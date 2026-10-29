package com.boat.cinehead.net

import org.json.JSONObject

/**
 * Logical device-control surface used by the UI and [ToolDispatcher].
 * CineAgent's TCP/JSON command protocol is the implementation (see
 * [com.boat.cinehead.net.CineAgentRemoteExecutor]); any future backend can implement
 * this same interface without touching the UI or tool-calling layers.
 */
interface RemoteExecutor {
    val isConnected: Boolean

    /** Fire-and-report commands where only success/failure matters to the caller. */
    suspend fun sendCommand(cmd: String, params: Map<String, Any?> = emptyMap()): CommandResult

    /** Read-style commands where the caller needs the actual response payload
     *  (e.g. GET_PLAYBACK_STATE's position), not just success/failure. */
    suspend fun query(cmd: String, params: Map<String, Any?> = emptyMap()): JSONObject?

    suspend fun getVolume(): VolumeInfo?
    suspend fun setVolumeIndex(index: Int): CommandResult
    suspend fun ping(): CommandResult
    fun disconnect()
}

/** [errorCode] is CineAgent's spec section 8 error code (e.g. 1004 = app not
 *  installed) when the command failed, null on success or for errors with no code. */
data class CommandResult(val success: Boolean, val message: String, val errorCode: Int? = null)

/** The projector's actual current/max volume, 0-100 - CineAgent normalizes volume to a
 *  percentage itself, so max is always 100. */
data class VolumeInfo(val current: Int, val max: Int)

object CineAgentErrorCodes {
    const val APP_NOT_INSTALLED = 1004
    /** Another phone is already connected to the projector. */
    const val DEVICE_BUSY = 1007
    /** CineAgent keeps at most 3 commands waiting per phone; a newer tap pushed this one out. */
    const val COMMAND_DROPPED = 1011
}
