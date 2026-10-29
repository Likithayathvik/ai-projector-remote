package com.boat.cineagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AccessibilityServiceGuard.ensureEnabled(context)
            CineAgentService.start(context)
        }
    }
}
