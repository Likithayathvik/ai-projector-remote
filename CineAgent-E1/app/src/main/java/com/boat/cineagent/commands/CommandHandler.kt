package com.boat.cineagent.commands

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import com.boat.cineagent.AccessibilityServiceGuard
import com.boat.cineagent.audio.AudioEffectsController
import com.boat.cineagent.audio.WhaleOsSoundModes
import com.boat.cineagent.accessibility.CineAgentAccessibilityService
import com.boat.cineagent.events.SystemEventMonitor
import com.boat.cineagent.media.MediaNotificationListenerService
import com.boat.cineagent.util.DeviceInfoProvider
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.roundToInt

// Implements the command protocol from spec section 5-6.
class CommandHandler(private val context: Context) {

    companion object {
        private const val TAG = "CommandHandler"
        val CAPABILITIES = listOf(
            "OPEN_APP",
            "OPEN_SEARCH",
            "GET_APPS",
            "GET_RUNNING_APP",
            "GET_DEVICE_INFO",
            "GET_KEYSTONE",
            "SET_VOLUME",
            "GET_VOLUME",
            "GET_EQUALIZER",
            "SET_EQUALIZER",
            "SET_DOLBY",
            "VOLUME_UP",
            "VOLUME_DOWN",
            "MUTE",
            "UNMUTE",
            "OPEN_WIFI",
            "OPEN_BLUETOOTH",
            "OPEN_DISPLAY",
            "OPEN_SOUND",
            "OPEN_SETTINGS",
            "PLAY",
            "PAUSE",
            "PLAY_PAUSE",
            "STOP",
            "NEXT",
            "PREVIOUS",
            "SEEK_TO",
            "GET_MEDIA_INFO",
            "GET_PLAYBACK_STATE",
            "HOME",
            "BACK",
            "MENU",
            "POWER",
            "DPAD_UP",
            "DPAD_DOWN",
            "DPAD_LEFT",
            "DPAD_RIGHT",
            "DPAD_CENTER",
            "SELECT_SOURCE",
            "SEND_TEXT",
            "PING"
        )

        // These three retry stages can all stack in a single SELECT_SOURCE call (accessibility
        // bind wait, then home-row click, then the picker fallback), and the phone app's
        // CineAgentConnection gives every command a flat 5s response timeout before reporting a
        // false "Command timed out" failure - it has no idea the projector is still legitimately
        // working. Tuned so the worst case (SELECT_SOURCE, right after a reboot) stays under
        // that budget with margin, rather than raising the phone's timeout to match the server:
        // 1_000 (bind) + 800 (home row) + 2_500 (picker) = 4_300ms worst case, vs. a 5_000ms cap.

        // handleSelectSource()'s retry tuning: wait once for the picker to render, then
        // keep re-checking for the target node until SELECT_SOURCE_RETRY_WINDOW_MS runs out.
        private const val SELECT_SOURCE_INITIAL_DELAY_MS = 700L
        private const val SELECT_SOURCE_RETRY_INTERVAL_MS = 350L
        private const val SELECT_SOURCE_RETRY_WINDOW_MS = 1_800L

        // clickSourceOnHomeScreen()'s retry tuning - deliberately short: on launchers without a
        // SOURCE INPUT row this should fail fast and fall through to the picker-based flow below,
        // not add multiple seconds of latency to every SELECT_SOURCE call on every vendor.
        private const val HOME_SOURCE_ROW_INITIAL_DELAY_MS = 300L
        private const val HOME_SOURCE_ROW_RETRY_INTERVAL_MS = 250L
        private const val HOME_SOURCE_ROW_WINDOW_MS = 500L

        // The per-app OPEN_SEARCH navigation (YouTube / Netflix ninja / Hotstar branches below)
        // runs on a background thread - handleOpenSearch() replies SUCCESS immediately - so these
        // waits are NOT bound by the phone's 5s response timeout or its heartbeat. A flat 3s
        // between every action gives each app's screen transition time to settle before the next
        // key press or tree check.
        private const val SEARCH_ACTION_WAIT_MS = 3_000L

        // SEND_TEXT: how long to wait after ACTION_SET_TEXT lands before firing the field's IME
        // action, so the app has registered the new text first. Runs on the client-connection
        // thread, well inside the phone's 5s response budget.
        private const val SEND_TEXT_SUBMIT_DELAY_MS = 250L

        // com.google.android.youtube.tv: the native app build (NOT the old Cobalt/Chromium one)
        // registers a VIEW http/https filter that jumps straight to the in-app search screen.
        // Confirmed live 2026-08-31 on a Formovie AP0034 / F1 Pro (YouTube TV 5.30.320): firing
        // this URL opens search from any state - launcher, home, mid-playback - with no D-pad
        // navigation and no accessibility service. Empty search_query = blank search page with the
        // on-screen keyboard. Preferred over the blind key path below; see handleOpenSearch().
        private const val YOUTUBE_SEARCH_DEEPLINK = "https://www.youtube.com/results?search_query="
        private const val YOUTUBE_TV_PACKAGE = "com.google.android.youtube.tv"
        // Whale TV (HOTACK SMART2KPJT) ships a rebranded Cobalt YouTube build; confirmed live
        // 2026-10-15 that it honours the same search_query= deep link (lands on results).
        private val YOUTUBE_TV_PACKAGES = setOf(YOUTUBE_TV_PACKAGE, "com.WhaleTV.whaleos.youtube.tv")

        // com.amazon.amazonvideo.livingroom (Prime Video "Ignite" build): exposes no in-app
        // a11y node tree. Firing this URL makes PV re-show its "Who's watching?" profile picker
        // with the search destination queued behind it; one DPAD_CENTER on the pre-focused
        // profile then lands on PV's own in-app search screen. Confirmed live 2026-09-01 on a
        // CineHead F1 (5-profile account) that phrase=<term> lands on that screen with <term>
        // typed AND full results shown (like YouTube's search_query=) - and that EVERY fire
        // re-shows the picker, even one sent while PV is already on its search screen, so the
        // DPAD_CENTER is always needed. phrase must be non-empty - an empty phrase drops to
        // Home - so the initial (query-less) open sends a throwaway PRIME_VIDEO_EMPTY_PHRASE
        // just to reach the search screen; the phone then supplies the real query.
        // app.primevideo.com is the only host PV registers for VIEW (www.primevideo.com does
        // not resolve). See handleOpenSearch().
        private const val PRIME_VIDEO_SEARCH_DEEPLINK_BASE = "https://app.primevideo.com/search?phrase="
        private const val PRIME_VIDEO_EMPTY_PHRASE = "a"
        private const val PRIME_VIDEO_PACKAGE = "com.amazon.amazonvideo.livingroom"

        // Netflix Android TV build (F1 family, WhaleOS). Search is refused there - see
        // handleOpenSearch().
        private const val NETFLIX_NINJA_PACKAGE = "com.netflix.ninja"

        // com.netflix.mediaclient: the mobile Netflix build shipped on E1 / M1 (vs. the Android
        // TV com.netflix.ninja on F1 / F1 Pro above). Unlike ninja it has a real a11y tree, but
        // rather than the old clickNodeByText("Search") path this fires Netflix's own
        // https://www.netflix.com/search deep link at NetflixComLaunchActivity. Confirmed live
        // 2026-09-03 on a CineHead E1: this lands directly on the in-app search screen from ANY
        // state - Netflix already foreground, or cold from another app. The activity is exported
        // and resolves the URL via its manifest VIEW filter (verified with `cmd package
        // resolve-activity`), so the explicit component is safe and avoids any chooser.
        //
        // With a query, append it as a path segment: .../search/<term> (Netflix registers
        // Path GLOB /search/.* for VIEW). Confirmed live 2026-09-10 on a CineHead E1
        // (com.netflix.mediaclient 9.72.0): .../search/stranger%20things lands straight on
        // "Top Results" for the term with the box pre-filled and no keyboard - the same
        // URL+query model as YouTube TV's search_query= and Prime Video's phrase=. NOTE: the
        // ?q=<term> web-URL param is silently ignored by this build (verified - lands on the
        // blank search screen), so it must be the path form. Query-less .../search opens the
        // blank search screen and returns acceptsQuery=true so the phone collects a query and
        // re-sends, exactly like those two.
        private const val NETFLIX_MOBILE_PACKAGE = "com.netflix.mediaclient"
        private const val NETFLIX_MOBILE_SEARCH_DEEPLINK = "https://www.netflix.com/search"
        private const val NETFLIX_MOBILE_SEARCH_ACTIVITY =
            "com.netflix.mediaclient.ui.launch.NetflixComLaunchActivity"
        // With a query the deep link lands on results, but Netflix keeps search_src_text
        // focused (query still in the box). Suppress TEXT_FIELD_FOCUSED for the render window
        // so the phone never raises Keyboard Mode for a search CineAgent already completed -
        // same idea as HOTSTAR_SEARCH_QUERY_SUPPRESS_MS. Shorter: no typing here, just render.
        private const val NETFLIX_MOBILE_SEARCH_QUERY_SUPPRESS_MS = 15_000L

        // com.hotstar (in.startv.hotstar): this build exposes a full native a11y tree AND
        // honours a deep link to its explore/search screen. Confirmed live 2026-09-01 on a
        // CineHead F1: firing this URL with an explicit component reaches the explore page
        // (which contains id/search_bar) from ANY state - home, a content-details page, or
        // mid-playback - in ~2s when Hotstar is already running (delivered to MainActivity's
        // onNewIntent), ~10s worst case from a colder state. The plain https:// form is
        // blocked as an unverified app link, so the explicit -n component is required; and
        // /in/search does NOT work (lands on home/loading) - only /in/explore. Once there,
        // clickNodeById(search_bar) focuses the field and raises the keyboard.
        //
        // This replaced the old blind path (BACK-walk home loop keyed on hasNodeWithText("Home")
        // + reveal + clickNodeByText("Search") + DPAD_RIGHT/UP*3/RIGHT/CENTER), which broke from
        // mid-playback: the revealed rail shows "Home" on every screen so the loop stopped one
        // step short of home, and the rail's "Search" tv_title has no clickable ancestor there.
        private const val HOTSTAR_EXPLORE_DEEPLINK = "https://www.hotstar.com/in/explore"
        private const val HOTSTAR_PACKAGE = "in.startv.hotstar"
        private const val HOTSTAR_MAIN_ACTIVITY = "com.hotstar.MainActivity"
        private const val HOTSTAR_SEARCH_BAR_ID = "in.startv.hotstar:id/search_bar"
        // Poll for id/search_bar to appear + become clickable after firing the deep link, rather
        // than a blind wait; the re-fire covers a first fire that landed mid-transition and was
        // dropped. The poll runs before OPEN_SEARCH replies, so the phone opens Keyboard Mode only
        // when the box was really found. Signed out, Hotstar shows its login page instead
        // (confirmed live 2026-10-29, JioHotstar 26.09.28): no search_bar, and every re-fire
        // reloaded that page and wiped the number typed into it. So the window stays inside the
        // phone's 10s command timeout (signed in, search_bar showed in 0.2-5.1s) and, when it runs
        // out, the search stops with an error - no further re-fires, no Keyboard Mode.
        private const val HOTSTAR_EXPLORE_POLL_INTERVAL_MS = 300L
        private const val HOTSTAR_EXPLORE_POLL_WINDOW_MS = 7_000L
        private const val HOTSTAR_EXPLORE_REFIRE_MS = 4_000L

        // search_bar focus-reassert tuning, added after clickNodeById() started granting real
        // ACTION_FOCUS: confirmed live 2026-09-02 via projector logcat (InputMethodManager /
        // GoogleInputMethodService onStartInput lines) that Hotstar's own UI steals input focus
        // right back off search_bar to a non-editable view (fieldId=0, actionName=UNSPECIFIED)
        // within ~300-700ms of every single successful click+focus, then stays blurred until the
        // next OPEN_SEARCH retry. The phone's TEXT_FIELD_FOCUSED poll only runs every 2s, so it
        // almost never lands inside that brief focused window - stopping at the first successful
        // click (as before) meant Keyboard Mode flashed on/off invisibly, not reliably. Re-firing
        // ACTION_FOCUS (not another ACTION_CLICK - see focusNodeById()) for this window after the
        // first success outlasts that steal with comfortable margin over the observed ~700ms.
        private const val HOTSTAR_SEARCH_BAR_REASSERT_WINDOW_MS = 1_500L
        private const val HOTSTAR_SEARCH_BAR_REASSERT_INTERVAL_MS = 150L

        // OPEN_SEARCH{query} for Hotstar (the voice/chat "search <term>" path): unlike YouTube TV
        // and Prime Video, Hotstar has no URL that carries the term - no /in/search?q=, no
        // ?search_query=, ACTION_SEARCH is ignored (all confirmed dead on JioHotstar 26.08.x).
        // So the query is typed into id/search_bar via ACTION_SET_TEXT once it's focused, exactly
        // like SEND_TEXT does; Hotstar filters results live as the field text changes, and the
        // IME action is fired too in case a build needs an explicit submit.
        private const val HOTSTAR_SEARCH_QUERY_SUBMIT_DELAY_MS = 400L
        // Suppress TEXT_FIELD_FOCUSED to the phone for this whole flow so it doesn't pop Keyboard
        // Mode over a search CineAgent has already run. Opened up front (the focused-field poll
        // is independent and 2s-cadenced, so arming it only after the field is focused would
        // race) and sized to cover the explore-page poll window + focus reassert + type/submit.
        private const val HOTSTAR_SEARCH_QUERY_SUPPRESS_MS = 25_000L
        // Re-armed from this point after the query is submitted, to ride out Hotstar's
        // post-submit focus flap before the poll is allowed to report the real state again.
        private const val HOTSTAR_SEARCH_QUERY_SUBMIT_TAIL_MS = 5_000L

        // requireAccessibilityService()'s retry tuning. Writing enabled_accessibility_services
        // correctly (AccessibilityServiceGuard) doesn't mean the OS has actually bound the
        // service yet - confirmed on a real reboot that the bind can lag well behind the setting
        // write under this device's boot-time load (by minutes, in the worst case - no amount of
        // in-command retrying can mask that; MainActivity's continuous re-assert and BootReceiver
        // are what actually close that gap over time). This is just a short nudge-and-check for
        // the tail end of that gap, kept short specifically to fit the phone's 5s budget above.
        private const val ACCESSIBILITY_BIND_RETRY_INTERVAL_MS = 500L
        private const val ACCESSIBILITY_BIND_RETRY_WINDOW_MS = 1_000L

        // Per-vendor source-picker Activity, tried in order after the standard VIEW_INPUTS
        // action fails to resolve or isn't exported. Confirmed launchable from a normal
        // (non-privileged) app - not just present in dumpsys - before adding an entry here.
        private val VENDOR_SOURCE_PICKERS = listOf(
            "com.droidlogic.tv.settings" to "com.droidlogic.tv.settings.TvSourceActivity"
        )

        // Per-vendor "Menu" screen: the Activity that vendor's physical-remote MENU key opens,
        // with the int extras that key passes (empty for none). There is no GLOBAL_ACTION_MENU
        // (every other button maps to a global action), and a non-privileged app can't inject
        // KEYCODE_MENU (signature-level INJECT_EVENTS), so MENU launches this Activity by
        // explicit component instead - tried in order, first that starts wins - and only falls
        // back to the recents action if none resolve. Each confirmed launchable from a normal
        // (non-privileged) app, not just present in dumpsys.
        //   E1 (Amlogic/Droidlogic): MorePrefFragmentActivity. from_live_tv=1 (an int, not a
        //     bool) selects the compact Picture/Sound/Settings "Menu" - exactly the screen
        //     com.android.tv's QuickKeyInfo.startDroidSettings() opens for the physical MENU
        //     key; without it the activity lands on its verbose "More Settings" root. Confirmed
        //     live 2026-09-10 (verified against a decompile of startDroidSettings()).
        //   M1 (SigmaStar): com.sstar.customizedmenu, no extras.
        private val VENDOR_MENU_SCREENS: List<Triple<String, String, Map<String, Int>>> = listOf(
            Triple("com.droidlogic.tv.settings", "com.droidlogic.tv.settings.more.MorePrefFragmentActivity",
                mapOf("from_live_tv" to 1)),
            Triple("com.sstar.customizedmenu", "com.sstar.customizedmenu.MainActivity", emptyMap())
        )

        // Some vendors' source pickers have no "USB" row at all - on this vendor (SigmaStar,
        // confirmed on a CineHead M1 unit) com.android.tv.SelectInputActivity opens fine via
        // VIEW_INPUTS but only ever lists TV/HDMI/HDMI (physical HDMI-CEC/tuner inputs), so
        // clickNodeByText("USB") correctly finds nothing there. On this firmware "USB source"
        // instead means opening the file manager app directly (confirmed present and handling
        // USB media: com.newlink.filemanager, which registers a USBDialogReceiver) - same
        // resolution as the CineHead E1 case, which opens com.stellamore.filemanager instead.
        // Tried only when source == "USB" and no on-screen node was found; add a new package
        // here after confirming (a) the vendor's picker genuinely has no USB entry and (b) the
        // file manager package is present and launchable from a normal (non-privileged) app.
        private val VENDOR_USB_FILE_MANAGERS = listOf(
            "com.newlink.filemanager"
        )

        // F1 and F1 Pro (Formovie AP0039, Android 14) don't register a handler for the standard
        // BLUETOOTH_SETTINGS action at all (confirmed: result code -91, "no activity found"). On
        // that firmware the only standalone-launchable Bluetooth screen is the accessory pairer
        // itself; the calmer "Remotes & Accessories" list (paired devices + a "Pair accessory"
        // row) is a Fragment living inside com.android.tv.settings's own internal navigation and
        // isn't reachable via any external Intent, even with WRITE_SECURE_SETTINGS (tried explicit
        // Slice URIs and Settings deep-link extras - both dead ends). This activity starts
        // Bluetooth discovery immediately on open (that's this screen's actual designed behavior
        // on that firmware, not something we're triggering). Routed to directly via
        // DeviceInfoProvider.isF1Family() rather than waiting for the implicit intent to fail
        // first, since that failure is already known and deterministic for this device family.
        private const val F1_FAMILY_BLUETOOTH_PAIRER_PKG = "com.android.tv.settings"
        private const val F1_FAMILY_BLUETOOTH_PAIRER_CLASS =
            "com.android.tv.settings.accessories.AddAccessoryActivity"

        // GET_KEYSTONE source #1: persist.vendor.hwc.keystone via SystemProperties. Works on
        // E1-class firmware (com.droidlogic.keystone owns it there) - an 8-value CSV of ABSOLUTE
        // corner coords, TL,TR,BR,BL. Returned as-is. This is the primary path.
        private const val KEYSTONE_PROPERTY = "persist.vendor.hwc.keystone"

        // GET_KEYSTONE source #2 (fallback): the MediaTek trapezoid HAL's logcat tag. Needed on
        // F1-class firmware (Formovie AP0034 / yelagiri, Android 14) where KEYSTONE_PROPERTY is
        // SELinux-blocked for every app context (avc denied { find } persist.vendor.hwc.keystone),
        // CineAgent included. The HAL logs its full parameter block under this tag on every
        // change; handleGetKeystone() parses the most recent line. Per-corner PIXEL OFFSETS.
        // NOTE: reading another process's logcat only succeeds when CineAgent is a privileged
        // system app - a sideloaded (untrusted_app) build gets back nothing here.
        private const val TRAPEZOID_LOG_TAG = "TrapezoidHal"
        private const val TRAPEZOID_LOG_LINES = 500
        private const val LOGCAT_TIMEOUT_MS = 2_000L
        // TrapezoidParams{ ... LeftTop: Point{x: 297, y: 138}, ... RightBot: Point{x: 222, y: 414}, ... }
        private val TRAPEZOID_PARAMS_MARKER = "TrapezoidParams{"
        private val TRAPEZOID_POINT_NAMES = listOf(
            "leftTop", "midTop", "rightTop", "leftMid",
            "rightMid", "leftBot", "midBot", "rightBot",
        )
    }

    private val audioEffects = AudioEffectsController(context) { foregroundAppInfo()?.first }
    // WhaleOS projectors: the projector's own sound modes replace the NXP equalizer.
    private val whaleOsSound = if (DeviceInfoProvider.isWhaleOs(context)) WhaleOsSoundModes(context) else null

    fun handle(request: JSONObject): JSONObject {
        val id: Any? = if (request.has("id")) request.get("id") else null
        val cmd = request.optString("cmd", "")

        if (cmd.isEmpty()) {
            return errorResponse(id, ErrorCode.MISSING_PARAMETER, "Missing parameter: cmd")
        }

        return try {
            when (cmd) {
                "PING" -> handlePing(id)
                "OPEN_APP" -> handleOpenApp(id, request)
                "OPEN_SEARCH" -> handleOpenSearch(id, request)
                "GET_APPS" -> handleGetApps(id)
                "GET_RUNNING_APP" -> handleGetRunningApp(id)
                "GET_DEVICE_INFO" -> handleGetDeviceInfo(id)
                "GET_KEYSTONE" -> handleGetKeystone(id)
                "SET_VOLUME" -> handleSetVolume(id, request)
                "GET_VOLUME" -> handleGetVolume(id)
                "GET_EQUALIZER" -> audioResponse(id) { whaleOsSound?.get() ?: audioEffects.get() }
                "SET_EQUALIZER" -> handleSetEqualizer(id, request)
                "SET_DOLBY" -> handleSetDolby(id, request)
                "VOLUME_UP" -> handleVolumeAdjust(id, AudioManager.ADJUST_RAISE)
                "VOLUME_DOWN" -> handleVolumeAdjust(id, AudioManager.ADJUST_LOWER)
                "MUTE" -> handleVolumeAdjust(id, AudioManager.ADJUST_MUTE)
                "UNMUTE" -> handleVolumeAdjust(id, AudioManager.ADJUST_UNMUTE)
                "OPEN_WIFI" -> handleOpenSettings(id, Settings.ACTION_WIFI_SETTINGS)
                "OPEN_BLUETOOTH" -> handleOpenBluetooth(id)
                "OPEN_DISPLAY" -> handleOpenSettings(id, Settings.ACTION_DISPLAY_SETTINGS)
                "OPEN_SOUND" -> handleOpenSettings(id, Settings.ACTION_SOUND_SETTINGS)
                "OPEN_SETTINGS" -> handleOpenSettings(id, Settings.ACTION_SETTINGS)
                "PLAY" -> handleTransportControl(id) { it.play() }
                "PAUSE" -> handleTransportControl(id) { it.pause() }
                "PLAY_PAUSE" -> handlePlayPause(id)
                "STOP" -> handleTransportControl(id) { it.stop() }
                "NEXT" -> handleTransportControl(id) { it.skipToNext() }
                "PREVIOUS" -> handleTransportControl(id) { it.skipToPrevious() }
                "SEEK_TO" -> handleSeekTo(id, request)
                "GET_MEDIA_INFO" -> handleGetMediaInfo(id)
                "GET_PLAYBACK_STATE" -> handleGetPlaybackState(id)
                "HOME" -> handleAccessibilityAction(id) { it.goHome() }
                "BACK" -> handleAccessibilityAction(id) { it.goBack() }
                "MENU" -> handleMenu(id)
                "POWER" -> handlePower(id)
                "DPAD_UP" -> handleAccessibilityAction(id) { it.dpadUp() }
                "DPAD_DOWN" -> handleAccessibilityAction(id) { it.dpadDown() }
                "DPAD_LEFT" -> handleAccessibilityAction(id) { it.dpadLeft() }
                "DPAD_RIGHT" -> handleAccessibilityAction(id) { it.dpadRight() }
                "DPAD_CENTER" -> handleAccessibilityAction(id) { it.dpadCenter() }
                "SELECT_SOURCE" -> handleSelectSource(id, request)
                "SEND_TEXT" -> handleSendText(id, request)
                else -> errorResponse(id, ErrorCode.UNKNOWN_COMMAND, ErrorCode.messageFor(ErrorCode.UNKNOWN_COMMAND))
            }
        } catch (t: Throwable) {
            errorResponse(id, ErrorCode.COMMAND_FAILED, t.message ?: ErrorCode.messageFor(ErrorCode.COMMAND_FAILED))
        }
    }

    private fun handlePing(id: Any?): JSONObject {
        val response = JSONObject()
        if (id != null) response.put("id", id)
        response.put("status", "OK")
        response.put("cmd", "PONG")
        return response
    }

    private fun handleOpenApp(id: Any?, request: JSONObject): JSONObject {
        val pkg = request.optString("package", "")
        if (pkg.isEmpty()) {
            return errorResponse(id, ErrorCode.MISSING_PARAMETER, "Missing parameter: package")
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: context.packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: return errorResponse(id, ErrorCode.APP_NOT_INSTALLED, ErrorCode.messageFor(ErrorCode.APP_NOT_INSTALLED))

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return successResponse(id)
    }

    private fun handleGetApps(id: Any?): JSONObject {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val leanbackIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val resolveInfos = context.packageManager.queryIntentActivities(launcherIntent, 0) +
            context.packageManager.queryIntentActivities(leanbackIntent, 0)

        val apps = JSONArray()
        val seenPackages = mutableSetOf<String>()
        for (info in resolveInfos) {
            if (!seenPackages.add(info.activityInfo.packageName)) continue
            val appEntry = JSONObject()
            appEntry.put("name", info.loadLabel(context.packageManager).toString())
            appEntry.put("package", info.activityInfo.packageName)
            apps.put(appEntry)
        }

        val response = JSONObject()
        if (id != null) response.put("id", id)
        response.put("status", "SUCCESS")
        response.put("apps", apps)
        return response
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return when (mode) {
            // Explicitly allowed (Settings toggle, or `appops set GET_USAGE_STATS allow`).
            AppOpsManager.MODE_ALLOWED -> true
            // No explicit appop set. Android's UsageStatsService then falls back to the
            // PACKAGE_USAGE_STATS permission, which the platform-signed build holds (granted by
            // the signature). Under this fallback queryEvents()/queryUsageStats() return data
            // normally, so treating MODE_DEFAULT + granted permission as "has access" is what
            // lets GET_RUNNING_APP work without the installer's `appops set ... allow` step.
            AppOpsManager.MODE_DEFAULT ->
                context.checkPermission(
                    android.Manifest.permission.PACKAGE_USAGE_STATS,
                    Process.myPid(),
                    Process.myUid()
                ) == PackageManager.PERMISSION_GRANTED
            else -> false
        }
    }

    /** Most-recent MOVE_TO_FOREGROUND event in the last 10 minutes of usage-stats history -
     *  same lookup [handleGetRunningApp] and [handleOpenSearch] both need, so it lives here once. */
    private fun currentForegroundPackage(): String? = foregroundAppInfo()?.first

    // queryEvents() is the primary source because it's the only one that also gives us the
    // foreground Activity's class name (event.className, API 28+) for GET_RUNNING_APP. But it
    // reads from UsageStatsService's daily-bucket-file log, which does NOT roll over backwards:
    // confirmed live 2026-08-18 that a projector whose clock had briefly jumped forward to
    // 2027-08-22 (then got corrected back) was left with its only daily/weekly/monthly bucket
    // file permanently stamped 2027-08-22 (dumpsys usagestats -> Database Summary). Querying the
    // real, current 2026 time range against that log silently returns zero events forever, even
    // seconds after switching apps - there is no time-based recovery short of a device reboot.
    //
    // queryUsageStats() was tried as a fallback first since its per-package aggregates looked
    // correct in `dumpsys usagestats` output - but confirmed live that the public API returns
    // something different from what dumpsys renders: on this same corrupted device it came back
    // with ~95 packages all reporting lastTimeUsed=0 except the launcher, which reported a
    // bogus value that decoded to exactly the corrupted yearly bucket's timestamp + 24h (a
    // rollover-boundary artifact, not real usage data) - so it's tied to the same broken bucket
    // state after all and reliably picks the launcher over whatever app is actually foreground.
    //
    // rootInActiveWindow, via the accessibility service CineAgent already runs for the HDMI/USB
    // picker and D-pad injection, sidesteps UsageStatsManager entirely - it reflects the OS's
    // actual current window in real time, so it isn't affected by this corruption at all.
    // Accessibility is checked FIRST, not as a fallback: confirmed live 2026-09-02 that
    // queryEvents()'s last MOVE_TO_FOREGROUND can go stale for far longer than the corrupted-
    // bucket case above covers. This projector's screensaver (com.google.android.apps.tv.dreamx)
    // overlays the resumed Activity instead of pausing it, so dismissing it back into Hotstar
    // triggers no fresh MOVE_TO_FOREGROUND for Hotstar at all - queryEvents() kept returning the
    // screensaver's own event as "last" for the full 10-minute window, so OPEN_SEARCH/
    // GET_RUNNING_APP misreported dreamx as foreground the entire time the user was actually back
    // in Hotstar (only self-corrected once that stale event finally aged out of the window).
    // rootInActiveWindow has no such staleness window - it reflects the OS's actual current
    // window right now - so it's authoritative whenever the service is bound. queryEvents() stays
    // as the fallback purely because it's the only source for GET_RUNNING_APP's activity class
    // name (event.className, API 28+), which accessibility can't provide.
    private fun foregroundAppInfo(): Pair<String, String?>? {
        CineAgentAccessibilityService.instance?.currentForegroundPackageName()?.let { return it to null }

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val start = end - 10 * 60 * 1000

        val events = usageStatsManager.queryEvents(start, end)
        var foregroundPackage: String? = null
        var foregroundClass: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                foregroundPackage = event.packageName
                foregroundClass = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) event.className else null
            }
        }
        return foregroundPackage?.let { it to foregroundClass }
    }

    private fun handleGetRunningApp(id: Any?): JSONObject {
        if (!hasUsageAccess()) {
            return errorResponse(id, ErrorCode.PERMISSION_DENIED, ErrorCode.messageFor(ErrorCode.PERMISSION_DENIED))
        }

        val (foregroundPackage, foregroundClass) = foregroundAppInfo()
            ?: return errorResponse(id, ErrorCode.COMMAND_FAILED, "No foreground application detected")

        val appName = try {
            val appInfo = context.packageManager.getApplicationInfo(foregroundPackage, 0)
            context.packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            foregroundPackage
        }

        val response = JSONObject()
        if (id != null) response.put("id", id)
        response.put("status", "SUCCESS")
        response.put("package", foregroundPackage)
        response.put("appName", appName)
        response.put("activity", foregroundClass ?: "")
        return response
    }

    /** Runs [block] on a daemon thread and returns SUCCESS to the caller immediately. The per-app
     *  search navigation takes far longer than the phone's 5s response timeout (and would trip
     *  its heartbeat), so it must not block the reply - the phone treats OPEN_SEARCH as
     *  fire-and-forget and watches the projector navigate. */
    private fun runSearchNavAsync(id: Any?, block: () -> Unit): JSONObject {
        thread(name = "cineagent-open-search", isDaemon = true) {
            try {
                block()
            } catch (t: Throwable) {
                Log.w(TAG, "OPEN_SEARCH navigation failed", t)
            }
        }
        return successResponse(id)
    }

    /**
     * Polls for Hotstar's id/search_bar and clicks it (which focuses it) once it shows up,
     * re-firing [exploreIntent] every HOTSTAR_EXPLORE_REFIRE_MS meanwhile: a single deep-link
     * fire is silently dropped if Hotstar happens to be mid-transition when it arrives (confirmed
     * live: first fire ignored, re-fire 4s later worked). False when HOTSTAR_EXPLORE_POLL_WINDOW_MS
     * runs out first - e.g. Hotstar is signed out and showing its login page.
     */
    private fun clickHotstarSearchBar(service: CineAgentAccessibilityService, exploreIntent: Intent): Boolean {
        val deadline = System.currentTimeMillis() + HOTSTAR_EXPLORE_POLL_WINDOW_MS
        var lastFire = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            if (service.clickNodeById(HOTSTAR_SEARCH_BAR_ID)) return true
            if (System.currentTimeMillis() - lastFire >= HOTSTAR_EXPLORE_REFIRE_MS) {
                tryStartActivity(exploreIntent)
                lastFire = System.currentTimeMillis()
            }
            Thread.sleep(HOTSTAR_EXPLORE_POLL_INTERVAL_MS)
        }
        return false
    }

    private fun handleOpenSearch(id: Any?, request: JSONObject): JSONObject {
        if (!hasUsageAccess()) {
            return errorResponse(id, ErrorCode.PERMISSION_DENIED, ErrorCode.messageFor(ErrorCode.PERMISSION_DENIED))
        }
        val foregroundPackage = currentForegroundPackage()
            ?: return errorResponse(id, ErrorCode.COMMAND_FAILED, "No foreground application detected")

        // Optional: an already-typed query. Empty (the default) just opens the app's search
        // screen the way OPEN_SEARCH always has. Only the YouTube TV branch below does anything
        // with a non-empty value - it appends it to the search_query= deep link so the app
        // lands straight on results (confirmed live 2026-09-01 on a CineHead F1 / YouTube TV
        // 5.30.320). Every other app still gets the plain open-the-search-screen behaviour.
        val query = request.optString("query", "").trim()

        if (foregroundPackage in YOUTUBE_TV_PACKAGES) {
            // Preferred path: the native YouTube TV build handles this deep link and lands
            // directly on the search screen from any state (confirmed live - see
            // YOUTUBE_SEARCH_DEEPLINK). No accessibility service, not position-dependent.
            // Uri.encode("") is "", so an empty query leaves the URL exactly as before.
            val searchIntent = Intent(Intent.ACTION_VIEW, Uri.parse(YOUTUBE_SEARCH_DEEPLINK + Uri.encode(query)))
                .setPackage(foregroundPackage)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tryStartActivity(searchIntent)) {
                // Tell the phone whether it should now collect a query and re-send OPEN_SEARCH
                // with it: true only when we just opened the empty search screen. YouTube's
                // on-screen keyboard exposes no focusable field the projector can report
                // (TEXT_FIELD_FOCUSED never fires here), so the phone drives text entry itself
                // and this flag is how it knows to. A non-empty query means we already
                // navigated to results - nothing left for the phone to do.
                val response = successResponse(id)
                response.put("package", foregroundPackage)
                response.put("acceptsQuery", query.isEmpty())
                return response
            }

            // Fallback for older firmwares still shipping the Cobalt/Chromium YouTube build:
            // its screen exposes zero accessible nodes, so clickNodeByText() can't work, but a
            // blind DPAD_LEFT then DPAD_UP from the YouTube home screen opens search. Only runs
            // if the deep link intent didn't resolve at all - the Cobalt build ignoring the URL
            // (landing on Home) can't be auto-detected, so that case is a known gap.
            val service = requireAccessibilityService()
                ?: return errorResponse(id, ErrorCode.PERMISSION_DENIED, "Accessibility service not enabled")
            return runSearchNavAsync(id) {
                service.dpadLeft()
                Thread.sleep(SEARCH_ACTION_WAIT_MS)
                service.dpadUp()
                Thread.sleep(SEARCH_ACTION_WAIT_MS)
            }
        }

        // Prime Video "Ignite" build (see PRIME_VIDEO_SEARCH_DEEPLINK_BASE): fire the phrase=
        // deep link, then one DPAD_CENTER to clear the "Who's watching?" profile gate the deep
        // link re-shows, at which point the queued nav lands on PV's in-app search screen -
        // with results for the phrase when one was supplied. Same phone-side flow as YouTube:
        // an empty query just opens the search screen (acceptsQuery=true tells the phone to
        // collect a query and re-send), a non-empty one navigates straight to its results.
        if (foregroundPackage == PRIME_VIDEO_PACKAGE) {
            val service = requireAccessibilityService()
                ?: return errorResponse(id, ErrorCode.PERMISSION_DENIED, "Accessibility service not enabled")
            val phrase = if (query.isEmpty()) PRIME_VIDEO_EMPTY_PHRASE else query
            val searchIntent = Intent(Intent.ACTION_VIEW, Uri.parse(PRIME_VIDEO_SEARCH_DEEPLINK_BASE + Uri.encode(phrase)))
                .setPackage(PRIME_VIDEO_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!tryStartActivity(searchIntent)) {
                return errorResponse(id, ErrorCode.COMMAND_FAILED, "Could not launch Prime Video search")
            }
            // Reply now; the profile-gate clear (~2 x SEARCH_ACTION_WAIT_MS) runs past the
            // phone's 5s response window so it has to be fire-and-forget, same as the other
            // per-app search nav.
            val response = successResponse(id)
            response.put("package", PRIME_VIDEO_PACKAGE)
            response.put("acceptsQuery", query.isEmpty())
            thread(name = "cineagent-open-search", isDaemon = true) {
                try {
                    Thread.sleep(SEARCH_ACTION_WAIT_MS)
                    service.dpadCenter()
                    Thread.sleep(SEARCH_ACTION_WAIT_MS)
                } catch (t: Throwable) {
                    Log.w(TAG, "Prime Video search nav failed", t)
                }
            }
            return response
        }

        // Netflix Android TV build (com.netflix.ninja - F1 / F1 Pro and WhaleOS, vs. the mobile
        // com.netflix.mediaclient on E1 handled below). Its whole UI is one opaque GL surface
        // (com.netflix.ninja:id/gibbon, no accessible nodes on any screen), so no typed query can
        // reach it - SEND_TEXT finds no field, and its search deep link ignores the term
        // (confirmed live 2026-10-15/16). Refused with a message the phone shows, instead of
        // opening an empty search screen and a Keyboard Mode that cannot work.
        if (foregroundPackage == NETFLIX_NINJA_PACKAGE) {
            return errorResponse(id, ErrorCode.UNSUPPORTED_COMMAND, "Search is not supported for Netflix Ninja build")
        }

        // Netflix mobile build (com.netflix.mediaclient - E1 / M1). URL+query model, same as
        // YouTube TV and Prime Video above: a non-empty query is appended as a .../search/<term>
        // path segment and Netflix lands straight on results for it; an empty query opens the
        // blank search screen and acceptsQuery=true tells the phone to collect a query and
        // re-send OPEN_SEARCH.
        if (foregroundPackage == NETFLIX_MOBILE_PACKAGE) {
            val hasQuery = query.isNotEmpty()
            // Voice/chat "search <term> in netflix": CineAgent lands Netflix on the results
            // itself, so keep the phone's Keyboard Mode suppressed throughout (same as Hotstar)
            // - the search field stays focused with <term> in it and would otherwise pop
            // Keyboard Mode on the phone for a search that's already done.
            if (hasQuery) SystemEventMonitor.suppressFocusedTextField(NETFLIX_MOBILE_SEARCH_QUERY_SUPPRESS_MS)
            val url = if (hasQuery) {
                NETFLIX_MOBILE_SEARCH_DEEPLINK + "/" + Uri.encode(query)
            } else {
                NETFLIX_MOBILE_SEARCH_DEEPLINK
            }
            val searchIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .setClassName(NETFLIX_MOBILE_PACKAGE, NETFLIX_MOBILE_SEARCH_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return if (tryStartActivity(searchIntent)) {
                val response = successResponse(id)
                response.put("package", NETFLIX_MOBILE_PACKAGE)
                response.put("acceptsQuery", query.isEmpty())
                response
            } else {
                errorResponse(id, ErrorCode.COMMAND_FAILED, "Could not open Netflix search")
            }
        }

        if (foregroundPackage == HOTSTAR_PACKAGE) {
            val service = requireAccessibilityService()
                ?: return errorResponse(id, ErrorCode.PERMISSION_DENIED, "Accessibility service not enabled")
            // A non-empty query only ever comes from the voice/chat "search <term>" path (the
            // Search *button* always sends OPEN_SEARCH with no query). For that case CineAgent
            // types the term into Hotstar's search bar itself and keeps the phone's Keyboard
            // Mode suppressed throughout, so the flow ends on Hotstar's results for <term>
            // rather than bouncing the user to the manual Keyboard Mode page. Empty query =
            // unchanged: open the field, let TEXT_FIELD_FOCUSED raise Keyboard Mode as before.
            val hasQuery = query.isNotEmpty()
            if (hasQuery) SystemEventMonitor.suppressFocusedTextField(HOTSTAR_SEARCH_QUERY_SUPPRESS_MS)
            // Deep link straight to Hotstar's explore/search screen - works from any state
            // (see HOTSTAR_EXPLORE_DEEPLINK). Explicit component: the plain https:// form is
            // blocked as an unverified app link.
            val exploreIntent = Intent(Intent.ACTION_VIEW, Uri.parse(HOTSTAR_EXPLORE_DEEPLINK))
                .setClassName(HOTSTAR_PACKAGE, HOTSTAR_MAIN_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!tryStartActivity(exploreIntent)) {
                return errorResponse(id, ErrorCode.COMMAND_FAILED, "Could not open Hotstar search")
            }
            // Poll for the search field and click it by resource-id to focus it and raise the
            // on-screen keyboard - before replying (see HOTSTAR_EXPLORE_POLL_WINDOW_MS). id/search_bar
            // is a real clickable EditText and only enters the tree once the explore page has
            // rendered; no search_bar means no search here, so the phone must not open Keyboard Mode.
            if (!clickHotstarSearchBar(service, exploreIntent)) {
                Log.i(TAG, "OPEN_SEARCH: Hotstar search box not found (signed out?) - not opening Keyboard Mode")
                return errorResponse(id, ErrorCode.COMMAND_FAILED, "Hotstar search isn't available. Sign in to Hotstar first.")
            }
            return runSearchNavAsync(id) {
                // Won focus once - Hotstar steals it back within ~700ms (see
                // HOTSTAR_SEARCH_BAR_REASSERT_WINDOW_MS), so keep re-requesting it
                // (focus only, not another click) until we've outlasted that.
                val reassertDeadline = System.currentTimeMillis() + HOTSTAR_SEARCH_BAR_REASSERT_WINDOW_MS
                while (System.currentTimeMillis() < reassertDeadline) {
                    Thread.sleep(HOTSTAR_SEARCH_BAR_REASSERT_INTERVAL_MS)
                    service.focusNodeById(HOTSTAR_SEARCH_BAR_ID)
                }
                if (hasQuery) {
                    // Type the term straight into the focused field (same ACTION_SET_TEXT
                    // as SEND_TEXT); Hotstar filters results live as it changes. Fire the
                    // IME action too so a build that needs an explicit submit still runs.
                    service.setFocusedFieldText(query)
                    Thread.sleep(HOTSTAR_SEARCH_QUERY_SUBMIT_DELAY_MS)
                    service.submitFocusedField()
                    // Hotstar keeps the search bar focused after typing (results update
                    // live, it never blurs on its own), so once the suppression window
                    // lapsed the phone would still get a TEXT_FIELD_FOCUSED and pop
                    // Keyboard Mode ~25s late. Move focus down into the results grid: the
                    // field genuinely unfocuses (currentFocusedTextField() -> null, no
                    // Keyboard Mode ever) and the user lands on the results ready to
                    // navigate. Short tail suppression just rides out that transition.
                    Thread.sleep(HOTSTAR_SEARCH_QUERY_SUBMIT_DELAY_MS)
                    service.dpadDown()
                    SystemEventMonitor.suppressFocusedTextField(HOTSTAR_SEARCH_QUERY_SUBMIT_TAIL_MS)
                }
            }
        }

        return errorResponse(id, ErrorCode.UNSUPPORTED_COMMAND, "Search is not supported for $foregroundPackage")
    }

    // Keyboard Mode Screen (SEND_TEXT): confirm-to-send only, not per-keystroke - writes the
    // whole string into whatever field the accessibility service's findFocus(FOCUS_INPUT) still
    // reports as focused right now via ACTION_SET_TEXT. If the user backed out of the field on
    // the projector between the phone opening Keyboard Mode and pressing Send, this correctly
    // fails with COMMAND_FAILED rather than writing into the wrong place.
    //
    // After the text lands, wait briefly for the app to register it, then fire the field's IME
    // action (the on-screen keyboard's Search / Go key) via submitFocusedField() so the search
    // actually runs on the projector by itself - the user doesn't have to reach for the remote.
    // Best-effort: apps that update results live while typing (e.g. Hotstar) or fields with no
    // IME action just no-op here, which is not a failure.
    private fun handleSendText(id: Any?, request: JSONObject): JSONObject {
        val text = request.optString("text", "")
        if (text.isEmpty()) {
            return errorResponse(id, ErrorCode.MISSING_PARAMETER, "Missing parameter: text")
        }
        val service = requireAccessibilityService()
            ?: return errorResponse(id, ErrorCode.PERMISSION_DENIED, "Accessibility service not enabled")
        return if (service.setFocusedFieldText(text)) {
            Thread.sleep(SEND_TEXT_SUBMIT_DELAY_MS)
            service.submitFocusedField()
            successResponse(id)
        } else {
            errorResponse(id, ErrorCode.COMMAND_FAILED, "No editable field is focused")
        }
    }

    private fun handleGetDeviceInfo(id: Any?): JSONObject {
        val usbSupported = DeviceInfoProvider.usbSourceSupported(context)
        Log.d(TAG, "GET_DEVICE_INFO (id=$id): model=${DeviceInfoProvider.model()} usbSupported=$usbSupported")
        val response = JSONObject()
        if (id != null) response.put("id", id)
        response.put("status", "SUCCESS")
        response.put("manufacturer", DeviceInfoProvider.manufacturer())
        response.put("model", DeviceInfoProvider.model())
        response.put("androidVersion", DeviceInfoProvider.androidVersion())
        response.put("firmware", DeviceInfoProvider.firmwareVersion())
        response.put("agentVersion", DeviceInfoProvider.agentVersion())
        response.put("usbSupported", usbSupported)
        response.put("menuSupported", DeviceInfoProvider.menuSupported(context))
        response.put("equalizer", DeviceInfoProvider.equalizerType(context))
        return response
    }

    // Live keystone-quad readout for the phone's Auto Keystone Correction panel. Two sources,
    // tried in order:
    //  1. persist.vendor.hwc.keystone via SystemProperties - the primary path. Works on E1-class
    //     firmware; an 8-value CSV of ABSOLUTE corner coords, returned as-is in "keystone",
    //     source "property".
    //  2. If that's blank/blocked (F1-class), the MediaTek trapezoid HAL's logcat tag - parsed
    //     into a structured "trapezoid" object of per-corner PIXEL OFFSETS plus a legacy 8-int
    //     "keystone" CSV (LT,RT,RB,LB), source "logcat".
    // Read-only. Errors 1006 only if neither source yields anything.
    private fun handleGetKeystone(id: Any?): JSONObject {
        val property = getSystemProperty(KEYSTONE_PROPERTY)
        if (!property.isNullOrBlank()) {
            val response = successResponse(id)
            response.put("keystone", property)
            response.put("source", "property")
            return response
        }

        val trapezoid = readTrapezoidFromLogcat()
        if (trapezoid != null) {
            val response = successResponse(id)
            response.put("keystone", trapezoidCsv(trapezoid))
            response.put("trapezoid", trapezoid)
            response.put("source", "logcat")
            return response
        }

        return errorResponse(
            id,
            ErrorCode.COMMAND_FAILED,
            "Could not read keystone property or $TRAPEZOID_LOG_TAG log",
        )
    }

    // Runs `logcat -d -b all -t N -s TrapezoidHal:D`, takes the last line containing
    // `TrapezoidParams{`, and parses it. Returns null if logcat can't be read (no READ_LOGS on
    // this build), the ring buffer has no such line yet, or the line doesn't match the shape.
    private fun readTrapezoidFromLogcat(): JSONObject? {
        val dump = try {
            val process = Runtime.getRuntime().exec(
                arrayOf(
                    "logcat", "-d", "-b", "all",
                    "-t", TRAPEZOID_LOG_LINES.toString(),
                    "-s", "$TRAPEZOID_LOG_TAG:D",
                ),
            )
            val text = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(LOGCAT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroy()
            }
            text
        } catch (t: Throwable) {
            Log.w(TAG, "logcat read for $TRAPEZOID_LOG_TAG failed", t)
            return null
        }

        val line = dump.lineSequence()
            .filter { it.contains(TRAPEZOID_PARAMS_MARKER) }
            .lastOrNull()
            ?: return null
        return parseTrapezoidParams(line)
    }

    // TrapezoidParams{LeftTop: Point{x: 297, y: 138}, MidTop: Point{x: 0, y: 0}, ...,
    //   fDegreeHorizontal: 1.000000, fDegreeVertical: 1.000000, fDegreeVideoRotation: 0.000000,
    //   fZoomRatio: 1.000000, eCorrectType: TRAPEZOID_CORRECT_TYPE_QC_OFFSET, ...}
    private fun parseTrapezoidParams(line: String): JSONObject? {
        fun point(halName: String): JSONObject? {
            val m = Regex("""$halName:\s*Point\{x:\s*(-?\d+),\s*y:\s*(-?\d+)\}""").find(line)
                ?: return null
            return JSONObject()
                .put("x", m.groupValues[1].toInt())
                .put("y", m.groupValues[2].toInt())
        }
        fun float(name: String): Double? =
            Regex("""$name:\s*(-?[\d.]+)""").find(line)?.groupValues?.get(1)?.toDoubleOrNull()

        val halNames = listOf(
            "LeftTop", "MidTop", "RightTop", "LeftMid",
            "RightMid", "LeftBot", "MidBot", "RightBot",
        )
        val out = JSONObject()
        halNames.forEachIndexed { i, halName ->
            val p = point(halName) ?: return null
            out.put(TRAPEZOID_POINT_NAMES[i], p)
        }
        float("fDegreeHorizontal")?.let { out.put("degreeHorizontal", it) }
        float("fDegreeVertical")?.let { out.put("degreeVertical", it) }
        float("fDegreeVideoRotation")?.let { out.put("videoRotation", it) }
        float("fZoomRatio")?.let { out.put("zoomRatio", it) }
        Regex("""eCorrectType:\s*(\w+)""").find(line)?.groupValues?.get(1)?.let {
            out.put("correctType", it.removePrefix("TRAPEZOID_CORRECT_TYPE_"))
        }
        return out
    }

    // Legacy flat CSV for older phone builds: the four active corners, LT,RT,RB,LB, x then y.
    private fun trapezoidCsv(trapezoid: JSONObject): String =
        listOf("leftTop", "rightTop", "rightBot", "leftBot").joinToString(",") { name ->
            val p = trapezoid.optJSONObject(name)
            "${p?.optInt("x") ?: 0},${p?.optInt("y") ?: 0}"
        }

    // persist.* reads go through the hidden android.os.SystemProperties (no Context method for
    // it). Returns null on any reflection failure or when the property is unreadable/unset.
    private fun getSystemProperty(key: String): String? = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val method = clazz.getMethod("get", String::class.java)
        method.invoke(null, key) as? String
    } catch (t: Throwable) {
        Log.w(TAG, "getSystemProperty($key) failed", t)
        null
    }

    private fun handleSetVolume(id: Any?, request: JSONObject): JSONObject {
        if (!request.has("value")) {
            return errorResponse(id, ErrorCode.MISSING_PARAMETER, "Missing parameter: value")
        }
        val percent = request.optInt("value", -1).coerceIn(0, 100)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = ((percent / 100.0) * max).roundToInt().coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        return successResponse(id)
    }

    private fun handleGetVolume(id: Any?): JSONObject {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val percent = if (max > 0) ((current.toDouble() / max) * 100).roundToInt() else 0

        val response = JSONObject()
        if (id != null) response.put("id", id)
        response.put("status", "SUCCESS")
        response.put("current", percent)
        response.put("maximum", 100)
        return response
    }

    // SET_EQUALIZER {mode, gains?}: "default", a preset name or "custom" (gains: whole dB per band).
    // On WhaleOS {mode, levels?}: a sound mode id, or "user" with levels (0..100 per band).
    private fun handleSetEqualizer(id: Any?, request: JSONObject): JSONObject {
        val mode = request.optString("mode", "")
        if (mode.isEmpty()) {
            return errorResponse(id, ErrorCode.MISSING_PARAMETER, "Missing parameter: mode")
        }
        whaleOsSound?.let { whale ->
            val levels = request.optJSONArray("levels")?.let { l -> (0 until l.length()).map { l.optInt(it) } }
            return audioResponse(id) { whale.set(mode, levels) }
        }
        val gains = request.optJSONArray("gains")?.let { g -> (0 until g.length()).map { g.optInt(it) } }
        return audioResponse(id) { audioEffects.setEqualizer(mode, gains) }
    }

    // SET_DOLBY {enabled}: the projector's own Dolby Audio Processing switch.
    private fun handleSetDolby(id: Any?, request: JSONObject): JSONObject {
        if (!request.has("enabled")) {
            return errorResponse(id, ErrorCode.MISSING_PARAMETER, "Missing parameter: enabled")
        }
        return audioResponse(id) { audioEffects.setDolby(request.optBoolean("enabled")) }
    }

    // Wraps an AudioEffectsController state in a SUCCESS response; its errors become 1006/1008.
    private fun audioResponse(id: Any?, block: () -> JSONObject): JSONObject = try {
        val state = block()
        successResponse(id).also { response -> state.keys().forEach { response.put(it, state.get(it)) } }
    } catch (e: IllegalArgumentException) {
        errorResponse(id, ErrorCode.COMMAND_FAILED, e.message ?: ErrorCode.messageFor(ErrorCode.COMMAND_FAILED))
    } catch (e: IllegalStateException) {
        errorResponse(id, ErrorCode.UNSUPPORTED_COMMAND, e.message ?: ErrorCode.messageFor(ErrorCode.UNSUPPORTED_COMMAND))
    }

    /** Re-applies the remembered sound mode; called once when the service starts. WhaleOS keeps
     *  its own sound mode, and the NXP equalizer stays off there so the two never stack. */
    fun restoreAudioEffects() {
        if (whaleOsSound == null) audioEffects.restore()
    }

    private fun handleVolumeAdjust(id: Any?, direction: Int): JSONObject {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        return successResponse(id)
    }

    // Every Settings screen opens into the Settings app's one task. With NEW_TASK alone, asking
    // for a screen that is already that task's root (e.g. Bluetooth, opened first) only brought
    // the task forward with whatever was on top of it (Wi-Fi, main Settings...) - so Bluetooth
    // opened once and never again (confirmed on the E1 via logcat: START, wm_task_moved, no
    // Displayed). CLEAR_TASK starts the task afresh with exactly the requested screen.
    private fun settingsIntent(intent: Intent): Intent =
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    private fun handleOpenSettings(id: Any?, action: String): JSONObject {
        context.startActivity(settingsIntent(Intent(action)))
        return successResponse(id)
    }

    /** F1/F1 Pro are identified up front (see [F1_FAMILY_BLUETOOTH_PAIRER_CLASS]) and routed
     *  straight to their known-working accessory pairer; every other vendor uses the standard
     *  action (works on vendors like SigmaStar/zhiying that do register it). */
    private fun handleOpenBluetooth(id: Any?): JSONObject {
        if (DeviceInfoProvider.isF1Family(context)) {
            val explicit = settingsIntent(
                Intent().setClassName(F1_FAMILY_BLUETOOTH_PAIRER_PKG, F1_FAMILY_BLUETOOTH_PAIRER_CLASS)
            )
            return if (tryStartActivity(explicit)) successResponse(id)
            else errorResponse(id, ErrorCode.COMMAND_FAILED, "No Bluetooth settings screen available on this device")
        }

        val implicit = settingsIntent(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        return if (tryStartActivity(implicit)) successResponse(id)
        else errorResponse(id, ErrorCode.COMMAND_FAILED, "No Bluetooth settings screen available on this device")
    }

    // MediaSessionManager.getActiveSessions() only returns sessions to a
    // caller holding an enabled NotificationListenerService component, hence
    // MediaNotificationListenerService (which itself does nothing else).
    private fun activeMediaController(): MediaController? {
        val mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val listenerComponent = ComponentName(context, MediaNotificationListenerService::class.java)
        val controllers = try {
            mediaSessionManager.getActiveSessions(listenerComponent)
        } catch (e: SecurityException) {
            return null
        }
        if (controllers.isEmpty()) return null
        return controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: controllers[0]
    }

    private fun handleTransportControl(id: Any?, action: (MediaController.TransportControls) -> Unit): JSONObject {
        val controller = activeMediaController()
            ?: return errorResponse(id, ErrorCode.COMMAND_FAILED, "No active media session")
        action(controller.transportControls)
        return successResponse(id)
    }

    private fun handlePlayPause(id: Any?): JSONObject {
        val controller = activeMediaController()
            ?: return errorResponse(id, ErrorCode.COMMAND_FAILED, "No active media session")
        if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
            controller.transportControls.pause()
        } else {
            controller.transportControls.play()
        }
        return successResponse(id)
    }

    private fun handleSeekTo(id: Any?, request: JSONObject): JSONObject {
        if (!request.has("position")) {
            return errorResponse(id, ErrorCode.MISSING_PARAMETER, "Missing parameter: position")
        }
        val controller = activeMediaController()
            ?: return errorResponse(id, ErrorCode.COMMAND_FAILED, "No active media session")
        controller.transportControls.seekTo(request.optLong("position", 0))
        return successResponse(id)
    }

    private fun handleGetMediaInfo(id: Any?): JSONObject {
        val controller = activeMediaController()
            ?: return errorResponse(id, ErrorCode.COMMAND_FAILED, "No active media session")
        val metadata = controller.metadata

        val response = successResponse(id)
        response.put("package", controller.packageName)
        response.put("title", metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "")
        response.put("artist", metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "")
        response.put("album", metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "")
        response.put("duration", metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
        return response
    }

    private fun handleGetPlaybackState(id: Any?): JSONObject {
        val controller = activeMediaController()
            ?: return errorResponse(id, ErrorCode.COMMAND_FAILED, "No active media session")
        val state = controller.playbackState

        val response = successResponse(id)
        response.put("package", controller.packageName)
        response.put("state", playbackStateName(state?.state))
        response.put("position", state?.position ?: 0L)
        response.put("playbackSpeed", state?.playbackSpeed?.toDouble() ?: 0.0)
        return response
    }

    private fun playbackStateName(state: Int?): String = when (state) {
        PlaybackState.STATE_PLAYING -> "PLAYING"
        PlaybackState.STATE_PAUSED -> "PAUSED"
        PlaybackState.STATE_STOPPED -> "STOPPED"
        PlaybackState.STATE_BUFFERING -> "BUFFERING"
        PlaybackState.STATE_ERROR -> "ERROR"
        PlaybackState.STATE_NONE -> "NONE"
        else -> "UNKNOWN"
    }

    // Every accessibility-dependent command resolves the service through here rather than
    // reading CineAgentAccessibilityService.instance directly, so a command arriving in the gap
    // between "setting is correct" and "OS has actually bound the service" gets a short chance
    // to catch the bind instead of failing immediately.
    private fun requireAccessibilityService(): CineAgentAccessibilityService? {
        CineAgentAccessibilityService.instance?.let { return it }

        AccessibilityServiceGuard.ensureEnabled(context)
        val deadline = System.currentTimeMillis() + ACCESSIBILITY_BIND_RETRY_WINDOW_MS
        while (System.currentTimeMillis() < deadline) {
            CineAgentAccessibilityService.instance?.let { return it }
            Thread.sleep(ACCESSIBILITY_BIND_RETRY_INTERVAL_MS)
        }
        return CineAgentAccessibilityService.instance
    }

    // HOME/BACK have no reliable Intent-based equivalent from a background
    // service (BACK especially), so both go through the accessibility
    // service's performGlobalAction(), same as USB/HDMI source selection
    // below goes through its on-screen node search.
    private fun handleAccessibilityAction(id: Any?, action: (CineAgentAccessibilityService) -> Boolean): JSONObject {
        val service = requireAccessibilityService()
            ?: return errorResponse(id, ErrorCode.PERMISSION_DENIED, "Accessibility service not enabled")
        return if (action(service)) {
            successResponse(id)
        } else {
            errorResponse(id, ErrorCode.COMMAND_FAILED, "Action was not performed")
        }
    }

    // "Menu": open the vendor's own menu screen by explicit component (the one its physical
    // MENU key opens - see VENDOR_MENU_SCREENS), first that starts wins. Falls back to the
    // recents global action only where no vendor screen resolves. Both ActivityNotFound and
    // SecurityException from tryStartActivity() just mean "try the next one".
    private fun handleMenu(id: Any?): JSONObject {
        for ((pkg, cls, extras) in VENDOR_MENU_SCREENS) {
            val intent = Intent().setClassName(pkg, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            extras.forEach { (key, value) -> intent.putExtra(key, value) }
            if (tryStartActivity(intent)) return successResponse(id)
        }
        return handleAccessibilityAction(id) { it.openRecents() }
    }

    // GLOBAL_ACTION_POWER_DIALOG only opens the Lockdown/Power off/Restart menu (same as a
    // physical power-button press) - it doesn't shut the device down by itself. Same pattern
    // as SELECT_SOURCE below: open it, give it time to render, then click through it.
    private fun handlePower(id: Any?): JSONObject {
        val service = requireAccessibilityService()
            ?: return errorResponse(id, ErrorCode.PERMISSION_DENIED, "Accessibility service not enabled")
        if (!service.openPowerDialog()) {
            return errorResponse(id, ErrorCode.COMMAND_FAILED, "Action was not performed")
        }
        Thread.sleep(800)
        return if (service.clickNodeByText("Power off")) {
            successResponse(id)
        } else {
            errorResponse(id, ErrorCode.COMMAND_FAILED, "Power off option not found on screen")
        }
    }

    // There's no single intent every vendor's source picker resolves: com.android.tv.action.
    // VIEW_INPUTS works on a SigmaStar pioneer5 unit but doesn't resolve at all on a Droidlogic/
    // Amlogic unit (confirmed via pm resolve-activity). KEYCODE_TV_INPUT would be universal -
    // every one of these projectors' physical remotes already opens its own native picker with
    // that exact key - but dispatching it ourselves needs the signature-level INJECT_EVENTS
    // permission, which Android refuses to hand to a non-privileged app under any circumstances
    // (confirmed: even `adb shell pm grant` is rejected with "not a changeable permission type").
    //
    // So this tries the standard action first, then falls back to launching each known vendor's
    // picker Activity by explicit component - add an entry to VENDOR_SOURCE_PICKERS after
    // confirming a new vendor's component name and that CineAgent (not adb/shell) can actually
    // launch it. Once whichever picker opens, the same clickNodeByText selection logic works
    // for all vendors since their pickers are simple HDMI/USB-labeled lists.
    private fun handleSelectSource(id: Any?, request: JSONObject): JSONObject {
        val source = request.optString("source", "")
        if (source.isEmpty()) {
            return errorResponse(id, ErrorCode.MISSING_PARAMETER, "Missing parameter: source")
        }
        val service = requireAccessibilityService()
            ?: return errorResponse(id, ErrorCode.PERMISSION_DENIED, "Accessibility service not enabled")

        if (clickSourceOnHomeScreen(service, source)) {
            return successResponse(id)
        }

        if (!openSourcePicker()) {
            return errorResponse(id, ErrorCode.COMMAND_FAILED, "No supported source picker found on this projector")
        }

        // A single fixed-delay click attempt was flaky in the field: on a real projector
        // (SigmaStar pioneer5) the picker sometimes hadn't finished populating its node tree
        // 800ms after opening, so clickNodeByText found nothing and the command failed with no
        // routing change ever happening. Poll instead of a one-shot attempt, same pattern as
        // handlePower() above.
        Thread.sleep(SELECT_SOURCE_INITIAL_DELAY_MS)
        val deadline = System.currentTimeMillis() + SELECT_SOURCE_RETRY_WINDOW_MS
        var clicked = service.clickNodeByText(source)
        while (!clicked && System.currentTimeMillis() < deadline) {
            Thread.sleep(SELECT_SOURCE_RETRY_INTERVAL_MS)
            clicked = service.clickNodeByText(source)
        }

        if (clicked) {
            return successResponse(id)
        }

        if (source.equals("USB", ignoreCase = true) && openUsbFileManager()) {
            return successResponse(id)
        }

        return errorResponse(id, ErrorCode.COMMAND_FAILED, "No on-screen element found for source: $source")
    }

    /** Some launchers (confirmed: CineHead M1's com.newlink.overseaslauncher) draw their own
     *  "SOURCE INPUT" row with HDMI/USB icons directly on the home screen - separate from, and
     *  on that vendor strictly more complete than, the system Select-Input picker below (whose
     *  com.android.tv.SelectInputActivity only lists TvInputService entries - tuner/HDMI - and
     *  structurally can never have a USB row, since a USB drive isn't a live AV signal source).
     *  Tried first with a short timeout: on launchers without this row it fails fast and falls
     *  through unchanged to the picker-based flow, so this doesn't add real latency there. */
    private fun clickSourceOnHomeScreen(service: CineAgentAccessibilityService, source: String): Boolean {
        service.goHome()
        Thread.sleep(HOME_SOURCE_ROW_INITIAL_DELAY_MS)
        val deadline = System.currentTimeMillis() + HOME_SOURCE_ROW_WINDOW_MS
        var clicked = service.clickNodeByText(source)
        while (!clicked && System.currentTimeMillis() < deadline) {
            Thread.sleep(HOME_SOURCE_ROW_RETRY_INTERVAL_MS)
            clicked = service.clickNodeByText(source)
        }
        return clicked
    }

    /** Last resort for USB when the picker has no "USB" row on this vendor's firmware: open the
     *  known file manager app directly instead, same pattern as handleOpenApp(). */
    private fun openUsbFileManager(): Boolean {
        for (pkg in VENDOR_USB_FILE_MANAGERS) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                ?: context.packageManager.getLeanbackLaunchIntentForPackage(pkg)
                ?: continue
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tryStartActivity(launchIntent)) return true
        }
        return false
    }

    /** Tries the standard action, then each known vendor fallback in turn, returning true on
     *  the first one that actually launches - both ActivityNotFoundException (doesn't resolve,
     *  e.g. VIEW_INPUTS on non-SigmaStar vendors) and SecurityException (resolves but isn't
     *  exported to a non-privileged caller) mean "try the next one," not "give up." */
    private fun openSourcePicker(): Boolean {
        val implicit = Intent("com.android.tv.action.VIEW_INPUTS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (tryStartActivity(implicit)) return true

        for ((pkg, cls) in VENDOR_SOURCE_PICKERS) {
            val explicit = Intent().setClassName(pkg, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tryStartActivity(explicit)) return true
        }
        return false
    }

    private fun tryStartActivity(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    private fun successResponse(id: Any?): JSONObject {
        val response = JSONObject()
        if (id != null) response.put("id", id)
        response.put("status", "SUCCESS")
        return response
    }

    private fun errorResponse(id: Any?, code: Int, message: String): JSONObject {
        val response = JSONObject()
        if (id != null) response.put("id", id)
        response.put("status", "ERROR")
        response.put("errorCode", code)
        response.put("message", message)
        return response
    }
}
