package com.boat.cineagent.net

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Socket

// Newline-delimited UTF-8 JSON in both directions, per spec section 4.2.
class ClientConnection(val socket: Socket) {

    val id: String = "${socket.inetAddress?.hostAddress}:${socket.port}"
    val reader: BufferedReader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
    private val output: OutputStream = socket.getOutputStream()
    private val writeLock = Any()

    @Volatile
    var lastActivityMillis: Long = System.currentTimeMillis()
        private set

    /** Set once the heartbeat monitor has sent this client a TCP_DISCONNECT_WARNING; cleared
     *  the moment any line is received again, so a client that answers the warning with a PING
     *  is never disconnected. */
    @Volatile
    var disconnectWarned: Boolean = false

    fun markActivity() {
        lastActivityMillis = System.currentTimeMillis()
        disconnectWarned = false
    }

    fun send(json: JSONObject) {
        val payload = (json.toString() + "\n").toByteArray(Charsets.UTF_8)
        synchronized(writeLock) {
            output.write(payload)
            output.flush()
        }
    }

    fun close() {
        try {
            socket.close()
        } catch (t: Throwable) {
        }
    }

    val isClosed: Boolean
        get() = socket.isClosed
}
