package com.boat.cinehead.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

data class DiscoveredProjector(
    val deviceName: String,
    val model: String,
    val serialNumber: String,
    val ipAddress: String,
    val tcpPort: Int,
    val firmwareVersion: String,
    val agentVersion: String,
    val capabilities: List<String>,
    /** Key #2 CineAgent issued to this phone in its reply; required to open the TCP session. */
    val credentials: SessionCredentials
)

/**
 * UDP discovery per the CineAgent Communication Specification section 3: broadcasts
 * DISCOVER_CINEAGENT on port 50000 and streams back each DISCOVERY_RESPONSE received
 * within a fixed 3-second window (per the spec's own guidance to the mobile app).
 *
 * Authenticated connection: every request carries the shared discovery key (Key #1) and this
 * phone's [clientId]. CineAgent ignores requests without a valid Key #1, and answers valid ones
 * with a session (Key #2) for this phone. Replies without a session (older CineAgent builds)
 * are skipped, since they could not be connected to anyway.
 */
class CineAgentDiscovery(private val clientId: String) {

    suspend fun scan(onFound: suspend (DiscoveredProjector) -> Unit) = withContext(Dispatchers.IO) {
        val socket = openSocket() ?: return@withContext

        try {
            val requestBytes = requestBytes()
            socket.send(
                DatagramPacket(requestBytes, requestBytes.size, InetSocketAddress("255.255.255.255", DISCOVERY_PORT))
            )

            val seenIps = mutableSetOf<String>()
            val deadline = System.currentTimeMillis() + SCAN_WINDOW_MS
            val buffer = ByteArray(4096)

            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                socket.soTimeout = remaining.coerceAtMost(SOCKET_POLL_MS).toInt()

                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }

                val response = parseResponse(packet) ?: continue
                if (!seenIps.add(response.ipAddress)) continue
                onFound(response)
            }
        } catch (e: IOException) {
            // No usable network route (e.g. Wi-Fi down / ENETUNREACH) or a transient
            // socket error: treat as "nothing discovered" rather than crashing the app.
        } finally {
            socket.close()
        }
    }

    /**
     * Unicast discovery to one known address: the manual-IP connect, and re-authentication when a
     * stored Key #2 is rejected (e.g. CineAgent reinstalled or the projector factory reset). Works
     * on networks that drop broadcast, as long as unicast UDP gets through. Retries a few times
     * because UDP can be lost; CineAgent returns the same session to repeated requests from this
     * phone within a few seconds, so whichever reply arrives carries the current key.
     */
    suspend fun discoverAt(ip: String): DiscoveredProjector? = withContext(Dispatchers.IO) {
        val target = try {
            InetAddress.getByName(ip)
        } catch (e: Exception) {
            return@withContext null
        }
        val socket = openSocket() ?: return@withContext null
        try {
            val requestBytes = requestBytes()
            val buffer = ByteArray(4096)
            repeat(UNICAST_ATTEMPTS) {
                socket.send(DatagramPacket(requestBytes, requestBytes.size, InetSocketAddress(target, DISCOVERY_PORT)))
                val deadline = System.currentTimeMillis() + UNICAST_WAIT_MS
                while (true) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    socket.soTimeout = remaining.toInt()
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        break
                    }
                    if (packet.address != target) continue
                    parseResponse(packet)?.let { return@withContext it }
                }
            }
            null
        } catch (e: IOException) {
            null
        } finally {
            socket.close()
        }
    }

    private fun openSocket(): DatagramSocket? = try {
        DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(0))
        }
    } catch (e: Exception) {
        null
    }

    private fun requestBytes(): ByteArray = JSONObject().apply {
        put("type", "DISCOVER_CINEAGENT")
        put("version", 1)
        put("key", DISCOVERY_KEY)
        put("clientId", clientId)
    }.toString().toByteArray(Charsets.UTF_8)

    private fun parseResponse(packet: DatagramPacket): DiscoveredProjector? {
        val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            return null
        }
        if (json.optString("type") != "DISCOVERY_RESPONSE") return null

        val sessionId = json.optString("sessionId")
        val sessionKey = json.optString("sessionKey")
        if (sessionId.isBlank() || sessionKey.isBlank()) return null

        val ip = json.optString("ip").ifBlank { packet.address?.hostAddress ?: return null }
        val capabilitiesJson = json.optJSONArray("capabilities")
        val capabilities = if (capabilitiesJson != null) {
            (0 until capabilitiesJson.length()).map { capabilitiesJson.optString(it) }
        } else {
            emptyList()
        }

        return DiscoveredProjector(
            deviceName = json.optString("deviceName", "CineAgent"),
            model = json.optString("model", ""),
            serialNumber = json.optString("serialNumber", ""),
            ipAddress = ip,
            tcpPort = json.optInt("tcpPort", DEFAULT_TCP_PORT),
            firmwareVersion = json.optString("firmwareVersion", ""),
            agentVersion = json.optString("agentVersion", ""),
            capabilities = capabilities,
            credentials = SessionCredentials(sessionId, sessionKey)
        )
    }

    companion object {
        const val DISCOVERY_PORT = 50000
        const val DEFAULT_TCP_PORT = 50001
        private const val SCAN_WINDOW_MS = 3000L
        private const val SOCKET_POLL_MS = 500L
        private const val UNICAST_ATTEMPTS = 3
        private const val UNICAST_WAIT_MS = 1000L

        /** Key #1: the shared discovery key CineAgent checks before answering. Never logged. */
        private const val DISCOVERY_KEY = "r31Co46Aej8cr28ePod46ienCe"
    }
}
