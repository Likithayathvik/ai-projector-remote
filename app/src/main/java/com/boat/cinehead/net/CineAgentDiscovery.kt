package com.boat.cinehead.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
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
    val capabilities: List<String>
)

/**
 * UDP discovery per the CineAgent Communication Specification section 3: broadcasts
 * DISCOVER_CINEAGENT on port 50000 and streams back each DISCOVERY_RESPONSE received
 * within a fixed 3-second window (per the spec's own guidance to the mobile app).
 */
class CineAgentDiscovery {

    suspend fun scan(onFound: suspend (DiscoveredProjector) -> Unit) = withContext(Dispatchers.IO) {
        val socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(0))
            }
        } catch (e: Exception) {
            return@withContext
        }

        try {
            val request = JSONObject().apply {
                put("type", "DISCOVER_CINEAGENT")
                put("version", 1)
            }
            val requestBytes = request.toString().toByteArray(Charsets.UTF_8)
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

    private fun parseResponse(packet: DatagramPacket): DiscoveredProjector? {
        val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            return null
        }
        if (json.optString("type") != "DISCOVERY_RESPONSE") return null

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
            capabilities = capabilities
        )
    }

    companion object {
        const val DISCOVERY_PORT = 50000
        const val DEFAULT_TCP_PORT = 50001
        private const val SCAN_WINDOW_MS = 3000L
        private const val SOCKET_POLL_MS = 500L
    }
}
