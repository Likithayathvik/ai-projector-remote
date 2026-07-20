package com.projector.airemote.net

/**
 * Logical device-control surface used by the UI and the Gemini tool dispatcher.
 * ADB-over-TCP is the only implementation today (see [com.projector.airemote.net.AdbRemoteExecutor]);
 * a future MCP-based executor can implement this same interface without touching
 * the UI or tool-calling layers (SRS 9/10: "Modular executor to replace ADB with MCP later").
 */
interface RemoteExecutor {
    val isConnected: Boolean
    suspend fun keyEvent(keyCode: Int): CommandResult
    suspend fun launchPackage(packageName: String): CommandResult
    suspend fun isPackageInstalled(packageName: String): Boolean
    suspend fun getVolume(streamId: Int = AudioStreams.MUSIC): VolumeInfo?
    suspend fun setVolumeIndex(index: Int, streamId: Int = AudioStreams.MUSIC): CommandResult
    suspend fun rawShell(command: String): CommandResult
    fun disconnect()
}

data class CommandResult(val success: Boolean, val message: String)

/** The projector's actual current/max volume for a stream - read from the device, not guessed. */
data class VolumeInfo(val current: Int, val max: Int)

object AudioStreams {
    const val MUSIC = 3
}

object KeyCodes {
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val MUTE = 164
    const val HOME = 3
    const val BACK = 4
    const val MENU = 82
    const val UP = 19
    const val DOWN = 20
    const val LEFT = 21
    const val RIGHT = 22
    const val OK = 23
    const val POWER = 26
    const val MEDIA_PLAY_PAUSE = 85
    const val MEDIA_STOP = 86
    const val MEDIA_NEXT = 87
    const val MEDIA_PREVIOUS = 88
    const val MEDIA_REWIND = 89
    const val MEDIA_FAST_FORWARD = 90
    const val MEDIA_PLAY = 126
    const val MEDIA_PAUSE = 127
}

/** Standard AOSP intent actions - resolved by whatever settings app is installed,
 *  rather than a hardcoded component that may not exist on every projector's firmware. */
object SystemIntents {
    const val SETTINGS = "android.settings.SETTINGS"
    const val WIFI_SETTINGS = "android.settings.WIFI_SETTINGS"
    const val BLUETOOTH_SETTINGS = "android.settings.BLUETOOTH_SETTINGS"
}
