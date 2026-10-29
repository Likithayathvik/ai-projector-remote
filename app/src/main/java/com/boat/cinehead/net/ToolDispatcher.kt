package com.boat.cinehead.net

/**
 * Maps tool names - from the on-screen remote buttons and CommandRouter's chat/voice results -
 * onto CineAgent commands, via [RemoteExecutor] so the mapping stays identical whether
 * triggered by touch or by voice/chat.
 */
class ToolDispatcher(
    private val appSettings: AppSettingsStore,
    private val executorProvider: () -> RemoteExecutor?
) {

    suspend fun dispatch(toolName: String, args: Map<String, Any?> = emptyMap()): CommandResult {
        val executor = executorProvider() ?: return CommandResult(false, "Not connected to a projector")
        return when (toolName) {
            "volume_up" -> executor.sendCommand("VOLUME_UP")
            "volume_down" -> executor.sendCommand("VOLUME_DOWN")
            "set_volume" -> setVolume(executor, (args["percent"] as? Number)?.toInt())
            "mute" -> executor.sendCommand("MUTE")
            "unmute" -> executor.sendCommand("UNMUTE")
            "home" -> executor.sendCommand("HOME")
            "back" -> executor.sendCommand("BACK")
            "menu" -> executor.sendCommand("MENU")
            // CineAgent detects which app is in the foreground itself and opens that app's
            // search screen the right way for it - no per-app branching needed here. An
            // optional "query" arg is passed straight through to CineAgent's OPEN_SEARCH -
            // it's only actually used by the YouTube TV and Prime Video branches today (see
            // CommandHandler.handleOpenSearch()); other apps just open the blank search
            // screen regardless of a supplied query. See understanding.docx for why each
            // app needs a different mechanism under the hood (some support it, some
            // genuinely don't - e.g. Hotstar returns UNSUPPORTED_COMMAND, surfaced via
            // CommandResult like any other failure).
            "search" -> executor.sendCommand(
                "OPEN_SEARCH",
                mapOf("query" to ((args["query"] as? String)?.trim().orEmpty()))
            )
            "up" -> executor.sendCommand("DPAD_UP")
            "down" -> executor.sendCommand("DPAD_DOWN")
            "left" -> executor.sendCommand("DPAD_LEFT")
            "right" -> executor.sendCommand("DPAD_RIGHT")
            "ok" -> executor.sendCommand("DPAD_CENTER")
            "power" -> executor.sendCommand("POWER")
            "switch_to_hdmi" -> executor.sendCommand("SELECT_SOURCE", mapOf("source" to "HDMI"))
            "switch_to_usb" -> executor.sendCommand("SELECT_SOURCE", mapOf("source" to "USB"))
            "launch_netflix" -> launchOrReportMissing(
                executor, "Netflix", listOf(appSettings.netflixPackage) + NETFLIX_FALLBACKS
            )
            "launch_youtube" -> launchOrReportMissing(
                executor, "YouTube", listOf(appSettings.youtubePackage) + YOUTUBE_FALLBACKS
            )
            "launch_prime_video" -> launchOrReportMissing(
                executor, "Prime Video", listOf(appSettings.primeVideoPackage) + PRIME_VIDEO_FALLBACKS
            )
            "launch_hotstar" -> launchOrReportMissing(
                executor, "Hotstar", listOf(appSettings.hotstarPackage) + HOTSTAR_FALLBACKS
            )
            "media_play_pause" -> executor.sendCommand("PLAY_PAUSE")
            "media_next" -> executor.sendCommand("NEXT")
            "media_previous" -> executor.sendCommand("PREVIOUS")
            "media_rewind" -> seekRelative(executor, -SEEK_STEP_MS)
            "media_fast_forward" -> seekRelative(executor, SEEK_STEP_MS)
            "open_settings" -> executor.sendCommand("OPEN_SETTINGS")
            "open_wifi_settings" -> executor.sendCommand("OPEN_WIFI")
            "open_bluetooth_settings" -> executor.sendCommand("OPEN_BLUETOOTH")
            else -> CommandResult(false, "Unknown tool: $toolName")
        }
    }

    /** CineAgent has no relative seek, only SEEK_TO an absolute position - reads the
     *  current position via GET_PLAYBACK_STATE first, then seeks +/- [deltaMs] from there. */
    private suspend fun seekRelative(executor: RemoteExecutor, deltaMs: Long): CommandResult {
        val state = executor.query("GET_PLAYBACK_STATE")
            ?: return CommandResult(false, "Could not read the current playback position")
        if (state.optString("status") != "SUCCESS") {
            return CommandResult(false, state.optString("message", "No active media session"))
        }
        val targetPosition = (state.optLong("position", 0L) + deltaMs).coerceAtLeast(0L)
        return executor.sendCommand("SEEK_TO", mapOf("position" to targetPosition))
    }

    /**
     * The same app ships under different package names depending on the projector's firmware
     * (e.g. this fleet's Netflix is the mobile "com.netflix.mediaclient" build, not the Android
     * TV "com.netflix.ninja" one we'd assumed) - try the user-configured package first, then a
     * short list of known alternates. CineAgent's OPEN_APP reports APP_NOT_INSTALLED (1004)
     * directly, so a fallback only continues past a genuine "not installed" - any other failure
     * stops the loop and surfaces immediately.
     */
    private suspend fun launchOrReportMissing(
        executor: RemoteExecutor,
        friendlyName: String,
        candidatePackages: List<String>
    ): CommandResult {
        val tried = mutableListOf<String>()
        for (packageName in candidatePackages.distinct()) {
            if (packageName.isBlank()) continue
            tried += packageName
            val result = executor.sendCommand("OPEN_APP", mapOf("package" to packageName))
            if (result.success) return CommandResult(true, "$friendlyName launched ($packageName)")
            if (result.errorCode != CineAgentErrorCodes.APP_NOT_INSTALLED) return result
        }
        return CommandResult(false, "$friendlyName is not installed on this projector (checked: ${tried.joinToString(", ")})")
    }

    /**
     * Takes a 0-100 percentage - CineAgent's SET_VOLUME already operates directly in
     * percent, so unlike the old ADB path there's no per-device max-index conversion needed.
     */
    private suspend fun setVolume(executor: RemoteExecutor, percent: Int?): CommandResult {
        if (percent == null) return CommandResult(false, "set_volume requires a 'percent' argument (0-100)")
        val clampedPercent = percent.coerceIn(0, 100)
        val result = executor.setVolumeIndex(clampedPercent)
        return if (result.success) CommandResult(true, "Volume set to $clampedPercent%") else result
    }

    companion object {
        /** How far media_rewind/media_fast_forward move the playback position per tap. */
        private const val SEEK_STEP_MS = 10_000L

        // Verified live against a real projector (SigmaStar pioneer5, 2026-07-09): its Netflix
        // is the mobile build, not the Android TV one AppSettingsStore defaults to.
        private val NETFLIX_FALLBACKS = listOf("com.netflix.mediaclient", "com.netflix.ninja")
        // Whale TV (HOTACK SMART2KPJT, 2026-10-15) ships its own rebranded YouTube TV build.
        private val YOUTUBE_FALLBACKS = listOf(
            "com.google.android.youtube.tv", "com.google.android.youtube", "com.WhaleTV.whaleos.youtube.tv"
        )
        // Not verified against real hardware (neither was installed on the test unit) - best
        // effort based on known common package ids for these apps.
        private val PRIME_VIDEO_FALLBACKS = listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient")
        private val HOTSTAR_FALLBACKS = listOf("in.startv.hotstar", "in.startv.hotstar.dplus")
    }
}
