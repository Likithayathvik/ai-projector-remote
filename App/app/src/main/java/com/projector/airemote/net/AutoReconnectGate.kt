package com.projector.airemote.net

/**
 * Ensures "try the last connected projector" (SRS: Enhanced Discovery Flow) only happens once
 * per actual app launch/process start, not every time the Discovery screen is (re)composed -
 * e.g. after the user taps Disconnect, or after an unexpected connection loss, we should show
 * the scan list directly rather than silently reconnecting them right back to what they just left.
 * Application-scoped so it naturally resets on a real cold start but stays "used" across
 * in-process navigation.
 */
class AutoReconnectGate {
    @Volatile
    private var hasAttempted = false

    /** Returns true (and flips the gate) only the first time this is called this process. */
    fun tryConsume(): Boolean {
        if (hasAttempted) return false
        hasAttempted = true
        return true
    }

    /** Re-arms the gate so the next return to Discovery tries the last-connected projector
     *  directly again, instead of falling through to a full subnet scan. Call this only for
     *  an *unexpected* connection loss (e.g. the heartbeat failing after the phone slept and
     *  the ADB socket died) - never after an explicit user-initiated disconnect, which must
     *  keep showing the scan list rather than silently reconnecting. */
    fun reset() {
        hasAttempted = false
    }
}
