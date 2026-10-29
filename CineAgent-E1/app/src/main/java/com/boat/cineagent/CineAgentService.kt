package com.boat.cineagent

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.boat.cineagent.auth.SessionStore
import com.boat.cineagent.commands.CommandHandler
import com.boat.cineagent.events.SystemEventMonitor
import com.boat.cineagent.net.TcpCommandServer
import com.boat.cineagent.net.UdpDiscoveryServer
import com.boat.cineagent.util.DeviceInfoProvider
import kotlin.concurrent.thread

class CineAgentService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val TV_INPUT_REBIND_DELAY_MS = 15_000L

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var connectedClientCount: Int = 0
            private set

        @Volatile
        var deviceName: String = ""
            private set

        @Volatile
        var ipAddress: String = ""
            private set

        @Volatile
        var serialNumber: String = ""
            private set

        fun start(context: Context) {
            val intent = Intent(context, CineAgentService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CineAgentService::class.java))
        }
    }

    private lateinit var udpDiscoveryServer: UdpDiscoveryServer
    private lateinit var tcpCommandServer: TcpCommandServer
    private lateinit var systemEventMonitor: SystemEventMonitor

    private var lastClientCount = 0

    override fun onCreate() {
        super.onCreate()

        val commandHandler = CommandHandler(this)
        val sessionStore = SessionStore(this)
        tcpCommandServer = TcpCommandServer(BuildConfig.TCP_COMMAND_PORT, commandHandler, sessionStore) { count ->
            val increased = count > lastClientCount
            lastClientCount = count
            connectedClientCount = count
            // A freshly-connected client (e.g. the phone after a Wi-Fi drop + reconnect) has
            // reset its own focusedTextField state - re-push the current focused field to it so
            // Keyboard Mode reappears without waiting for the on-screen field to change.
            if (increased && ::systemEventMonitor.isInitialized) {
                systemEventMonitor.resyncFocusedTextField()
            }
        }
        udpDiscoveryServer = UdpDiscoveryServer(BuildConfig.UDP_DISCOVERY_PORT, BuildConfig.TCP_COMMAND_PORT, this, sessionStore)
        systemEventMonitor = SystemEventMonitor(this) { event -> tcpCommandServer.broadcast(event) }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )

        udpDiscoveryServer.start()
        tcpCommandServer.start()
        systemEventMonitor.start()
        // Off the main thread: it queries the TV agent's sound database.
        thread(name = "cineagent-audio-restore") { commandHandler.restoreAudioEffects() }
        // After an app update the TV input auto-start hook is left unbound (see
        // AutoStartTvInputService.rebindIfNotRunning). Delayed so that during a normal boot
        // TvInputManagerService gets the chance to bind it on its own first.
        Handler(Looper.getMainLooper()).postDelayed(
            { AutoStartTvInputService.rebindIfNotRunning(this) },
            TV_INPUT_REBIND_DELAY_MS
        )

        deviceName = DeviceInfoProvider.deviceName(this)
        ipAddress = DeviceInfoProvider.localIpAddress() ?: ""
        serialNumber = DeviceInfoProvider.serialNumber(this)
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        systemEventMonitor.stop()
        tcpCommandServer.stop()
        udpDiscoveryServer.stop()
        isRunning = false
        connectedClientCount = 0
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification() =
        NotificationCompat.Builder(this, CineAgentApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}
