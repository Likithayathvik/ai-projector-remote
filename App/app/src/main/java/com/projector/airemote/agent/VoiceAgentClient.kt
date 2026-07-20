package com.projector.airemote.agent

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket client that connects to our GCP Voice Agent server.
 * Per-query connection: connects when query sent, disconnects after response.
 * No persistent idle connection — server always runs, app connects per query.
 */
class VoiceAgentClient(
    private val serverUrl: String,
    private val onCommand: suspend (action: String, args: Map<String, Any?>) -> Unit,
    private val onTextResponse: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    /**
     * Connects to the server, sends the query immediately on connection,
     * and disconnects after receiving the response.
     *
     * Server runs continuously — this just opens a connection per query.
     * Flow: connect → send query → receive response → disconnect
     */
    fun connectAndSend(text: String) {
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected — sending: $text")
                onConnected()
                // Send query immediately after connecting
                val json = JSONObject().apply {
                    put("type", "text")
                    put("message", text)
                }
                webSocket.send(json.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received: $text")
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {

                        "command" -> {
                            // Tool was called — execute via ADB on projector
                            val action = json.optString("action")
                            val argsJson = json.optJSONObject("args")
                            val args = mutableMapOf<String, Any?>()
                            argsJson?.keys()?.forEach { key ->
                                args[key] = argsJson.get(key)
                            }
                            scope.launch { onCommand(action, args) }
                            // Disconnect after command received
                            webSocket.close(1000, "Query complete")
                        }

                        "text_response" -> {
                            // Direct text response from agent
                            onTextResponse(json.optString("message"))
                            // Disconnect after response received
                            webSocket.close(1000, "Query complete")
                        }

                        "error" -> {
                            onError(json.optString("message", "Unknown error"))
                            webSocket.close(1000, "Error received")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing: $text", e)
                    onError("Failed to parse server response")
                    webSocket.close(1000, "Parse error")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                onError("Connection error: ${t.message}")
                onDisconnected()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Closed: $code $reason")
                onDisconnected()
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
    }

    companion object {
        private const val TAG = "VoiceAgentClient"
    }
}
