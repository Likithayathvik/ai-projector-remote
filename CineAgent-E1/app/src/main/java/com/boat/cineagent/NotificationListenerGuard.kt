package com.boat.cineagent

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.boat.cineagent.media.MediaNotificationListenerService

// Keeps this app's MediaNotificationListenerService approved as a notification listener.
//
// On Android 11+ NotificationManagerService keeps its own approved-listener list (persisted in
// notification_policy.xml); Settings.Secure.enabled_notification_listeners is only a mirror it
// writes out. Writing the setting directly does NOT approve anything - confirmed live on the E1
// (Android 13): the setting held our component while "Allowed notification listeners" stayed
// empty and the listener was never bound.
//
// The real approval is NotificationManager.setNotificationListenerAccessGranted(), the same call
// `adb shell cmd notification allow_listener` makes. It needs MANAGE_NOTIFICATION_LISTENERS
// (signature|installer), which the platform-signed build holds. The method is @SystemApi, so it is
// reached by reflection (platform-signed apps are exempt from hidden-API enforcement). NMS
// persists the approval itself, so it survives reboots (verified on
// the E1); this guard runs on launch and boot only to repair it if something ever revokes it.
object NotificationListenerGuard {
    private const val TAG = "CineAgentNotifGuard"
    private const val SETTING = "enabled_notification_listeners"

    /** Approves this app's notification listener if not already approved. No-ops if approved, and
     *  silently no-ops if MANAGE_NOTIFICATION_LISTENERS isn't held (non-platform-signed build). */
    fun ensureEnabled(context: Context) {
        val component = ComponentName(context, MediaNotificationListenerService::class.java)
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.isNotificationListenerAccessGranted(component)) return

        try {
            NotificationManager::class.java
                .getMethod("setNotificationListenerAccessGranted", ComponentName::class.java, Boolean::class.javaPrimitiveType)
                .invoke(nm, component, true)
            Log.i(TAG, "Approved notification listener via NotificationManager")
        } catch (e: Throwable) {
            // Permission not held, or the hidden API is blocked on this build. Fall back to the
            // settings write - harmless where it is ignored, and still effective on old releases.
            Log.w(TAG, "Could not approve listener via NotificationManager: ${e.javaClass.simpleName}: ${e.cause?.message ?: e.message}")
            writeSetting(context, component.flattenToString())
        }
    }

    private fun writeSetting(context: Context, flattened: String) {
        val resolver = context.contentResolver
        val listeners = Settings.Secure.getString(resolver, SETTING)
            ?.split(":")?.filter { it.isNotBlank() }.orEmpty()
        if (flattened in listeners) return
        try {
            Settings.Secure.putString(resolver, SETTING, (listeners + flattened).joinToString(":"))
            Log.i(TAG, "Added notification listener to $SETTING")
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot write $SETTING: ${e.message}")
        }
    }
}
