package com.boat.cineagent.net

import android.util.Log
import com.boat.cineagent.auth.AuthRateLimiter
import com.boat.cineagent.auth.SessionStore
import com.boat.cineagent.commands.CommandHandler
import com.boat.cineagent.commands.ErrorCode
import org.json.JSONException
import org.json.JSONObject
import java.net.ServerSocket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// Persistent multi-client TCP command server (spec section 4-6). Idle clients are warned
// (TCP_DISCONNECT_WARNING) after HEARTBEAT_WARN_MS and only actually dropped after the much
// longer HEARTBEAT_TIMEOUT_MS, so a client on a lossy link that answers the warning with a
// single PING is never disconnected. Truly dead peers are caught by SO_KEEPALIVE and the
// hard timeout.
//
// Authenticated connection: the first message must be
//   {"cmd":"AUTH","sessionId":"…","sessionKey":"…"}
// with a session (Key #2) issued by UdpDiscoveryServer. Any other first message, a wrong key,
// or silence for AUTH_TIMEOUT_MS closes the socket. After AUTH,
//   {"cmd":"DISCONNECT","sessionId":"…","sessionKey":"…"}
// deletes that phone's Key #2 and closes every connection using it; other phones' sessions
// are untouched. Only authenticated clients count as connected and receive pushed events.
class TcpCommandServer(
    private val port: Int,
    private val commandHandler: CommandHandler,
    private val sessionStore: SessionStore,
    private val onClientCountChanged: (Int) -> Unit = {}
) {
    companion object {
        private const val TAG = "TcpCommandServer"
        // First: warn once. Then: only if still silent this much later, disconnect.
        private const val HEARTBEAT_WARN_MS = 45_000L
        private const val HEARTBEAT_TIMEOUT_MS = 120_000L
        private const val MONITOR_INTERVAL_MS = 5_000L
        private const val AUTH_TIMEOUT_MS = 10_000L
        private const val MAX_LINE_LENGTH = 64 * 1024
        /** Commands allowed to wait behind the running one, per phone. A tap beyond this drops
         *  the oldest waiting command, so fast repeated taps (HDMI/USB, Power, volume) never
         *  build a long backlog and the most recent taps always run. */
        private const val MAX_QUEUED_COMMANDS = 3
        /** A connected phone PINGs every 5 s; silent this long, its connection is treated as
         *  dead (Wi-Fi gone without a close) so it can't keep another phone locked out. */
        private const val STALE_CLIENT_MS = 15_000L
    }

    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<ClientConnection>()
    private val rateLimiter = AuthRateLimiter()

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

    private fun authenticatedCount(): Int = clients.count { it.isAuthenticated }

    // Callers include BroadcastReceiver.onReceive(), which runs on the main
    // thread; socket writes must never happen there, so always hop off first.
    fun broadcast(event: JSONObject) {
        thread(name = "cineagent-broadcast") {
            for (client in clients) {
                if (!client.isAuthenticated) continue
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

            if (rateLimiter.isBlocked(socket.inetAddress?.hostAddress ?: "unknown")) {
                try { socket.close() } catch (t: Throwable) {}
                continue
            }
            socket.tcpNoDelay = true
            socket.keepAlive = true
            val client = ClientConnection(socket)
            clients.add(client)
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
        // At most MAX_QUEUED_COMMANDS wait behind the running command; see enqueue().
        val queue = ArrayBlockingQueue<Runnable>(MAX_QUEUED_COMMANDS)
        val commandExecutor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, queue) { r ->
            Thread(r, "cineagent-cmd-${client.id}")
        }
        try {
            while (running && !client.isClosed) {
                val line = client.reader.readLine() ?: break
                if (line.isBlank()) continue
                if (line.length > MAX_LINE_LENGTH) break
                client.markActivity()

                val request = try {
                    JSONObject(line)
                } catch (e: JSONException) {
                    if (!client.isAuthenticated) break
                    client.send(JSONObject().apply {
                        put("status", "ERROR")
                        put("errorCode", ErrorCode.INVALID_JSON)
                        put("message", ErrorCode.messageFor(ErrorCode.INVALID_JSON))
                    })
                    continue
                }
                val cmd = request.optString("cmd")
                val id: Any? = if (request.has("id")) request.get("id") else null

                if (!client.isAuthenticated) {
                    if (cmd != "AUTH") {
                        client.send(error(id, ErrorCode.NOT_AUTHENTICATED))
                        break
                    }
                    val sessionId = request.optString("sessionId", null)
                    if (!sessionStore.validate(sessionId, request.optString("sessionKey", null))) {
                        rateLimiter.recordFailure(client.address)
                        Log.w(TAG, "AUTH rejected from ${client.address}")
                        client.send(error(id, ErrorCode.AUTH_FAILED))
                        break
                    }
                    rateLimiter.recordSuccess(client.address)
                    if (!claimSlot(client, sessionId!!)) {
                        Log.i(TAG, "AUTH from ${client.address} refused: another phone is connected")
                        client.send(error(id, ErrorCode.DEVICE_BUSY))
                        break
                    }
                    client.send(success(id))
                    onClientCountChanged(authenticatedCount())
                    continue
                }

                when (cmd) {
                    // A repeated AUTH on an already authenticated connection is harmless.
                    "AUTH" -> {
                        client.send(success(id))
                        continue
                    }
                    "DISCONNECT" -> {
                        val sessionId = request.optString("sessionId", null)
                        if (sessionId == client.sessionId &&
                            sessionStore.revoke(sessionId, request.optString("sessionKey", null))
                        ) {
                            client.send(success(id))
                            for (other in clients) {
                                if (other.sessionId == sessionId) disconnect(other)
                            }
                        } else {
                            client.send(error(id, ErrorCode.AUTH_FAILED))
                        }
                        continue
                    }
                }

                // PING is answered right here on the read thread - it must never wait behind a
                // slow command, or the client's heartbeat trips.
                if (cmd == "PING") {
                    client.send(commandHandler.handle(request))
                    continue
                }

                enqueue(client, commandExecutor, queue, QueuedCommand(id, cmd) {
                    val response = commandHandler.handle(request)
                    try {
                        client.send(response)
                    } catch (t: Throwable) {
                        disconnect(client)
                    }
                })
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
                    !client.isAuthenticated && idleMs > AUTH_TIMEOUT_MS -> disconnect(client)
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
            if (client.isAuthenticated) onClientCountChanged(authenticatedCount())
        }
    }

    /**
     * One phone at a time. Lets [client] in when no other phone is connected, or when the only
     * connection(s) are this same phone's own earlier session (an old socket the phone already
     * abandoned, e.g. when Android cut it in the background) or have gone silent past
     * STALE_CLIENT_MS - those are closed. A different, live phone keeps the projector: false.
     * Synchronized so two phones authenticating at once can't both get in.
     */
    @Synchronized
    private fun claimSlot(client: ClientConnection, sessionId: String): Boolean {
        val now = System.currentTimeMillis()
        val others = clients.filter { it !== client && it.isAuthenticated }
        val blocking = others.any { it.sessionId != sessionId && now - it.lastActivityMillis < STALE_CLIENT_MS }
        if (blocking) return false
        others.forEach { disconnect(it) }
        client.sessionId = sessionId
        return true
    }

    private class QueuedCommand(val id: Any?, val cmd: String, private val body: () -> Unit) : Runnable {
        override fun run() = body()
    }

    /** Queues [command] behind the running one. When MAX_QUEUED_COMMANDS are already waiting,
     *  the oldest waiting command is dropped (answered COMMAND_DROPPED at once, so the phone
     *  doesn't wait out its timeout for it) and [command] takes the free slot. Only this
     *  client's read thread adds to the queue, so the retry below always finds room. */
    private fun enqueue(
        client: ClientConnection,
        executor: ThreadPoolExecutor,
        queue: ArrayBlockingQueue<Runnable>,
        command: QueuedCommand
    ) {
        while (!executor.isShutdown) {
            try {
                executor.execute(command)
                return
            } catch (e: RejectedExecutionException) {
                val dropped = queue.poll() as? QueuedCommand ?: continue
                Log.d(TAG, "Client ${client.id}: queue full, dropped ${dropped.cmd} (id=${dropped.id}) for ${command.cmd}")
                try {
                    client.send(error(dropped.id, ErrorCode.COMMAND_DROPPED))
                } catch (t: Throwable) {
                    disconnect(client)
                    return
                }
            }
        }
    }

    private fun success(id: Any?) = JSONObject().apply {
        if (id != null) put("id", id)
        put("status", "SUCCESS")
    }

    private fun error(id: Any?, code: Int) = JSONObject().apply {
        if (id != null) put("id", id)
        put("status", "ERROR")
        put("errorCode", code)
        put("message", ErrorCode.messageFor(code))
    }
}
