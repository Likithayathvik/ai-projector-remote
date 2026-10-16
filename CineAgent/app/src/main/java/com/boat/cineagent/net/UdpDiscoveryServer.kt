package com.boat.cineagent.net

import android.content.Context
import android.util.Log
import com.boat.cineagent.commands.CommandHandler
import com.boat.cineagent.util.DeviceInfoProvider
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import kotlin.concurrent.thread

// UDP discovery listener (spec section 3): answers DISCOVER_CINEAGENT
// broadcasts directly to the sender with a DISCOVERY_RESPONSE.
class UdpDiscoveryServer(
    private val udpPort: Int,
    private val tcpPort: Int,
    private val context: Context
) {
    companion object {
        private const val TAG = "UdpDiscoveryServer"
        private const val REQUEST_TYPE = "DISCOVER_CINEAGENT"
    }

    private var socket: DatagramSocket? = null

    @Volatile
    private var running = false

    fun start() {
        running = true
        thread(name = "cineagent-udp-discovery") { listenLoop() }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (t: Throwable) {
        }
    }

    private fun listenLoop() {
        val udpSocket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(udpPort))
                broadcast = true
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to bind UDP discovery socket on port $udpPort", t)
            return
        }
        socket = udpSocket

        val buffer = ByteArray(2048)
        while (running) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                udpSocket.receive(packet)
                handlePacket(udpSocket, packet)
            } catch (t: Throwable) {
                if (running) Log.d(TAG, "UDP receive error: ${t.message}")
            }
        }
    }

    private fun handlePacket(udpSocket: DatagramSocket, packet: DatagramPacket) {
        val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
        val request = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return
        }
        if (request.optString("type") != REQUEST_TYPE) return

        val response = JSONObject().apply {
            put("type", "DISCOVERY_RESPONSE")
            put("deviceName", DeviceInfoProvider.deviceName(context))
            put("model", DeviceInfoProvider.model())
            put("serialNumber", DeviceInfoProvider.serialNumber(context))
            put("ip", DeviceInfoProvider.localIpAddress() ?: "")
            put("tcpPort", tcpPort)
            put("firmwareVersion", DeviceInfoProvider.firmwareVersion())
            put("agentVersion", DeviceInfoProvider.agentVersion())
            put("protocolVersion", DeviceInfoProvider.protocolVersion())
            put("capabilities", JSONArray(CommandHandler.CAPABILITIES))
        }

        val payload = response.toString().toByteArray(Charsets.UTF_8)
        val replyPacket = DatagramPacket(payload, payload.size, packet.address, packet.port)
        try {
            udpSocket.send(replyPacket)
        } catch (t: Throwable) {
            Log.d(TAG, "Failed to send discovery response: ${t.message}")
        }
    }
}
