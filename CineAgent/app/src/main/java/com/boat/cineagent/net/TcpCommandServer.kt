package com.boat.cineagent.net

import android.util.Log
import com.boat.cineagent.commands.CommandHandler
import com.boat.cineagent.commands.ErrorCode
import org.json.JSONException
import org.json.JSONObject
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.concurrent.thread

// Persistent multi-client TCP command server (spec section 4-6). Idle clients are warned
// (TCP_DISCONNECT_WARNING) after HEARTBEAT_WARN_MS and only actually dropped after the much
// longer HEARTBEAT_TIMEOUT_MS, so a client on a lossy link that answers the warning with a
// single PING is never disconnected. Truly dead peers are caught by SO_KEEPALIVE and the
// hard timeout.
class TcpCommandServer(
    private val port: Int,
    private val commandHandler: CommandHandler,
    private val onClientCountChanged: (Int) -> Unit = {}
) {
    companion object {
        private const val TAG = "TcpCommandServer"
        // First: warn once. Then: only if still silent this much later, disconnect.
        private const val HEARTBEAT_WARN_MS = 45_000L
        private const val HEARTBEAT_TIMEOUT_MS = 120_000L
        private const val MONITOR_INTERVAL_MS = 5_000L
    }

    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<ClientConnection>()

    @Volatile
    private var running = false

    fun start() {
        running = true
        serverSocket = ServerSocket(port)
        thread(name = "cineagent-tcp-accept") { acceptLoop() }
        thread(name = "cineagent-heartbeat-monitor") { monitorLoop() }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (t: Throwable) {
        }
        for (client in clients) client.close()
        clients.clear()
        onClientCountChanged(0)
    }

    fun clientCount(): Int = clients.size

    // Callers include BroadcastReceiver.onReceive(), which runs on the main
    // thread; socket writes must never happen there, so always hop off first.
    fun broadcast(event: JSONObject) {
        thread(name = "cineagent-broadcast") {
            for (client in clients) {
                try {
                    client.send(event)
                } catch (t: Throwable) {
                    disconnect(client)
                }
            }
        }
    }

    private fun acceptLoop() {
        while (running) {
            val socket = try {
                serverSocket?.accept()
            } catch (t: Throwable) {
                null
            } ?: if (running) continue else break

            socket.tcpNoDelay = true
            socket.keepAlive = true
            val client = ClientConnection(socket)
            clients.add(client)
            onClientCountChanged(clients.size)
            thread(name = "cineagent-client-${client.id}") { handleClient(client) }
        }
    }

    private fun handleClient(client: ClientConnection) {
        // Commands run on this per-client worker, NOT on the read loop below. OPEN_SEARCH /
        // SELECT_SOURCE poll the UI for many seconds (Thread.sleep loops up to a deadline);
        // running handle() inline used to block client.reader.readLine(), so the client's PINGs
        // piled up unread, it declared the connection dead and reconnected - repeatedly, under
        // load. A single thread (not a pool) keeps commands serialized so two never drive the
        // accessibility UI at once; responses carry "id", so out-of-order delivery is fine.
        val commandExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "cineagent-cmd-${client.id}")
        }
        try {
            while (running && !client.isClosed) {
                val line = client.reader.readLine() ?: break
                if (line.isBlank()) continue
                client.markActivity()

                val request = try {
                    JSONObject(line)
                } catch (e: JSONException) {
                    client.send(JSONObject().apply {
                        put("status", "ERROR")
                        put("errorCode", ErrorCode.INVALID_JSON)
                        put("message", ErrorCode.messageFor(ErrorCode.INVALID_JSON))
                    })
                    continue
                }

                // PING is answered right here on the read thread - it must never wait behind a
                // slow command, or the client's heartbeat trips.
                if (request.optString("cmd") == "PING") {
                    client.send(commandHandler.handle(request))
                    continue
                }

                commandExecutor.execute {
                    val response = commandHandler.handle(request)
                    try {
                        client.send(response)
                    } catch (t: Throwable) {
                        disconnect(client)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "Client ${client.id} disconnected: ${t.message}")
        } finally {
            commandExecutor.shutdownNow()
            disconnect(client)
        }
    }

    private fun monitorLoop() {
        while (running) {
            try {
                Thread.sleep(MONITOR_INTERVAL_MS)
            } catch (t: InterruptedException) {
                break
            }
            val now = System.currentTimeMillis()
            for (client in clients) {
                val idleMs = now - client.lastActivityMillis
                when {
                    idleMs > HEARTBEAT_TIMEOUT_MS -> {
                        try {
                            client.send(JSONObject().apply {
                                put("event", "TCP_DISCONNECT_WARNING")
                                put("reason", "Heartbeat timeout")
                            })
                        } catch (t: Throwable) {
                        }
                        disconnect(client)
                    }
                    idleMs > HEARTBEAT_WARN_MS && !client.disconnectWarned -> {
                        client.disconnectWarned = true
                        try {
                            client.send(JSONObject().apply {
                                put("event", "TCP_DISCONNECT_WARNING")
                                put("reason", "No heartbeat - send a PING to stay connected")
                            })
                        } catch (t: Throwable) {
                        }
                    }
                }
            }
        }
    }

    private fun disconnect(client: ClientConnection) {
        if (clients.remove(client)) {
            client.close()
            onClientCountChanged(clients.size)
        }
    }
}
