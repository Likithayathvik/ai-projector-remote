package com.boat.cineagent

import android.content.Context
import android.provider.Settings

// This projector's firmware resets Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES to its own
// factory default list on every boot - confirmed live: after a power-off/power-on cycle,
// `adb shell settings get secure enabled_accessibility_services` no longer contained CineAgent's
// service, only the vendor's own two (com.newlink.accessibility, com.sstar.systemcontrol's
// LowRamService). com.newlink.provision.FallbackHome runs its own provisioning routine on every
// single boot (not just first boot), which is the most likely source. A normal app has no way to
// intercept or veto another component's write to a secure setting - the only option is to detect
// the drop afterward and re-add ourselves.
//
// Requires WRITE_SECURE_SETTINGS, granted once via
// `adb shell pm grant com.boat.cineagent android.permission.WRITE_SECURE_SETTINGS`
// (install.bat's job, one time, at install). Once granted it persists across reboots by itself
// (it's a normal granted-permission record, not the accessibility list this guard is repairing),
// so every boot after that first install is self-healing with no further adb/install.bat needed.
object AccessibilityServiceGuard {
    private const val SERVICE_COMPONENT =
        "com.boat.cineagent/com.boat.cineagent.accessibility.CineAgentAccessibilityService"

    /** Re-adds this app's accessibility service to the enabled list if the OS (or, on this
     *  device, the vendor's own boot provisioning) dropped it. No-ops if already present, and
     *  silently no-ops if WRITE_SECURE_SETTINGS hasn't been granted - same as before this guard
     *  existed, falling back to needing install.bat re-run on that specific device. */
    fun ensureEnabled(context: Context) {
        val resolver = context.contentResolver
        val existing = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val services = existing?.split(":")?.filter { it.isNotBlank() }.orEmpty()
        if (SERVICE_COMPONENT in services) return

        val newValue = (services + SERVICE_COMPONENT).joinToString(":")
        try {
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newValue)
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        } catch (e: SecurityException) {
            // WRITE_SECURE_SETTINGS not granted on this device/build - nothing more we can do
            // from inside the app.
        }
    }
}
