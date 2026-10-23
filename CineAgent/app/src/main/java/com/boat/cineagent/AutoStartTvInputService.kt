package com.boat.cineagent

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.tv.TvInputService
import android.os.Handler
import android.os.Looper
import android.util.Log

// Install-time self-start: the hook that makes a bare `adb install` enough, with no launch.
//
// A freshly installed (non-system) app is in the "stopped" state: Android delivers it no
// broadcasts at all - not even BOOT_COMPLETED, across any number of reboots (confirmed on the E1) -
// until something outside the app starts one of its components. Normally that is the user
// opening it.
//
// TvInputManagerService is that "something". It watches package changes and immediately binds
// every TvInputService whose app holds TV_INPUT_HARDWARE (signature|privileged - held via the
// platform signature), and keeps hardware services bound permanently (the firmware's own
// com.droidlogic.tvinput HDMI/AV services show `bound: true` with no sessions). Binding a service
// clears its package's stopped state, so from this point BootReceiver fires on every boot as well.
//
// This service exposes no inputs: onHardwareAdded()/onHdmiDeviceAdded() keep their default null,
// so nothing new appears in the projector's source list, and no session is ever created. Its only
// job is onCreate(), which runs the same bring-up as BootReceiver. Starting the foreground service
// from this background bind is allowed by START_FOREGROUND_SERVICES_FROM_BACKGROUND.
class AutoStartTvInputService : TvInputService() {

    companion object {
        private const val TAG = "CineAgentTvInput"

        @Volatile
        var isRunning: Boolean = false
            private set

        // An app update (`adb install -r`) kills this service, and TvInputManagerService keeps its
        // ServiceState as `bound: true, service: null` without ever rebinding - confirmed on the
        // E1. That stale state even survives an uninstall, so a later fresh install is never bound
        // and stays stopped. Toggling the component disabled/enabled makes TIMS drop the stale
        // state and bind afresh (same effect as `pm disable` + `pm enable`, verified live).
        // DONT_KILL_APP keeps the running CineAgent process alive across the toggle, but with it
        // PackageManager delays and coalesces the PACKAGE_CHANGED broadcast: an immediate
        // disable+enable reaches TIMS as one "still enabled" change and the stale state stays
        // (seen on the E1). Re-enabling only after REENABLE_DELAY_MS lets TIMS see both changes.
        private const val REENABLE_DELAY_MS = 5_000L

        fun rebindIfNotRunning(context: Context) {
            if (isRunning) return
            val pm = context.packageManager
            val component = ComponentName(context, AutoStartTvInputService::class.java)
            try {
                pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                Log.i(TAG, "TV input service was not bound - disabled component to force a rebind")
            } catch (e: Throwable) {
                Log.w(TAG, "Could not disable TV input component: ${e.message}")
                return
            }
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
                    Log.i(TAG, "Re-enabled TV input component")
                } catch (e: Throwable) {
                    Log.w(TAG, "Could not re-enable TV input component: ${e.message}")
                }
            }, REENABLE_DELAY_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        AccessibilityServiceGuard.ensureEnabled(this)
        NotificationListenerGuard.ensureEnabled(this)
        if (!CineAgentService.isRunning) {
            CineAgentService.start(this)
        }
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    override fun onCreateSession(inputId: String): Session? = null
}
