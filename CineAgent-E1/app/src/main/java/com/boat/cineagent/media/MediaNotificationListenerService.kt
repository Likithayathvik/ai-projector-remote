package com.boat.cineagent.media

import android.service.notification.NotificationListenerService
import com.boat.cineagent.CineAgentService

// Otherwise empty on purpose: MediaSessionManager.getActiveSessions() only requires an
// enabled listener component to exist, not that it override any callback.
//
// onListenerConnected() doubles as the most reliable "app is back up after boot" signal we
// have on real hardware: a device capture after a power cycle showed NotificationManagerService
// reliably re-binding this listener a few seconds after user-unlock, while
// CineAgentAccessibilityService's own rebind (the other trigger for CineAgentService.start())
// never happened at all in that same capture - AccessibilityManagerService skipped it as
// "non-encryption-aware" early in boot and never retried post-unlock on this OEM skin. Starting
// it from here as well means CineAgentService still comes up even when that accessibility
// rebind doesn't.
class MediaNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        if (!CineAgentService.isRunning) {
            CineAgentService.start(this)
        }
    }
}
