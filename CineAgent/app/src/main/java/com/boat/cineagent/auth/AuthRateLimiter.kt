package com.boat.cineagent.auth

// Blocks an address for BLOCK_MS after MAX_FAILURES wrong keys inside WINDOW_MS, so Key #1 and
// Key #2 can't be guessed by hammering discovery or AUTH. One instance per server.
class AuthRateLimiter {

    companion object {
        private const val MAX_FAILURES = 5
        private const val WINDOW_MS = 60_000L
        private const val BLOCK_MS = 60_000L
        private const val MAX_TRACKED = 256
    }

    private class Record(var windowStart: Long, var failures: Int, var blockedUntil: Long)

    private val records = HashMap<String, Record>()

    @Synchronized
    fun isBlocked(address: String): Boolean {
        val record = records[address] ?: return false
        return System.currentTimeMillis() < record.blockedUntil
    }

    @Synchronized
    fun recordFailure(address: String) {
        val now = System.currentTimeMillis()
        if (records.size >= MAX_TRACKED && address !in records) {
            records.entries.removeAll { now >= it.value.blockedUntil && now - it.value.windowStart > WINDOW_MS }
            if (records.size >= MAX_TRACKED) return
        }
        val record = records.getOrPut(address) { Record(now, 0, 0) }
        if (now - record.windowStart > WINDOW_MS) {
            record.windowStart = now
            record.failures = 0
        }
        record.failures++
        if (record.failures >= MAX_FAILURES) {
            record.blockedUntil = now + BLOCK_MS
            record.failures = 0
            record.windowStart = now
        }
    }

    @Synchronized
    fun recordSuccess(address: String) {
        records.remove(address)
    }
}
