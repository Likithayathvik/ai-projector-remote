package com.projector.airemote.net

import android.util.Log
import com.projector.airemote.adb.AdbConnection
import com.projector.airemote.adb.AdbResult

class AdbRemoteExecutor(private val connection: AdbConnection) : RemoteExecutor {

    override val isConnected: Boolean get() = connection.isConnected

    override suspend fun keyEvent(keyCode: Int): CommandResult =
        rawShell("input keyevent $keyCode")

    override suspend fun launchPackage(packageName: String): CommandResult {
        val launchResult = rawShell("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
        val output = launchResult.message
        val looksFailed = !launchResult.success ||
            output.contains("monkey aborted", ignoreCase = true) ||
            output.contains("No activities found", ignoreCase = true) ||
            output.contains("Unable to find package", ignoreCase = true)
        return if (looksFailed) CommandResult(false, output) else launchResult
    }

    override suspend fun isPackageInstalled(packageName: String): Boolean {
        val result = rawShell("pm list packages $packageName")
        return result.success && result.message.lineSequence().any { it.trim() == "package:$packageName" }
    }

    override suspend fun getVolume(streamId: Int): VolumeInfo? {
        val mediaResult = rawShell("cmd media_session volume --stream $streamId --get")
        parseMediaVolumeOutput(mediaResult.message)?.let { return it }

        val dumpsysResult = rawShell("dumpsys audio")
        parseDumpsysAudioOutput(dumpsysResult.message)?.let { return it }

        Log.w(TAG, "Could not parse volume from either 'cmd media_session volume --get' or 'dumpsys audio'." +
            " media_session output: ${mediaResult.message}")
        return null
    }

    override suspend fun setVolumeIndex(index: Int, streamId: Int): CommandResult =
        rawShell("cmd media_session volume --stream $streamId --set $index")

    /** Expected form: "volume is 8 in range [0..100]" (`cmd media_session volume --get`). */
    private fun parseMediaVolumeOutput(output: String): VolumeInfo? {
        val match = MEDIA_VOLUME_REGEX.find(output) ?: return null
        val (current, min, max) = match.destructured
        return VolumeInfo(current = current.toInt(), max = max.toInt())
    }

    /** Fallback: parse the STREAM_MUSIC block out of `dumpsys audio`, format varies by AOSP version. */
    private fun parseDumpsysAudioOutput(output: String): VolumeInfo? {
        val streamBlock = STREAM_MUSIC_BLOCK_REGEX.find(output)?.value ?: return null
        val max = MAX_REGEX.find(streamBlock)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val current = CURRENT_REGEX.find(streamBlock)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return VolumeInfo(current = current, max = max)
    }

    override suspend fun rawShell(command: String): CommandResult {
        return when (val result = connection.shell(command)) {
            is AdbResult.Success -> CommandResult(true, result.output.trim())
            is AdbResult.Failure -> CommandResult(false, result.reason)
        }
    }

    override fun disconnect() {
        connection.close()
    }

    companion object {
        private const val TAG = "AdbRemoteExecutor"
        private val MEDIA_VOLUME_REGEX = Regex("""volume is (\d+) in range \[(\d+)\.\.(\d+)]""")
        private val STREAM_MUSIC_BLOCK_REGEX = Regex("""STREAM_MUSIC[\s\S]*?(?=STREAM_|\z)""")
        private val MAX_REGEX = Regex("""Max:\s*(\d+)""")
        private val CURRENT_REGEX = Regex("""Current:\s*\d+\s*\(speaker\):\s*(\d+)""")
    }
}
