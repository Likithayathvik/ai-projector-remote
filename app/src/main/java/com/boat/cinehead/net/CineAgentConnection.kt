package com.boat.cinehead.net

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A single persistent TCP connection to CineAgent (Communication Spec section 4-6):
 * newline-delimited JSON, request/response correlated by "id"; unsolicited "event"
 * messages (section 7) are delivered separately via [onEvent] rather than resolving
 * any pending request.
 */
class CineAgentConnection private constructor(
    private val socket: Socket,
    private val reader: BufferedReader,
    private val output: OutputStream
) {
    private val writeLock = Mutex()
    private val idCounter = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readerJob: Job? = null

    @Volatile
    var onEvent: ((JSONObject) -> Unit)? = null

    val isConnected: Boolean get() = !socket.isClosed && socket.isConnected

    init {
        readerJob = scope.launch { readLoop() }
    }

    /** Sends a command and suspends for its matching response (by "id"), or null on
     *  timeout / connection loss. */
    suspend fun sendCommand(cmd: String, params: Map<String, Any?> = emptyMap(), timeoutMs: Long = TIMEOUT_MS): JSONObject? {
        val id = idCounter.getAndIncrement()
        val request = JSONObject().apply {
            put("id", id)
            put("cmd", cmd)
            params.forEach { (key, value) -> if (value != null) put(key, value) }
        }
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        return try {
            writeLock.withLock {
                withContext(Dispatchers.IO) {
                    output.write((request.toString() + "\n").toByteArray(Charsets.UTF_8))
                    output.flush()
                }
            }
            val response = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (response == null) {
                Log.w(TAG, "$cmd (id=$id): no response within ${timeoutMs}ms - isConnected=$isConnected")
            } else {
                Log.d(TAG, "$cmd (id=$id): response=$response")
            }
            response
        } catch (e: Exception) {
            Log.w(TAG, "$cmd (id=$id): send/await threw", e)
            null
        } finally {
            pending.remove(id)
        }
    }

    fun close() {
        readerJob?.cancel()
        pending.values.forEach { it.cancel() }
        pending.clear()
        try {
            socket.close()
        } catch (e: Exception) {
        }
    }

    private suspend fun readLoop() {
        try {
            while (true) {
                val line = withContext(Dispatchers.IO) { reader.readLine() } ?: break
                if (line.isBlank()) continue
                val json = try {
                    JSONObject(line)
                } catch (e: Exception) {
                    continue
                }
                if (json.has("id")) {
                    pending.remove(idAsLong(json.get("id")))?.complete(json)
                } else if (json.has("event")) {
                    onEvent?.invoke(json)
                }
            }
        } catch (e: Exception) {
            // Socket closed or read error - pending callers time out naturally.
            Log.w(TAG, "readLoop ended with exception", e)
        } finally {
            Log.w(TAG, "readLoop exiting - ${pending.size} pending request(s) will time out")
            pending.values.forEach { it.cancel() }
            pending.clear()
        }
    }

    private fun idAsLong(id: Any): Long = when (id) {
        is Number -> id.toLong()
        else -> id.toString().toLongOrNull() ?: -1L
    }

    companion object {
        private const val TAG = "CineAgentConnection"
        private const val CONNECT_TIMEOUT_MS = 3000
        private const val TIMEOUT_MS = 5000L

        suspend fun connect(host: String, port: Int): Result<CineAgentConnection> =
            withContext(Dispatchers.IO) {
                try {
                    val socket = Socket()
                    socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    socket.tcpNoDelay = true
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    val output = socket.getOutputStream()
                    Result.success(CineAgentConnection(socket, reader, output))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
    }
}
