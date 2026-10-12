package com.example.sample.network

import android.net.Network
import android.system.ErrnoException
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * TCP connect scanner for a single host. TCP only; UDP is deliberately not handled.
 *
 * Only scan devices you own or are authorized to assess.
 *
 * Results are emitted as each port finishes, so order is not guaranteed; [scanAll] returns them
 * sorted by port. Cancelling the collector closes in-flight sockets immediately.
 */
class PortScanner(private val config: PortScanConfig = PortScanConfig()) {

    /**
     * @param ipAddress IPv4 literal of the target.
     * @param ports ports to check (default: [PortScanConfig.ports]); duplicates are ignored.
     * @param network optional Wi-Fi network to bind sockets to (use [NetworkInfo.network]).
     */
    fun scan(
        ipAddress: String,
        ports: Collection<Int> = config.ports,
        network: Network? = null
    ): Flow<PortScanResult> {
        require(isValidIpv4Literal(ipAddress)) { "Not a valid IPv4 address: $ipAddress" }
        val targetPorts = ports.distinct()
        require(targetPorts.all { it in 1..65535 }) { "Ports must be in 1..65535" }

        return channelFlow {
            val semaphore = Semaphore(config.maxConcurrentConnections)
            for (port in targetPorts) {
                launch {
                    val result = semaphore.withPermit {
                        tcpProbe(ipAddress, port, config.connectTimeoutMs, network)
                    }
                    send(result)
                }
            }
        }.flowOn(Dispatchers.IO)
    }

    /** Convenience wrapper that collects all results, sorted by port. */
    suspend fun scanAll(
        ipAddress: String,
        ports: Collection<Int> = config.ports,
        network: Network? = null
    ): List<PortScanResult> = scan(ipAddress, ports, network).toList().sortedBy { it.port }
}

/**
 * Single bounded TCP connect attempt shared by [PortScanner] and [LanDeviceScanner].
 * Socket.connect() is blocking and not interruptible, so a child coroutine closes the socket on
 * cancellation, which aborts the connect immediately instead of waiting for the timeout.
 */
internal suspend fun tcpProbe(
    host: String,
    port: Int,
    timeoutMs: Int,
    network: Network?
): PortScanResult = withContext(Dispatchers.IO) {
    val socket = Socket()
    val startNs = System.nanoTime()
    coroutineScope {
        val closer = launch {
            try {
                awaitCancellation()
            } finally {
                runCatching { socket.close() }
            }
        }
        val (state, error) = try {
            ensureActive()
            network?.bindSocket(socket)
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            PortState.OPEN to null
        } catch (e: SocketTimeoutException) {
            PortState.TIMED_OUT to null
        } catch (e: IOException) {
            val s = classifyFailure(e)
            s to (if (s == PortState.ERROR) e.message else null)
        } finally {
            closer.cancel()
            runCatching { socket.close() }
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
        ensureActive() // if we were cancelled, surface CancellationException, not a bogus result
        PortScanResult(
            ipAddress = host,
            port = port,
            state = state,
            responseTimeMs = elapsedMs,
            errorMessage = error
        )
    }
}

private fun classifyFailure(e: IOException): PortState {
    val errno = (e.cause as? ErrnoException)?.errno
    val msg = e.message.orEmpty()
    return when {
        errno == OsConstants.ECONNREFUSED ||
                msg.contains("ECONNREFUSED") ||
                msg.contains("refused", ignoreCase = true) -> PortState.CLOSED
        errno == OsConstants.ETIMEDOUT || msg.contains("ETIMEDOUT") -> PortState.TIMED_OUT
        else -> PortState.ERROR
    }
}

internal fun isValidIpv4Literal(value: String): Boolean {
    val parts = value.split('.')
    return parts.size == 4 && parts.all { p ->
        p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() in 0..255
    }
}