package com.projector.airemote.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.projector.airemote.adb.AdbConnection
import com.projector.airemote.adb.AdbResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

data class VerifiedProjector(val ipAddress: String, val port: Int, val model: String, val manufacturer: String)

/**
 * Implements the "Enhanced Projector Discovery Flow" SRS: ARP cache first (fast), full
 * subnet sweep as a fallback, a plain TCP probe on port 5555, then an actual ADB connect +
 * `getprop` check so only devices that genuinely answer as Android/ADB show up in the list.
 */
class ProjectorScanner(private val context: Context) {

    suspend fun scan(
        onVerified: suspend (VerifiedProjector) -> Unit
    ) = withContext(Dispatchers.IO) {
        val arpCandidates = ArpCacheReader.readActiveIpAddresses()
        var foundAny = false

        if (arpCandidates != null) {
            Log.d(TAG, "Using ARP cache: ${arpCandidates.size} active host(s)")
            foundAny = probeAndVerify(arpCandidates, onVerified)
        }

        // The ARP cache only lists hosts the phone has already exchanged traffic with recently -
        // an idle projector the phone hasn't talked to since it last joined the network simply
        // won't have an entry yet, even though the cache read itself succeeded. Always fall back
        // to a full subnet sweep when nothing was verified, not only when the ARP read failed.
        if (!foundAny) {
            val subnetHosts = subnetHostAddresses().filterNot { arpCandidates?.contains(it) == true }
            Log.d(
                TAG,
                "${if (arpCandidates == null) "ARP cache unavailable" else "Nothing verified via ARP cache"}, " +
                    "scanning subnet: ${subnetHosts.size} host(s)"
            )
            probeAndVerify(subnetHosts, onVerified)
        }
    }

    /** Probes [candidates] for an open ADB port and verifies each one. Returns true if at least
     *  one genuine projector was found, so the caller knows whether a broader fallback is needed. */
    private suspend fun probeAndVerify(
        candidates: List<String>,
        onVerified: suspend (VerifiedProjector) -> Unit
    ): Boolean {
        val openPortHosts = probeOpenPorts(candidates)
        Log.d(TAG, "${openPortHosts.size} host(s) with port $ADB_PORT open: $openPortHosts")
        if (openPortHosts.isEmpty()) return false

        val semaphore = Semaphore(4)
        val verified = coroutineScope {
            openPortHosts.map { ip ->
                async {
                    semaphore.withPermit {
                        val projector = verify(ip)
                        projector?.let { onVerified(it) }
                        projector != null
                    }
                }
            }.awaitAll()
        }
        return verified.any { it }
    }

    private suspend fun verify(ip: String): VerifiedProjector? {
        // A host that just answered the plain TCP port probe can still fail the *next* fresh
        // connection right after - confirmed on a real device/router where a burst of probe
        // connections across the subnet leaves the following ADB connect attempt timing out for
        // a few seconds, while a retry moments later succeeds immediately. Retry a couple of
        // times rather than writing off a host that's already proven to have the port open.
        var connection: AdbConnection? = null
        var lastError: String? = null
        for (attempt in 1..VERIFY_CONNECT_ATTEMPTS) {
            val connectionResult = AdbConnection.connect(ip, ADB_PORT)
            connection = connectionResult.getOrNull()
            if (connection != null) break
            lastError = connectionResult.exceptionOrNull()?.message
            if (attempt < VERIFY_CONNECT_ATTEMPTS) delay(VERIFY_RETRY_DELAY_MS)
        }
        if (connection == null) {
            Log.d(TAG, "$ip: ADB connect failed after $VERIFY_CONNECT_ATTEMPTS attempt(s) ($lastError)")
            return null
        }
        try {
            // Both props in one shell round-trip rather than two separate ADB streams -
            // halves the per-candidate verify latency.
            val (model, manufacturer) = getModelAndManufacturer(connection)
            if (model.isNullOrBlank() || manufacturer.isNullOrBlank()) {
                Log.d(TAG, "$ip: getprop verification failed (model='$model', manufacturer='$manufacturer')")
                return null
            }
            Log.d(TAG, "$ip: verified as $manufacturer $model")
            return VerifiedProjector(ip, ADB_PORT, model, manufacturer)
        } finally {
            connection.close()
        }
    }

    private suspend fun getModelAndManufacturer(connection: AdbConnection): Pair<String?, String?> {
        val result = connection.shell("getprop ro.product.model; getprop ro.product.manufacturer")
        val output = (result as? AdbResult.Success)?.output ?: return null to null
        val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return lines.getOrNull(0) to lines.getOrNull(1)
    }

    private suspend fun probeOpenPorts(candidates: List<String>): List<String> = coroutineScope {
        val semaphore = Semaphore(PORT_PROBE_CONCURRENCY)
        candidates.map { ip ->
            async {
                semaphore.withPermit { if (isPortOpen(ip)) ip else null }
            }
        }.awaitAll().filterNotNull()
    }

    /** Retries like [verify] does - a burst of probes across the subnet can make a single
     *  attempt miss its deadline even for a host that's genuinely listening, so one failure
     *  here shouldn't drop the host before it ever reaches the (already resilient) verify step. */
    private suspend fun isPortOpen(ip: String): Boolean {
        repeat(PORT_PROBE_ATTEMPTS) { attempt ->
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ip, ADB_PORT), PORT_PROBE_TIMEOUT_MS)
                }
                return true
            } catch (e: Exception) {
                Log.d(TAG, "$ip: port probe attempt ${attempt + 1} failed (${e.javaClass.simpleName}: ${e.message})")
                if (attempt < PORT_PROBE_ATTEMPTS - 1) delay(PORT_PROBE_RETRY_DELAY_MS)
            }
        }
        return false
    }

    /** Mask-aware: derives the true host range from the phone's actual Wi-Fi prefix length
     *  rather than assuming /24 (this network is a /22 - a /24 assumption would miss hosts). */
    private fun subnetHostAddresses(): List<String> {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return emptyList()
        val wifiNetwork = cm.allNetworks.firstOrNull { network ->
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return emptyList()
        val linkAddress = cm.getLinkProperties(wifiNetwork)?.linkAddresses
            ?.firstOrNull { it.address is java.net.Inet4Address } ?: return emptyList()

        val ipBytes = linkAddress.address.address
        val ipInt = ((ipBytes[0].toInt() and 0xFF) shl 24) or
            ((ipBytes[1].toInt() and 0xFF) shl 16) or
            ((ipBytes[2].toInt() and 0xFF) shl 8) or
            (ipBytes[3].toInt() and 0xFF)
        val prefixLength = linkAddress.prefixLength.coerceAtLeast(MIN_PREFIX_LENGTH)
        val maskInt = if (prefixLength <= 0) 0 else -1 shl (32 - prefixLength)
        val networkInt = ipInt and maskInt
        val hostCount = (1 shl (32 - prefixLength)) - 2 // exclude network + broadcast addresses

        return (1..hostCount).map { hostOffset ->
            val hostInt = networkInt + hostOffset
            "%d.%d.%d.%d".format(
                hostInt shr 24 and 0xFF,
                hostInt shr 16 and 0xFF,
                hostInt shr 8 and 0xFF,
                hostInt and 0xFF
            )
        }
    }

    companion object {
        private const val TAG = "ProjectorScanner"
        private const val ADB_PORT = 5555
        /** A burst of connection attempts to many hosts across a /24 in a short window can
         *  cause a live host's probe to miss a short deadline - confirmed via
         *  SocketTimeoutException on a real device/router even though the neighbor table
         *  already had that host's MAC cached (so it wasn't waiting on ARP resolution). This
         *  longer timeout plus the retry below made the probe reliable in testing. */
        private const val PORT_PROBE_TIMEOUT_MS = 600
        /** Most of a sweep's wall-clock time is spent waiting out this timeout on addresses
         *  nothing is listening on, 8 at a time - now that a failed probe gets retried instead
         *  of writing the host off immediately, concurrency can go higher without losing real
         *  projectors to the burst-timeout effect described above. */
        private const val PORT_PROBE_CONCURRENCY = 24
        /** Short because this only needs to ride out a transient burst-induced timeout, not a
         *  genuinely closed port - a couple of quick attempts is enough. */
        private const val PORT_PROBE_ATTEMPTS = 2
        private const val PORT_PROBE_RETRY_DELAY_MS = 300L
        /** Only a handful of hosts ever reach this point (those the port probe already found
         *  open), so retrying here is cheap. Confirmed on a real device: the ADB connect right
         *  after a subnet-wide probe burst can time out for several seconds, while a retry
         *  moments later succeeds immediately. */
        private const val VERIFY_CONNECT_ATTEMPTS = 3
        private const val VERIFY_RETRY_DELAY_MS = 800L
        /** Caps a full sweep at a /20 (4094 hosts) even if the real prefix is smaller/wider. */
        private const val MIN_PREFIX_LENGTH = 20
    }
}
