package com.projector.airemote.net

import android.util.Log
import java.io.File

/**
 * Reads the kernel's ARP neighbor table (/proc/net/arp) to shortcut discovery to hosts the
 * phone has actually exchanged traffic with recently, instead of a blind subnet sweep.
 * Some Android versions/SELinux policies deny this read for regular apps - that's treated as
 * "unavailable" (returns null) rather than an error, so callers can fall back to a full scan
 * (SRS: "ARP Available -> ... / ARP Unavailable -> Scan Local Subnet").
 */
object ArpCacheReader {
    private const val TAG = "ArpCacheReader"
    private const val ARP_TABLE_PATH = "/proc/net/arp"
    private const val FLAG_COMPLETE = 0x2
    private val ZERO_MAC = "00:00:00:00:00:00"

    fun readActiveIpAddresses(): List<String>? {
        return try {
            val lines = File(ARP_TABLE_PATH).readLines()
            if (lines.size <= 1) return null // header only, or unreadable-but-empty
            val active = lines.drop(1).mapNotNull { parseLine(it) }
            if (active.isEmpty()) null else active
        } catch (e: Exception) {
            Log.w(TAG, "ARP cache unavailable ($ARP_TABLE_PATH): ${e.message}")
            null
        }
    }

    private fun parseLine(line: String): String? {
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size < 4) return null
        val ip = fields[0]
        val flags = fields[2].removePrefix("0x").toIntOrNull(16) ?: return null
        val mac = fields[3]
        if (flags and FLAG_COMPLETE == 0) return null
        if (mac.equals(ZERO_MAC, ignoreCase = true)) return null
        return ip
    }
}
