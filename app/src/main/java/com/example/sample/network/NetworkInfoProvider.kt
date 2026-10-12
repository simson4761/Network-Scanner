package com.example.sample.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address

/**
 * Reads the active Wi-Fi network's IPv4 address and prefix length from
 * [ConnectivityManager]/LinkProperties and computes the scannable host range.
 *
 * Requires ACCESS_NETWORK_STATE. Subnets are never assumed to be /24. If the real subnet has more
 * than [maxScanHosts] usable hosts (e.g. a /16), enumeration is limited to the smaller block
 * (aligned to the local address) that fits; see [NetworkInfo.isScanRangeLimited].
 */
class NetworkInfoProvider(
    context: Context,
    private val maxScanHosts: Int = DEFAULT_MAX_SCAN_HOSTS
) {
    private val connectivityManager: ConnectivityManager? =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    init {
        require(maxScanHosts >= 1) { "maxScanHosts must be >= 1" }
    }

    /** Returns network info or a failure holding a [NetworkUnavailableException]. */
    fun getNetworkInfo(): Result<NetworkInfo> {
        val cm = connectivityManager
            ?: return failure("ConnectivityManager is unavailable")
        return try {
            val network = findWifiNetwork(cm)
                ?: return failure("No active Wi-Fi network (mobile data, Ethernet or offline)")
            val linkProperties = cm.getLinkProperties(network)
                ?: return failure("No LinkProperties for the Wi-Fi network")
            val linkAddress = linkProperties.linkAddresses.firstOrNull { la ->
                val a = la.address
                a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress && !a.isAnyLocalAddress
            } ?: return failure("Wi-Fi network has no usable IPv4 address (IPv6-only?)")
            calculateNetworkInfo(
                network = network,
                localAddress = linkAddress.address as Inet4Address,
                prefixLength = linkAddress.prefixLength,
                maxScanHosts = maxScanHosts
            )
        } catch (e: SecurityException) {
            failure("Missing ACCESS_NETWORK_STATE permission", e)
        }
    }

    // allNetworks is deprecated since API 31; it is only a fallback for when a VPN is the
    // active network and hides the underlying Wi-Fi network.
    @Suppress("DEPRECATION")
    private fun findWifiNetwork(cm: ConnectivityManager): Network? {
        fun isWifi(n: Network): Boolean {
            val caps = cm.getNetworkCapabilities(n) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        cm.activeNetwork?.takeIf { isWifi(it) }?.let { return it }
        return cm.allNetworks.firstOrNull { isWifi(it) }
    }

    companion object {
        const val DEFAULT_MAX_SCAN_HOSTS = 4094 // one /20

        private fun failure(message: String, cause: Throwable? = null): Result<NetworkInfo> =
            Result.failure(NetworkUnavailableException(message, cause))

        /** Pure subnet math, exposed so it can be unit tested without Android. */
        fun calculateNetworkInfo(
            network: Network?,
            localAddress: Inet4Address,
            prefixLength: Int,
            maxScanHosts: Int = DEFAULT_MAX_SCAN_HOSTS
        ): Result<NetworkInfo> {
            if (prefixLength !in 0..32) return failure("Invalid prefix length: $prefixLength")
            if (maxScanHosts < 1) return failure("maxScanHosts must be >= 1")

            val ip = toLong(localAddress)
            val mask = maskFor(prefixLength)
            val networkAddr = ip and mask
            val broadcastAddr = networkAddr or (mask.inv() and MAX_IPV4)

            // Shrink to a block that fits the host limit (never beyond /31).
            var scanPrefix = prefixLength
            while (scanPrefix < 31 && usableHostCount(scanPrefix) > maxScanHosts) scanPrefix++

            val scanMask = maskFor(scanPrefix)
            val scanNetwork = ip and scanMask
            val scanBroadcast = scanNetwork or (scanMask.inv() and MAX_IPV4)
            // /31 (RFC 3021) and /32 have no network/broadcast addresses to skip.
            val first = if (scanPrefix <= 30) scanNetwork + 1 else scanNetwork
            val last = if (scanPrefix <= 30) scanBroadcast - 1 else scanBroadcast

            val hosts = (first..last).filter { it != ip }.map { toIpString(it) }

            return Result.success(
                NetworkInfo(
                    network = network,
                    localAddress = toIpString(ip),
                    prefixLength = prefixLength,
                    subnetMask = toIpString(mask),
                    networkAddress = toIpString(networkAddr),
                    broadcastAddress = if (prefixLength <= 30) toIpString(broadcastAddr) else null,
                    scanPrefixLength = scanPrefix,
                    hostAddresses = hosts
                )
            )
        }

        private const val MAX_IPV4 = 0xFFFFFFFFL

        private fun maskFor(prefix: Int): Long =
            if (prefix == 0) 0L else (MAX_IPV4 shl (32 - prefix)) and MAX_IPV4

        private fun usableHostCount(prefix: Int): Long = when {
            prefix <= 30 -> (1L shl (32 - prefix)) - 2
            prefix == 31 -> 2L
            else -> 1L
        }

        private fun toLong(address: Inet4Address): Long =
            address.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }

        private fun toIpString(v: Long): String =
            "${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"
    }
}