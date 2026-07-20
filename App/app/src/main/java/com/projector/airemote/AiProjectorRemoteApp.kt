package com.projector.airemote

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.projector.airemote.net.AppSettingsStore
import com.projector.airemote.net.AutoReconnectGate
import com.projector.airemote.net.FirebaseServerController
import com.projector.airemote.net.LastConnectedStore
import com.projector.airemote.net.ProjectorConnectionManager

class AiProjectorRemoteApp : Application() {
    lateinit var connectionManager: ProjectorConnectionManager
        private set
    lateinit var appSettingsStore: AppSettingsStore
        private set
    lateinit var lastConnectedStore: LastConnectedStore
        private set
    lateinit var autoReconnectGate: AutoReconnectGate
        private set
    lateinit var firebaseServerController: FirebaseServerController
        private set

    override fun onCreate() {
        super.onCreate()
        autoReconnectGate = AutoReconnectGate()
        connectionManager = ProjectorConnectionManager(this, autoReconnectGate)
        appSettingsStore = AppSettingsStore(this)
        lastConnectedStore = LastConnectedStore(this)
        firebaseServerController = FirebaseServerController()

        // Fires once for the whole app process — not per Activity/screen.
        // We deliberately do NOT signal "start" here: the server should only
        // spin up when the user actually sends their first query (see
        // RemoteViewModel.sendChat / ensureServerStarted). We DO signal
        // "stop" when the app is backgrounded/closed, so the server doesn't
        // keep running after the user has left the app.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                firebaseServerController.signalStop()
            }
        })
    }
}
