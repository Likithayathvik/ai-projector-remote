package com.projector.airemote.net

import android.content.Context
import android.content.SharedPreferences

class AppSettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    var netflixPackage: String
        get() = prefs.getString("netflix_package", "com.netflix.ninja").orEmpty()
        set(v) = prefs.edit().putString("netflix_package", v).apply()

    var youtubePackage: String
        get() = prefs.getString("youtube_package", "com.google.android.youtube.tv").orEmpty()
        set(v) = prefs.edit().putString("youtube_package", v).apply()

    var primeVideoPackage: String
        get() = prefs.getString("prime_video_package", "com.amazon.amazonvideo.livingroom").orEmpty()
        set(v) = prefs.edit().putString("prime_video_package", v).apply()

    var hotstarPackage: String
        get() = prefs.getString("hotstar_package", "in.startv.hotstar").orEmpty()
        set(v) = prefs.edit().putString("hotstar_package", v).apply()

    var usbSourceCommand: String
        get() = prefs.getString("usb_source_command", DEFAULT_USB_SOURCE_COMMAND).orEmpty()
        set(v) = prefs.edit().putString("usb_source_command", v).apply()

    // GCP VM WebSocket URL — set this to your VM's external IP
    var serverUrl: String
        get() = prefs.getString("server_url", "ws://34.170.101.179:8765").orEmpty()
        set(v) = prefs.edit().putString("server_url", v).apply()

    companion object {
        // KEYCODE_TV_INPUT (178) opens the projector's input-source picker; switch_to_hdmi/
        // switch_to_usb in ToolDispatcher depend on this actually opening the picker before
        // navigating it, so an empty default silently breaks both.
        private const val DEFAULT_USB_SOURCE_COMMAND = "input keyevent 178"
    }
}
