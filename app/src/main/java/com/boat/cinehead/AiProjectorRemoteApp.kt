package com.boat.cinehead

import android.app.Application
import com.boat.cinehead.net.AppSettingsStore
import com.boat.cinehead.net.AutoReconnectGate
import com.boat.cinehead.net.LastConnectedStore
import com.boat.cinehead.net.ProjectorConnectionManager

class AiProjectorRemoteApp : Application() {
    lateinit var connectionManager: ProjectorConnectionManager
        private set
    lateinit var appSettingsStore: AppSettingsStore
        private set
    lateinit var lastConnectedStore: LastConnectedStore
        private set
    lateinit var autoReconnectGate: AutoReconnectGate
        private set

    override fun onCreate() {
        super.onCreate()
        autoReconnectGate = AutoReconnectGate()
        connectionManager = ProjectorConnectionManager(this, autoReconnectGate)
        appSettingsStore = AppSettingsStore(this)
        lastConnectedStore = LastConnectedStore(this)
    }
}
