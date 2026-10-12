package com.example.sample.network

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

/**
 * Discovers devices on the local IPv4 subnet.
 *
 * Emits discovered devices and resolves metadata (hostname, MAC address) using multiple protocols:
 * 1. mDNS / DNS-SD (NSD) TXT records & service attributes
 * 2. NetBIOS Node Status queries over UDP 137 (resolves Windows, Samba, NAS, TV, printer names & MACs)
 * 3. Kernel ARP table (/proc/net/arp) & 'ip neighbor' system queries
 * 4. Reverse DNS resolution
 *
 * NOTE: On non-rooted Android 10+ (API 29+), OS security policies restrict access to /proc/net/arp.
 * For devices that do not expose mDNS or NetBIOS, MAC address access is restricted by Android OS.
 */
class LanDeviceScanner(
    context: Context,
    private val config: LanScanConfig = LanScanConfig()
) {
    private val appContext = context.applicationContext

    /** Emits devices as they are found. Cancel the collector to stop the scan. */
    fun scan(networkInfo: NetworkInfo): Flow<DiscoveredDevice> = channelFlow {
        val seen = ConcurrentHashMap<String, DiscoveredDevice>()
        val semaphore = Semaphore(config.maxConcurrentProbes)

        suspend fun publish(device: DiscoveredDevice) {
            val (resolvedHostname, resolvedMac) = resolveDeviceDetails(
                ip = device.ipAddress,
                currentHostname = device.hostname,
                currentMac = device.macAddress,
                advertisedServices = device.advertisedServices
            )

            val enrichedDevice = device.copy(
                hostname = resolvedHostname,
                macAddress = resolvedMac
            )

            var shouldSend = false
            seen.compute(device.ipAddress) { _, existing ->
                if (existing == null) {
                    shouldSend = true
                    enrichedDevice
                } else {
                    val mergedServices = existing.advertisedServices + enrichedDevice.advertisedServices
                    val mergedHostname = enrichedDevice.hostname ?: existing.hostname
                    val mergedMac = enrichedDevice.macAddress ?: existing.macAddress
                    val merged = existing.copy(
                        hostname = mergedHostname,
                        macAddress = mergedMac,
                        advertisedServices = mergedServices
                    )
                    if (merged != existing) {
                        shouldSend = true
                    }
                    merged
                }
            }

            if (shouldSend) {
                seen[device.ipAddress]?.let { send(it) }
            }
        }

        if (config.enableNsd) {
            launch { nsdDevices(networkInfo).collect { publish(it) } }
        }
        for (ip in networkInfo.hostAddresses) {
            launch { probeHost(ip, networkInfo.network, semaphore)?.let { publish(it) } }
        }
    }.flowOn(Dispatchers.IO)

    /** Convenience wrapper: collects everything into a list sorted by IP and emits as a flow. */
    fun scanToList(networkInfo: NetworkInfo): Flow<List<DiscoveredDevice>> = flow {
        val list = mutableListOf<DiscoveredDevice>()
        scan(networkInfo).collect { device ->
            val existingIndex = list.indexOfFirst { it.ipAddress == device.ipAddress }
            if (existingIndex != -1) {
                list[existingIndex] = device
            } else {
                list.add(device)
            }
            emit(list.sortedBy { d ->
                d.ipAddress.split('.').fold(0L) { acc, s -> acc * 256 + s.toLong() }
            })
        }
    }

    private suspend fun probeHost(
        ip: String,
        network: Network?,
        semaphore: Semaphore
    ): DiscoveredDevice? {
        for (port in config.probePorts) {
            val r = semaphore.withPermit { tcpProbe(ip, port, config.connectTimeoutMs, network) }
            when (r.state) {
                PortState.OPEN, PortState.CLOSED -> return DiscoveredDevice(
                    ipAddress = ip,
                    source = DiscoverySource.TCP_PROBE,
                    probePort = port,
                    probeState = r.state,
                    responseTimeMs = r.responseTimeMs
                )
                PortState.ERROR -> return null // e.g. no route to host: skip remaining ports
                PortState.TIMED_OUT -> Unit // maybe filtered; try the next port
            }
        }
        return null
    }

    private suspend fun resolveDeviceDetails(
        ip: String,
        currentHostname: String?,
        currentMac: String?,
        advertisedServices: Set<String>
    ): Pair<String?, String?> = withContext(Dispatchers.IO) {
        var hostname = currentHostname
        var mac = currentMac

        if (hostname == null || mac == null) {
            queryNetBios(ip)?.let { (name, nbMac) ->
                hostname = hostname ?: name
                mac = mac ?: nbMac
            }
        }

        if (hostname == null) hostname = queryDnsReverse(ip = ip,port=  5353)   // macOS, Linux, printers, IoT
        if (hostname == null) hostname = queryDnsReverse(ip = ip, port = 5355)   // Windows (LLMNR)
        if (hostname == null && config.resolveHostnames) {
            hostname = reverseLookup(ip) ?: extractNameFromServices(advertisedServices)
        }
        hostname to mac
    }

    private fun queryDnsReverse(ip: String, port: Int,timeoutMs: Int = 500): String? {
        val octets = ip.split('.')
        if (octets.size != 4) return null
        val qname = octets.reversed().joinToString(".") + ".in-addr.arpa"

        val out = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(out)
        dos.writeShort(0)      // id
        dos.writeShort(0)      // flags: standard query
        dos.writeShort(1)      // questions
        dos.writeShort(0); dos.writeShort(0); dos.writeShort(0)
        qname.split('.').forEach { label ->
            dos.writeByte(label.length)
            dos.write(label.toByteArray(Charsets.US_ASCII))
        }
        dos.writeByte(0)
        dos.writeShort(12)     // type PTR
        dos.writeShort(1)      // class IN
        val request = out.toByteArray()

        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                // Source port != 5353, so responders reply by unicast (RFC 6762 6.7)
                socket.send(DatagramPacket(request, request.size, InetAddress.getByName(ip), port))
                val buf = ByteArray(1500)
                val resp = DatagramPacket(buf, buf.size)
                socket.receive(resp)

                val d = resp.data
                val answers = ((d[6].toInt() and 0xFF) shl 8) or (d[7].toInt() and 0xFF)
                if (answers == 0) return null
                var p = readDnsName(d, 12).second + 4          // skip question
                p = readDnsName(d, p).second + 10              // skip answer name, type, class, ttl, rdlen
                readDnsName(d, p).first.removeSuffix(".local").takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Returns the decoded name and the offset just after it in the original position. */
    private fun readDnsName(d: ByteArray, start: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var pos = start
        var end = -1
        var hops = 0
        while (true) {
            val len = d[pos].toInt() and 0xFF
            if (len == 0) { if (end < 0) end = pos + 1; break }
            if (len and 0xC0 == 0xC0) {                        // compression pointer
                if (end < 0) end = pos + 2
                pos = ((len and 0x3F) shl 8) or (d[pos + 1].toInt() and 0xFF)
                if (++hops > 10) break
            } else {
                sb.append(String(d, pos + 1, len, Charsets.UTF_8)).append('.')
                pos += len + 1
            }
        }
        return sb.toString().trimEnd('.') to end
    }

    private fun queryNetBios(ip: String, timeoutMs: Int = 400): Pair<String?, String?>? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            socket.soTimeout = timeoutMs
            val address = InetAddress.getByName(ip)

            // NetBIOS NBSTAT (Node Status) query packet for '*'
            val requestData = byteArrayOf(
                0x80.toByte(), 0x94.toByte(), // Transaction ID
                0x00, 0x00, // Flags: Query
                0x00, 0x01, // Questions: 1
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x20, // Name length: 32
                0x43, 0x4B, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
                0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
                0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
                0x41, 0x41, 0x00,
                0x00, 0x21, // Type: NBSTAT
                0x00, 0x01  // Class: IN
            )

            val packet = DatagramPacket(requestData, requestData.size, address, 137)
            socket.send(packet)

            val buffer = ByteArray(1024)
            val receivePacket = DatagramPacket(buffer, buffer.size)
            socket.receive(receivePacket)

            val data = receivePacket.data
            val len = receivePacket.length
            if (len < 50) return null

            var ptr = 12
            while (ptr < len - 4) {
                if (data[ptr] == 0x00.toByte() && data[ptr + 1] == 0x21.toByte()) {
                    ptr += 4
                    break
                }
                ptr++
            }
            if (ptr + 6 >= len) return null
            ptr += 6

            val numNames = data[ptr].toInt() and 0xFF
            ptr += 1

            var hostname: String? = null
            if (numNames > 0 && ptr + 18 <= len) {
                val nameBytes = ByteArray(15)
                System.arraycopy(data, ptr, nameBytes, 0, 15)
                val rawName = String(nameBytes, Charsets.US_ASCII).trim()
                if (rawName.isNotBlank() && rawName.all { it.code in 32..126 }) {
                    hostname = rawName
                }
            }

            ptr += numNames * 18
            var mac: String? = null
            if (ptr + 6 <= len) {
                val macBytes = ByteArray(6)
                System.arraycopy(data, ptr, macBytes, 0, 6)
                val macStr = macBytes.joinToString(":") { "%02X".format(it) }
                if (macStr != "00:00:00:00:00:00" && macStr != "FF:FF:FF:FF:FF:FF") {
                    mac = macStr
                }
            }

            if (hostname != null || mac != null) Pair(hostname, mac) else null
        } catch (e: Exception) {
            null
        } finally {
            socket?.close()
        }
    }

    private fun triggerArpRequest(ip: String) {
        try {
            val socket = DatagramSocket()
            socket.soTimeout = 100
            val packet = DatagramPacket(byteArrayOf(0), 1, InetAddress.getByName(ip), 7)
            socket.send(packet)
            socket.close()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun getMacFromArpTable(ipAddress: String): String? {
        return try {
            val file = File("/proc/net/arp")
            if (!file.exists() || !file.canRead()) return null
            file.bufferedReader().useLines { lines ->
                lines.drop(1).forEach { line ->
                    val tokens = line.split("\\s+".toRegex())
                    if (tokens.size >= 4 && tokens[0] == ipAddress) {
                        val mac = tokens[3]
                        if (mac != "00:00:00:00:00:00" && isValidMac(mac)) {
                            return mac.uppercase()
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun getMacFromIpNeighbor(ipAddress: String): String? {
        val commands = listOf(
            arrayOf("ip", "neigh", "show", ipAddress),
            arrayOf("sh", "-c", "ip neigh | grep $ipAddress"),
            arrayOf("sh", "-c", "cat /proc/net/arp | grep $ipAddress")
        )
        for (cmd in commands) {
            try {
                val process = Runtime.getRuntime().exec(cmd)
                val reader = process.inputStream.bufferedReader()
                val output = reader.readText()
                process.waitFor()
                val parts = output.split("\\s+".toRegex())
                val lladdrIndex = parts.indexOf("lladdr")
                if (lladdrIndex != -1 && lladdrIndex + 1 < parts.size) {
                    val mac = parts[lladdrIndex + 1]
                    if (isValidMac(mac)) return mac.uppercase()
                }
                if (parts.size >= 4 && isValidMac(parts[3])) {
                    return parts[3].uppercase()
                }
            } catch (e: Exception) {
                // ignore
            }
        }
        return null
    }

    private fun isValidMac(mac: String): Boolean {
        return mac.matches(Regex("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$"))
    }

    private suspend fun reverseLookup(ip: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val inet = InetAddress.getByName(ip)
            val canonical = inet.canonicalHostName
            if (!canonical.isNullOrBlank() && canonical != ip) {
                canonical
            } else {
                val hostName = inet.hostName
                if (!hostName.isNullOrBlank() && hostName != ip) hostName else null
            }
        }.getOrNull()
    }

    private fun extractNameFromServices(services: Set<String>): String? {
        val first = services.firstOrNull() ?: return null
        return first.substringBefore(" (").takeIf { it.isNotBlank() }
    }

    private fun extractMacFromNsd(serviceInfo: NsdServiceInfo): String? {
        val serviceName = serviceInfo.serviceName
        if (serviceName.contains("@")) {
            val possibleMac = serviceName.substringBefore("@").replace("-", "").replace(":", "")
            if (possibleMac.length == 12 && possibleMac.all { it.isDigit() || it in 'A'..'F' || it in 'a'..'f' }) {
                return possibleMac.chunked(2).joinToString(":").uppercase()
            }
        }

        val attributes = serviceInfo.attributes ?: return null
        val macKeys = listOf("deviceid", "mac", "macaddress", "mac_address", "id", "serialnumber")
        for (key in macKeys) {
            val bytes = attributes[key] ?: attributes[key.uppercase()] ?: continue
            val str = String(bytes, Charsets.UTF_8).trim()
            val cleaned = str.replace("-", "").replace(":", "")
            if (cleaned.length == 12 && cleaned.all { it.isDigit() || it in 'A'..'F' || it in 'a'..'f' }) {
                return cleaned.chunked(2).joinToString(":").uppercase()
            } else if (isValidMac(str)) {
                return str.uppercase()
            }
        }
        return null
    }

    // ---------------------------------------------------------------- NSD (mDNS / DNS-SD)

    private fun nsdDevices(info: NetworkInfo): Flow<DiscoveredDevice> = callbackFlow {
        val nsd = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        val listeners = mutableListOf<NsdManager.DiscoveryListener>()
        val resolveLock = Mutex()
        val allowed = info.hostAddresses.toHashSet()

        if (nsd == null) {
            close()
        } else {
            for (type in config.nsdServiceTypes) {
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) = Unit
                    override fun onDiscoveryStopped(serviceType: String) = Unit
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                    override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        launch(Dispatchers.IO) {
                            val resolved = resolveService(nsd, serviceInfo, resolveLock)
                                ?: return@launch
                            for (addr in resolved.ipv4Addresses()) {
                                val ip = addr.hostAddress ?: continue
                                if (ip !in allowed) continue // ignore off-subnet / self addresses
                                val deviceName = serviceInfo.serviceName.takeIf { it.isNotBlank() }
                                    ?: resolved.host?.hostName?.takeIf { it != ip }
                                val nsdMac = extractMacFromNsd(resolved)
                                trySend(
                                    DiscoveredDevice(
                                        ipAddress = ip,
                                        hostname = deviceName,
                                        macAddress = nsdMac,
                                        source = DiscoverySource.NSD,
                                        advertisedServices = setOf("${serviceInfo.serviceName} ($type)")
                                    )
                                )
                            }
                        }
                    }
                }
                try {
                    nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                    listeners += listener
                } catch (e: Exception) {
                    // Invalid type or NSD unavailable: skip this service type.
                }
            }
            launch {
                delay(config.nsdDurationMs.milliseconds)
                close()
            }
        }
        awaitClose {
            listeners.forEach { runCatching { nsd?.stopServiceDiscovery(it) } }
        }
    }

    private suspend fun resolveService(
        nsd: NsdManager,
        service: NsdServiceInfo,
        legacyLock: Mutex
    ): NsdServiceInfo? =
        if (Build.VERSION.SDK_INT >= 34) {
            resolveModern(nsd, service)
        } else {
            // Before API 34 only one resolveService call may be active at a time.
            legacyLock.withLock { resolveLegacy(nsd, service) }
        }

    @RequiresApi(34)
    private suspend fun resolveModern(nsd: NsdManager, service: NsdServiceInfo): NsdServiceInfo? {
        val result = CompletableDeferred<NsdServiceInfo?>()
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                result.complete(null)
            }
            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.hostAddresses.isNotEmpty()) result.complete(serviceInfo)
            }
            override fun onServiceLost() { result.complete(null) }
            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        return try {
            nsd.registerServiceInfoCallback(service, { it.run() }, callback)
            withTimeoutOrNull(RESOLVE_TIMEOUT_MS.milliseconds) { result.await() }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { nsd.unregisterServiceInfoCallback(callback) }
        }
    }

    @Suppress("DEPRECATION") // resolveService is deprecated in API 34; only used below API 34.
    private suspend fun resolveLegacy(nsd: NsdManager, service: NsdServiceInfo): NsdServiceInfo? =
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS.milliseconds) {
            suspendCancellableCoroutine { cont ->
                try {
                    nsd.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            if (cont.isActive) cont.resume(serviceInfo)
                        }
                    })
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    @Suppress("DEPRECATION") // NsdServiceInfo.host is deprecated in API 34; fallback below 34.
    private fun NsdServiceInfo.ipv4Addresses(): List<Inet4Address> =
        if (Build.VERSION.SDK_INT >= 34) {
            hostAddresses.filterIsInstance<Inet4Address>()
        } else {
            listOfNotNull(host as? Inet4Address)
        }

    private companion object {
        const val RESOLVE_TIMEOUT_MS = 3_000L
    }
}