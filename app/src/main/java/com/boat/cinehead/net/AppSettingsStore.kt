package com.boat.cinehead.net

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
}
