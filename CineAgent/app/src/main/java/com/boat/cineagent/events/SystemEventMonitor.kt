package com.boat.cineagent.events

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.boat.cineagent.accessibility.CineAgentAccessibilityService
import android.net.Uri
import android.provider.Settings
import com.boat.cineagent.audio.AudioEffectsController
import com.boat.cineagent.audio.WhaleOsSoundModes
import com.boat.cineagent.util.DeviceInfoProvider
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.roundToInt

// Turns OS-level signals into the async events from spec section 7.
class SystemEventMonitor(
    private val context: Context,
    private val onEvent: (JSONObject) -> Unit
) {
    private var wifiReceiver: BroadcastReceiver? = null
    private var bluetoothReceiver: BroadcastReceiver? = null
    private var volumeReceiver: BroadcastReceiver? = null
    private var shutdownReceiver: BroadcastReceiver? = null

    @Volatile
    private var running = false
    private var lastForegroundPackage: String? = null
    private var lastWifiConnected: Boolean? = null
    @Volatile
    private var lastFocusedFieldSignature: String? = null
    private var lastDolby: Boolean? = null
    private var dolbyObserver: ContentObserver? = null
    private var soundModeObserver: ContentObserver? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val emitEqualizerChanged = Runnable { emit("EQUALIZER_CHANGED") }

    /** Forces the next focused-field poll (<=2s away) to re-emit the current
     *  TEXT_FIELD_FOCUSED state even though it hasn't changed. Call this when a new TCP client
     *  connects: the phone resets its own focusedTextField to null on every (re)connect, so a
     *  client that connected after the last change would otherwise never learn a field is
     *  focused and Keyboard Mode would stay hidden until the field's contents change on-screen.
     *  Clearing to null (not "" ) so that when no field is focused this stays a no-op - the
     *  poll's `signature == last` check still short-circuits and no spurious BLURRED is sent. */
    fun resyncFocusedTextField() {
        lastFocusedFieldSignature = null
    }

    fun start() {
        running = true
        registerWifiReceiver()
        registerBluetoothReceiver()
        registerVolumeReceiver()
        registerShutdownReceiver()
        registerDolbyObserver()
        registerSoundModeObserver()
        startForegroundAppPolling()
    }

    fun stop() {
        running = false
        for (receiver in listOf(wifiReceiver, bluetoothReceiver, volumeReceiver, shutdownReceiver)) {
            receiver?.let {
                try {
                    context.unregisterReceiver(it)
                } catch (t: Throwable) {
                }
            }
        }
        for (observer in listOf(dolbyObserver, soundModeObserver)) {
            observer?.let {
                try {
                    context.contentResolver.unregisterContentObserver(it)
                } catch (t: Throwable) {
                }
            }
        }
        dolbyObserver = null
        soundModeObserver = null
        mainHandler.removeCallbacks(emitEqualizerChanged)
    }

    private fun emit(eventName: String) {
        onEvent(JSONObject().put("event", eventName))
    }

    private fun registerWifiReceiver() {
        val filter = IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
                val connected = networkInfo?.isConnected == true
                if (lastWifiConnected == connected) return
                lastWifiConnected = connected
                emit(if (connected) "WIFI_CONNECTED" else "WIFI_DISCONNECTED")
            }
        }
        context.registerReceiver(receiver, filter)
        wifiReceiver = receiver
    }

    private fun registerBluetoothReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> emit("BLUETOOTH_CONNECTED")
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> emit("BLUETOOTH_DISCONNECTED")
                }
            }
        }
        try {
            context.registerReceiver(receiver, filter)
            bluetoothReceiver = receiver
        } catch (t: SecurityException) {
        }
    }

    private fun registerVolumeReceiver() {
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                if (streamType != AudioManager.STREAM_MUSIC) return

                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val percent = if (max > 0) ((current.toDouble() / max) * 100).roundToInt() else 0

                onEvent(JSONObject().apply {
                    put("event", "VOLUME_CHANGED")
                    put("current", percent)
                    put("maximum", 100)
                })
            }
        }
        context.registerReceiver(receiver, filter)
        volumeReceiver = receiver
    }

    private fun registerShutdownReceiver() {
        val filter = IntentFilter(Intent.ACTION_SHUTDOWN)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                emit("DEVICE_REBOOTING")
            }
        }
        context.registerReceiver(receiver, filter)
        shutdownReceiver = receiver
    }

    private fun startForegroundAppPolling() {
        thread(name = "cineagent-app-watcher") {
            while (running) {
                try {
                    pollForegroundApp()
                } catch (t: Throwable) {
                }
                try {
                    pollFocusedTextField()
                } catch (t: Throwable) {
                }
                try {
                    Thread.sleep(2000)
                } catch (t: InterruptedException) {
                    break
                }
            }
        }
    }

    // Keyboard Mode Screen: reads the accessibility service's live findFocus(FOCUS_INPUT) check
    // (see CineAgentAccessibilityService.currentFocusedTextField()) on the same 2s cadence as
    // foreground-app polling, rather than reacting to onAccessibilityEvent directly - keeps this
    // one thread as the single source of all polled events instead of adding a second push path.
    private fun pollFocusedTextField() {
        // While CineAgent is itself driving a focused field - specifically typing a search query
        // into Hotstar's search bar for an OPEN_SEARCH{query} it is fulfilling server-side (see
        // CommandHandler.handleOpenSearch()'s Hotstar branch) - a TEXT_FIELD_FOCUSED push would
        // wrongly pop Keyboard Mode on the phone over a search that has already been run. Stay a
        // no-op for that window; the next poll after it closes re-syncs to the real on-screen
        // state. Only Hotstar's query path opens this window, so every other app is unaffected.
        if (System.currentTimeMillis() < suppressFocusedUntilMs) return

        val field = CineAgentAccessibilityService.instance?.currentFocusedTextField()
        val signature = field?.let { "${it.packageName}|${it.isPassword}|${it.text}" }
        if (signature == lastFocusedFieldSignature) return
        lastFocusedFieldSignature = signature

        if (field == null) {
            emit("TEXT_FIELD_BLURRED")
            return
        }
        onEvent(JSONObject().apply {
            put("event", "TEXT_FIELD_FOCUSED")
            put("package", field.packageName)
            put("isPassword", field.isPassword)
            put("text", field.text)
        })
    }

    // Dolby Audio Processing switched from the projector's own Sound menu (remote) never reaches
    // CineAgent as a command. The MediaTek TV agent's sound database notifies observers of
    // .../general/AQ_DolbyDap when the switch changes (the agent itself watches that URI, confirmed
    // live 2026-10-15 via `dumpsys content` on an F1 Pro), so watch it too and push DOLBY_CHANGED -
    // no polling. On projectors without that database the registration fails and nothing is sent.
    // Changes made via SET_DOLBY are pushed as well, which is harmless: the phone re-reads the state.
    private fun registerDolbyObserver() {
        lastDolby = AudioEffectsController.readDolby(context) ?: return
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                thread(name = "cineagent-dolby-read") {
                    val on = AudioEffectsController.readDolby(context) ?: return@thread
                    synchronized(this@SystemEventMonitor) {
                        if (on == lastDolby) return@thread
                        lastDolby = on
                    }
                    onEvent(JSONObject().apply {
                        put("event", "DOLBY_CHANGED")
                        put("enabled", on)
                    })
                }
            }
        }
        try {
            context.contentResolver.registerContentObserver(AudioEffectsController.DOLBY_DAP_URI, false, observer)
            dolbyObserver = observer
        } catch (t: Throwable) {
        }
    }

    // WhaleOS sound mode or band level changed - usually from the projector's own Sound menu with
    // the remote, which never reaches CineAgent as a command. WhaleOS stores both in settings
    // global, so watch that and push EQUALIZER_CHANGED; the phone re-reads GET_EQUALIZER. A remote
    // held on a band slider writes many levels in a row, so changes are gathered for
    // EQUALIZER_DEBOUNCE_MS into one event. Changes made via SET_EQUALIZER are pushed too (harmless).
    private fun registerSoundModeObserver() {
        if (!DeviceInfoProvider.isWhaleOs(context)) return
        val observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                if (!WhaleOsSoundModes.isSoundModeKey(uri?.lastPathSegment)) return
                mainHandler.removeCallbacks(emitEqualizerChanged)
                mainHandler.postDelayed(emitEqualizerChanged, EQUALIZER_DEBOUNCE_MS)
            }
        }
        try {
            context.contentResolver.registerContentObserver(Settings.Global.CONTENT_URI, true, observer)
            soundModeObserver = observer
        } catch (t: Throwable) {
        }
    }

    private fun pollForegroundApp() {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        if (mode != AppOpsManager.MODE_ALLOWED) return

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val start = end - 10_000
        val events = usageStatsManager.queryEvents(start, end)

        var foregroundPackage: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                foregroundPackage = event.packageName
            }
        }

        if (foregroundPackage != null && foregroundPackage != lastForegroundPackage) {
            lastForegroundPackage = foregroundPackage
            val appName = try {
                val info = context.packageManager.getApplicationInfo(foregroundPackage, 0)
                context.packageManager.getApplicationLabel(info).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                foregroundPackage
            }
            onEvent(JSONObject().apply {
                put("event", "APP_CHANGED")
                put("package", foregroundPackage)
                put("appName", appName)
            })
        }
    }

    companion object {
        private const val EQUALIZER_DEBOUNCE_MS = 300L

        /** Epoch-ms until which [pollFocusedTextField] suppresses TEXT_FIELD_FOCUSED /
         *  TEXT_FIELD_BLURRED. Set by [suppressFocusedTextField] while CineAgent is driving a
         *  field itself; static so CommandHandler can reach it without wiring a monitor
         *  reference through CineAgentService. */
        @Volatile
        private var suppressFocusedUntilMs = 0L

        /** Suppress focused-field events for the next [durationMs]. Extends an existing window,
         *  never shortens it (concurrent callers / re-arming after a submit both just push the
         *  deadline out). */
        fun suppressFocusedTextField(durationMs: Long) {
            val until = System.currentTimeMillis() + durationMs
            if (until > suppressFocusedUntilMs) suppressFocusedUntilMs = until
        }
    }
}
