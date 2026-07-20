package com.projector.airemote.net

import kotlinx.coroutines.delay

/**
 * Maps the Gemini tool-calling function names (SRS section 6) and the on-screen
 * remote buttons onto ADB commands, via [RemoteExecutor] so the mapping stays
 * identical whether triggered by touch or by voice/chat.
 */
class ToolDispatcher(
    private val appSettings: AppSettingsStore,
    private val executorProvider: () -> RemoteExecutor?
) {

    suspend fun dispatch(toolName: String, args: Map<String, Any?> = emptyMap()): CommandResult {
        val executor = executorProvider() ?: return CommandResult(false, "Not connected to a projector")
        return when (toolName) {
            "volume_up" -> executor.keyEvent(KeyCodes.VOLUME_UP)
            "volume_down" -> executor.keyEvent(KeyCodes.VOLUME_DOWN)
            "set_volume" -> setVolume(executor, (args["percent"] as? Number)?.toInt())
            "mute" -> executor.keyEvent(KeyCodes.MUTE)
            "home" -> executor.keyEvent(KeyCodes.HOME)
            "back" -> executor.keyEvent(KeyCodes.BACK)
            "menu" -> executor.keyEvent(KeyCodes.MENU)
            "up" -> executor.keyEvent(KeyCodes.UP)
            "down" -> executor.keyEvent(KeyCodes.DOWN)
            "left" -> executor.keyEvent(KeyCodes.LEFT)
            "right" -> executor.keyEvent(KeyCodes.RIGHT)
            "ok" -> executor.keyEvent(KeyCodes.OK)
            "power" -> executor.keyEvent(KeyCodes.POWER)
            "switch_to_hdmi" -> switchToHdmi(executor)
            "switch_to_usb" -> switchToUsb(executor)
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
            "media_play" -> executor.keyEvent(KeyCodes.MEDIA_PLAY)
            "media_pause" -> executor.keyEvent(KeyCodes.MEDIA_PAUSE)
            "media_play_pause" -> executor.keyEvent(KeyCodes.MEDIA_PLAY_PAUSE)
            "media_stop" -> executor.keyEvent(KeyCodes.MEDIA_STOP)
            "media_next" -> executor.keyEvent(KeyCodes.MEDIA_NEXT)
            "media_previous" -> executor.keyEvent(KeyCodes.MEDIA_PREVIOUS)
            "media_rewind" -> executor.keyEvent(KeyCodes.MEDIA_REWIND)
            "media_fast_forward" -> executor.keyEvent(KeyCodes.MEDIA_FAST_FORWARD)
            "open_settings" -> executor.rawShell("am start -a ${SystemIntents.SETTINGS}")
            "open_wifi_settings" -> executor.rawShell("am start -a ${SystemIntents.WIFI_SETTINGS}")
            "open_bluetooth_settings" -> executor.rawShell("am start -a ${SystemIntents.BLUETOOTH_SETTINGS}")
            else -> CommandResult(false, "Unknown tool: $toolName")
        }
    }

    /** Selecting USB opens the source picker (usbSourceCommand), waits for it to render, then
     *  navigates down to the USB entry, waits for the selection to register, and confirms it. */
    private suspend fun switchToUsb(executor: RemoteExecutor): CommandResult {
        val result = executor.rawShell(appSettings.usbSourceCommand)
        if (!result.success) return result
        delay(SOURCE_PICKER_DELAY_MS)
        executor.keyEvent(KeyCodes.DOWN)
        delay(USB_DOWN_TO_OK_DELAY_MS)
        return executor.keyEvent(KeyCodes.OK)
    }

    /** Opens the same source picker as USB (not the full USB sequence - no Down here), waits
     *  for it to render, then confirms. */
    private suspend fun switchToHdmi(executor: RemoteExecutor): CommandResult {
        val result = executor.rawShell(appSettings.usbSourceCommand)
        if (!result.success) return result
        delay(SOURCE_PICKER_DELAY_MS)
        return executor.keyEvent(KeyCodes.OK)
    }

    /**
     * The same app ships under different package names depending on the projector's firmware
     * (e.g. this fleet's Netflix is the mobile "com.netflix.mediaclient" build, not the Android
     * TV "com.netflix.ninja" one we'd assumed) - try the user-configured package first, then a
     * short list of known alternates, rather than failing on the first guess.
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
            if (executor.isPackageInstalled(packageName)) {
                val result = executor.launchPackage(packageName)
                return if (result.success) CommandResult(true, "$friendlyName launched ($packageName)") else result
            }
        }
        return CommandResult(false, "$friendlyName is not installed on this projector (checked: ${tried.joinToString(", ")})")
    }

    /**
     * Takes a 0-100 percentage rather than a raw stream index, since the actual max index
     * varies by projector (observed 15 on some devices, 100 on others) - percent is the only
     * value that means the same thing regardless of hardware.
     */
    private suspend fun setVolume(executor: RemoteExecutor, percent: Int?): CommandResult {
        if (percent == null) return CommandResult(false, "set_volume requires a 'percent' argument (0-100)")
        val clampedPercent = percent.coerceIn(0, 100)
        val info = executor.getVolume()
            ?: return CommandResult(false, "Could not read the projector's current volume range")
        val targetIndex = (info.max * clampedPercent + 50) / 100
        val result = executor.setVolumeIndex(targetIndex)
        return if (result.success) {
            CommandResult(true, "Volume set to $clampedPercent% ($targetIndex/${info.max})")
        } else {
            result
        }
    }

    companion object {
        /** How long the source picker (opened by usbSourceCommand) takes to render before a
         *  Down or OK key event will register against the right item. */
        private const val SOURCE_PICKER_DELAY_MS = 3000L
        /** How long after Down for the USB entry's selection highlight to settle before OK
         *  confirms it. */
        private const val USB_DOWN_TO_OK_DELAY_MS = 1000L

        // Verified live against a real projector (SigmaStar pioneer5, 2026-07-09): its Netflix
        // is the mobile build, not the Android TV one AppSettingsStore defaults to.
        private val NETFLIX_FALLBACKS = listOf("com.netflix.mediaclient", "com.netflix.ninja")
        private val YOUTUBE_FALLBACKS = listOf("com.google.android.youtube.tv", "com.google.android.youtube")
        // Not verified against real hardware (neither was installed on the test unit) - best
        // effort based on known common package ids for these apps.
        private val PRIME_VIDEO_FALLBACKS = listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient")
        private val HOTSTAR_FALLBACKS = listOf("in.startv.hotstar", "in.startv.hotstar.dplus")
    }
}
