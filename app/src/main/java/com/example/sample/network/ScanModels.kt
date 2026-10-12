package com.example.sample.network

import android.net.Network

/** Default TCP ports for [PortScanConfig]. TCP only; UDP is intentionally not supported. */
val DEFAULT_COMMON_TCP_PORTS: List<Int> =
    listOf(22, 53, 80, 443, 445, 554, 631, 3389, 8080, 8443)

/** Default TCP ports used to decide whether a host is alive during discovery. */
val DEFAULT_DISCOVERY_PROBE_PORTS: List<Int> =
    listOf(80, 443, 22, 445, 8080, 62078)

/** Default DNS-SD service types queried when NSD is enabled. */
val DEFAULT_NSD_SERVICE_TYPES: List<String> = listOf(
    "_http._tcp.", "_https._tcp.", "_ssh._tcp.", "_smb._tcp.",
    "_ipp._tcp.", "_airplay._tcp.", "_googlecast._tcp.", "_workstation._tcp."
)

enum class TransportProtocol { TCP }

enum class PortState {
    /** The TCP handshake completed. */
    OPEN,

    /** The host actively refused the connection (RST). The host is alive but nothing listens. */
    CLOSED,

    /** No answer within the timeout. Filtered by a firewall, host down, or slow. */
    TIMED_OUT,

    /** Other failure: no route to host, permission denied, network error. */
    ERROR
}

enum class DiscoverySource { TCP_PROBE, NSD }

/** Thrown (wrapped in [Result]) when no usable Wi-Fi IPv4 network information exists. */
class NetworkUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * @property network Android [Network] handle used to bind sockets to Wi-Fi (null in unit tests).
 * @property prefixLength Real prefix length reported by LinkProperties.
 * @property scanPrefixLength Prefix actually enumerated; larger than [prefixLength]
 *           when the real subnet exceeded the configured host limit.
 * @property hostAddresses Usable host IPs in the scan range, excluding the local device.
 */
data class NetworkInfo(
    val network: Network?,
    val localAddress: String,
    val prefixLength: Int,
    val subnetMask: String,
    val networkAddress: String,
    val broadcastAddress: String?,
    val scanPrefixLength: Int,
    val hostAddresses: List<String>
) {
    val isScanRangeLimited: Boolean get() = scanPrefixLength > prefixLength
}

data class DiscoveredDevice(
    val ipAddress: String,
    val hostname: String? = null,
    val macAddress: String? = null,
    val source: DiscoverySource,
    val probePort: Int? = null,
    val probeState: PortState? = null,
    val responseTimeMs: Long? = null,
    val advertisedServices: Set<String> = emptySet()
)

data class PortScanResult(
    val ipAddress: String,
    val port: Int,
    val protocol: TransportProtocol = TransportProtocol.TCP,
    val state: PortState,
    val responseTimeMs: Long,
    val errorMessage: String? = null
)

data class LanScanConfig(
    val probePorts: List<Int> = DEFAULT_DISCOVERY_PROBE_PORTS,
    val connectTimeoutMs: Int = 400,
    val maxConcurrentProbes: Int = 64,
    val resolveHostnames: Boolean = true,
    val enableNsd: Boolean = true,
    val nsdServiceTypes: List<String> = DEFAULT_NSD_SERVICE_TYPES,
    val nsdDurationMs: Long = 5_000L
) {
    init {
        require(probePorts.isNotEmpty() && probePorts.all { it in 1..65535 }) { "Invalid probe ports" }
        require(connectTimeoutMs > 0) { "connectTimeoutMs must be > 0" }
        require(maxConcurrentProbes > 0) { "maxConcurrentProbes must be > 0" }
        require(nsdDurationMs > 0) { "nsdDurationMs must be > 0" }
    }
}

data class PortScanConfig(
    val ports: List<Int> = DEFAULT_COMMON_TCP_PORTS,
    val connectTimeoutMs: Int = 1_000,
    val maxConcurrentConnections: Int = 50
) {
    init {
        require(ports.all { it in 1..65535 }) { "Ports must be in 1..65535" }
        require(connectTimeoutMs > 0) { "connectTimeoutMs must be > 0" }
        require(maxConcurrentConnections > 0) { "maxConcurrentConnections must be > 0" }
    }
}

data class DeviceUiState(
    val device: DiscoveredDevice,
    val openPorts: List<Int> = emptyList(),
    val isScanningPorts: Boolean = true
)

data class NetworkScanUiState(
    val isScanning: Boolean = false,
    val devices: List<DeviceUiState> = emptyList(),
    val errorMessage: String? = null
)
