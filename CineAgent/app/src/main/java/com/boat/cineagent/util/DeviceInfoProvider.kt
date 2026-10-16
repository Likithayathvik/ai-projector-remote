package com.boat.cineagent.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.boat.cineagent.BuildConfig
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object DeviceInfoProvider {

    // F1 and F1 Pro share firmware quirks (no working USB source, no registered handler for the
    // standard BLUETOOTH_SETTINGS action) that the rest of CommandHandler/DeviceInfoProvider needs
    // to detect ahead of time rather than discovering via a failed intent/click at the moment of
    // use. Detected via a build-time artifact rather than a Build.MODEL string match, since
    // Build.MODEL on these projectors is a generic chip/platform string, not a per-SKU marketing
    // name: both F1 and F1 Pro ship one of these two runtime-resource-overlay packages (Android's
    // per-SKU TvSettings customization mechanism) pre-installed as part of a shared firmware image
    // - confirmed present in captured logs from both units, and confirmed absent from E1 and M1
    // entirely (different vendor firmware, no such overlay exists there).
    private val F1_FAMILY_MARKER_PACKAGES = listOf(
        "com.android.tv.settings.rro.CineHead_F1_9",
        "com.android.tv.settings.rro.CineHead_F1_Pro_8"
    )

    fun isF1Family(context: Context): Boolean {
        val pm = context.packageManager
        return F1_FAMILY_MARKER_PACKAGES.any { pkg -> isPackagePresent(pm, pkg) }
    }

    // WhaleOS projectors (Whale TV, Amlogic) have their own sound modes (Standard, Music, News,
    // Movie, Game, User), which the equalizer uses there instead of the NXP engine. Detected the
    // same way as the F1 family: by the WhaleOS settings app, part of that firmware image only.
    private const val WHALEOS_SETTINGS_PACKAGE = "com.zeasn.whaleos.settings"

    fun isWhaleOs(context: Context): Boolean = isPackagePresent(context.packageManager, WHALEOS_SETTINGS_PACKAGE)

    /** GET_DEVICE_INFO's "equalizer": which equalizer the phone app should show. */
    fun equalizerType(context: Context): String = if (isWhaleOs(context)) "whaleos" else "nxp"

    // F1 and F1 Pro have no working USB source on this firmware (no video-passthrough path and
    // no file-browser fallback wired up, unlike the E1/M1 fixes already in CommandHandler) -
    // clicking USB there just fails, so the phone app hides the button instead of offering a
    // control that never works.
    // WhaleOS (Whale TV) has no USB entry in its source picker at all, so it is hidden there too.
    fun usbSourceSupported(context: Context): Boolean = !isF1Family(context) && !isWhaleOs(context)

    // F1 and F1 Pro map the remote's Menu button to GLOBAL_ACTION_RECENTS, but this firmware's
    // launcher has no recents/overview surface for it to open - the action is a silent no-op on
    // that hardware - so the phone app hides the button rather than offering a control that does
    // nothing. Same F1-family detection and same "never hide on a guess" default (shown unless
    // GET_DEVICE_INFO explicitly says otherwise) as usbSourceSupported above.
    // WhaleOS: the remote's Menu opens the current screen's own options panel (HDMI Options),
    // reachable only by a real KEYCODE_MENU, which CineAgent cannot inject - hidden there too.
    fun menuSupported(context: Context): Boolean = !isF1Family(context) && !isWhaleOs(context)

    private fun isPackagePresent(pm: PackageManager, packageName: String): Boolean = try {
        pm.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun deviceName(context: Context): String {
        val name = try {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        } catch (t: Throwable) {
            null
        }
        return name ?: model()
    }

    fun model(): String = Build.MODEL ?: "CineAgent Projector"

    fun manufacturer(): String = Build.MANUFACTURER ?: "boAt"

    fun androidVersion(): String = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString()

    fun serialNumber(context: Context): String {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?: "UNKNOWN"
        } catch (t: Throwable) {
            "UNKNOWN"
        }
    }

    fun firmwareVersion(): String = BuildConfig.FIRMWARE_VERSION

    fun agentVersion(): String = BuildConfig.VERSION_NAME

    fun protocolVersion(): Int = BuildConfig.PROTOCOL_VERSION

    fun localIpAddress(): String? {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val addresses = Collections.list(iface.inetAddresses)
                for (addr in addresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (t: Throwable) {
            null
        }
    }
}
