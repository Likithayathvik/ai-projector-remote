package com.boat.cinehead.net

import org.json.JSONObject

class CineAgentRemoteExecutor(private val connection: CineAgentConnection) : RemoteExecutor {

    override val isConnected: Boolean get() = connection.isConnected

    override suspend fun sendCommand(cmd: String, params: Map<String, Any?>): CommandResult =
        connection.sendCommand(cmd, params).toCommandResult()

    override suspend fun query(cmd: String, params: Map<String, Any?>): JSONObject? =
        connection.sendCommand(cmd, params)

    override suspend fun getVolume(): VolumeInfo? {
        val response = connection.sendCommand("GET_VOLUME") ?: return null
        if (response.optString("status") != "SUCCESS") return null
        return VolumeInfo(current = response.optInt("current"), max = response.optInt("maximum", 100))
    }

    override suspend fun setVolumeIndex(index: Int): CommandResult =
        sendCommand("SET_VOLUME", mapOf("value" to index))

    override suspend fun ping(): CommandResult =
        connection.sendCommand("PING", timeoutMs = CineAgentConnection.PING_TIMEOUT_MS).toCommandResult()

    override fun disconnect() {
        connection.close()
    }

    private fun JSONObject?.toCommandResult(): CommandResult {
        if (this == null) return CommandResult(false, "Command timed out or the connection was lost")
        val status = optString("status")
        return if (status == "SUCCESS" || status == "OK") {
            CommandResult(true, optString("message", "OK"))
        } else {
            CommandResult(
                success = false,
                message = optString("message", "Command failed"),
                errorCode = if (has("errorCode")) optInt("errorCode") else null
            )
        }
    }
}
