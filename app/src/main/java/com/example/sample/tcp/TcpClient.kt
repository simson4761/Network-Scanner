package com.example.sample.tcp

import android.util.Log
import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import com.example.sample.data.TcpConnectionState
import com.example.sample.data.hl7.Hl7Message
import com.example.sample.data.ozelle.EhomeOrder
import com.example.sample.data.ozelle.EhomeTest
import com.example.sample.utils.EhomeControlIds
import com.example.sample.utils.HL7Orders
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** Where an order is on the analyser: ORC-5 of OSU / OSR plus a readable label. */
data class AnalyserOrderStatus(val orderNo: String, val code: String, val label: String)

/** Outcome of [TcpClient.fetchReportOutcome]. */
sealed class FetchOutcome {
    /** The report came back and was stored. */
    object Stored : FetchOutcome()

    /** The analyser does not know the order (only orders pushed by the LIS can be fetched). */
    data class NotFound(val reason: String?) : FetchOutcome()

    /** The analyser did not answer: the sample has not been run yet. */
    object NoReport : FetchOutcome()

    /** A reply came but could not be stored. */
    data class Failed(val reason: String) : FetchOutcome()
}

/** Outcome of [TcpClient.fetchAllResults]. */
data class FetchSummary(val stored: Int, val notRunYet: Int, val notOnAnalyser: Int, val failed: Int)

/**
 * CLIENT mode for the EHOME analyser (native protocol, TCP + MLLP).
 *
 * Link: after connecting it reads the device identity (EQ01), the tests it offers (EQ03) and the
 * running info (EQ02). Only then is the link CONNECTED. Every [KEEP_ALIVE_MS] it sends the
 * plain-text heartbeat "H:Ping" and an EQ02 query; the query doubles as the dead-link check.
 *
 * Orders ([sendOrder]): queued in memory. While the link is up, each queued order is checked against
 * the offered tests and pushed (ORM^O01), one at a time, and the analyser's MSA decides the outcome,
 * reported through [onOrderPushed]. An order that finds the link down stays queued for the next
 * connect. Calling [sendOrder] again for a rejected or unconfirmed order retries it.
 *
 * After a push: on every connect the status of accepted orders is queried; OSU notices from the
 * analyser are handled; when an order completes (CM) its report is fetched and delivered like any
 * result. Reconnects automatically (1s, 2s, 4s ... up to 30s).
 */
class TcpClient(
    private val context: Context,
    private val host: String,
    private val port: Int,
    onError: (Throwable) -> Unit = {},
    /** Called after each handshake with the tests the analyser offers (empty if none reported). */
    private val onTests: (List<EhomeTest>) -> Unit = {},
    /** Called when the analyser deletes an order on its own screen. */
    private val onOrderDeleted: (orderNo: String) -> Unit = {},
    /** Outcome of a push: accepted, or the reason it was not (rejected, test not offered, no reply). */
    private val onOrderPushed: (orderNo: String, accepted: Boolean, reason: String?) -> Unit = { _, _, _ -> },
    /** Status the analyser reports for one of our orders. */
    private val onOrderStatus: (AnalyserOrderStatus) -> Unit = {},
    onResult: suspend (Hl7Message) -> Unit
) : BaseTcpLink("TcpClient", onError, onResult) {

    private class NotConnectedException : IOException("Analyser not connected")

    @Volatile private var socket: Socket? = null

    /** True once the handshake is done. */
    @Volatile private var ready = false

    /** Tests the analyser offered at the last handshake. */
    @Volatile private var offered: List<EhomeTest> = emptyList()


    @Volatile private var udp: DatagramSocket? = null

    /** "ip:port" of the analyser we are connected or connecting to. */
    @Volatile private var peer = ""

    private val ids = EhomeControlIds()

    /** Request waiters, keyed by our control ID (= MSA-2 of the reply). */
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()

    /** Every order handed to [sendOrder], by order number (needed to cancel). */
    private val orders = ConcurrentHashMap<String, EhomeOrder>()

    /** Orders waiting to be pushed. */
    private val unsent = MutableStateFlow<List<EhomeOrder>>(emptyList())

    /** Order numbers that are queued, being pushed, or accepted. Stops the same order going out twice. */
    private val known = ConcurrentHashMap.newKeySet<String>()

    /** Accepted by the analyser and not completed yet. Their status is queried after a reconnect. */
    private val pushedOrders = ConcurrentHashMap.newKeySet<String>()

    /** Orders whose status turned CM; their report is fetched outside the read loop. */
    private val completed = Channel<String>(Channel.UNLIMITED)


    override fun closeSockets() {
        runCatching { socket?.close() }   // unblocks the pending read
        runCatching { udp?.close() }      // unblocks discovery
    }

    override fun onPong() {
        Log.d(TAG, "[RX] Heartbeat PONG received")
    }

    // ---------- connection loop ----------

    override suspend fun runLoop(): Unit = coroutineScope {
        val fixedHost = host?.trim()?.ifEmpty { null }
        val announced = MutableStateFlow<Pair<String, Int>?>(null)
        if (fixedHost == null) launch { discover(announced) }

        while (isActive) {
            val (ip, p) = if (fixedHost != null) {
                fixedHost to port
            } else {
                if (announced.value == null) {
                    Log.d(TAG, "No analyser IP set: waiting for its UDP announcement on port $DISCOVERY_PORT...")
                    _state.value = TcpConnectionState.CONNECTING
                }
                announced.filterNotNull().first()
            }
            peer = "$ip:$p"
            try {
                Log.d(TAG, "Connecting to $peer...")
                _state.value = TcpConnectionState.CONNECTING
                Socket().use { s ->
                    s.keepAlive = true
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(ip, p), 5_000)
                    socket = s
                    Log.i(TAG, "TCP connected to $peer (local port ${s.localPort}), starting handshake")
                    coroutineScope {
                        val linkJob = launch {
                            handshakeAndKeepAlive()
                        }
                        try {
                            session(s)
                        } finally {
                            linkJob.cancel()
                            failPending()
                        }
                    }
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "Connection loop cancelled")
                throw e
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Error in connection/session for $peer: ${e.localizedMessage}", e)
                    onError(e)
                }
            } finally {
                socket = null
                ready = false
                _state.value = TcpConnectionState.DISCONNECTED
                Log.d(TAG, "Disconnected from $peer")
            }
            if (isActive) waitBackoff("Reconnecting")
        }
    }

    /** Handshake, then the order pusher and the keep-alive. On any failure the socket is closed so the loop reconnects. */
    private suspend fun handshakeAndKeepAlive() {
        try {
            coroutineScope {
                // session() sets `out` as soon as it starts reading.
                withTimeoutOrNull(2_000) { while (out == null) delay(20); true }
                    ?: throw IOException("Session did not start")

                val info = request { query("EHQ^EQ01", it) }
                val testsReply = request { query("EHQ^EQ03", it) }
                request { query("EHQ^EQ02", it) }

                val eqdi = segments(info, "EQDI").firstOrNull()
                val tests = parseTests(testsReply)
                offered = tests
                onTests(tests)

                ready = true
                _state.value = TcpConnectionState.CONNECTED
                Log.d(
                    TAG,
                    "Connected to ${eqdi?.getOrNull(1) ?: "analyser"} ${eqdi?.getOrNull(6).orEmpty()} " +
                            "(fw ${eqdi?.getOrNull(3) ?: "?"}) at $host:$port; " +
                            "tests: ${tests.joinToString { "${it.shortName} (${it.code})" }.ifEmpty { "none reported" }}"
                )

                launch { pushPending() }
                launch { for (orderNo in completed) fetchCompleted(orderNo) }

                while (true) {
                    delay(KEEP_ALIVE_MS)
                    val o = out ?: throw NotConnectedException()
                    Log.d(TAG, "[TX] Sending Raw Text: $PING")
                    writeRaw(o, PING)
                    request { query("EHQ^EQ02", it) }   // no reply within the timeout = dead link
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Handshake/keep-alive failed: ${e.localizedMessage}", e)
            onError(e)
            closeSockets()   // ends session(); runLoop reconnects with backoff
        } finally {
            ready = false
        }
    }

    // ---------- request / reply ----------

    /** Sends one request and waits for the reply whose MSA-2 equals our control ID. */
    private suspend fun request(build: (controlId: String) -> String): String {
        val o = out ?: throw NotConnectedException()
        val id = ids.next()
        val waiter = CompletableDeferred<String>()
        pending[id] = waiter
        try {
            val payload = build(id)
            Log.d(TAG, "[TX] Sending Payload:\n${payload.replace('\r', '\n')}")
            writeFrame(o, payload)
            return withTimeoutOrNull(REQUEST_TIMEOUT_MS) { waiter.await() }
                ?: throw SocketTimeoutException(
                    "No reply from the analyser within ${REQUEST_TIMEOUT_MS / 1000} s (it may still have accepted the request)"
                )
        } finally {
            pending.remove(id)
        }
    }

    private fun failPending() {
        pending.values.forEach { it.completeExceptionally(IOException("Connection lost before the analyser replied")) }
    }

    /** Runs a request-style call on IO, only while the link is up. Failures come back as a Result with a readable message. */
    private suspend fun <T> onAnalyser(block: suspend () -> T): Result<T> {
        if (!ready) return Result.failure(IllegalStateException("The analyser is not connected."))
        return try {
            Result.success(withContext(Dispatchers.IO) { block() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(IllegalStateException(e.message ?: "Could not reach the analyser.", e))
        }
    }

    // ---------- pushing orders ----------

    /**
     * Queues the order; it is pushed as soon as the link is ready (immediately if it already is).
     * Success means queued: the outcome arrives through [onOrderPushed]. An order number that is
     * already queued or accepted is ignored. After a rejected or unconfirmed push, call this again to retry.
     */
    override suspend fun sendOrder(order: EhomeOrder): Result<Unit> {
        if (order.orderNo.isBlank()) return Result.failure(IllegalArgumentException("Order number is required"))
        orders[order.orderNo] = order
        if (known.add(order.orderNo)) unsent.update { it + order }
        return Result.success(Unit)
    }

    /** While the link is up: catch up on status changes, then push every queued order. */
    private suspend fun pushPending() {
        for (orderNo in pushedOrders.toList()) {
            try {
                queryStatus(orderNo)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                break   // link trouble: the reconnect starts this over
            }
        }
        unsent.collect { queued ->
            for (order in queued) {
                if (!ready) break
                push(order)
            }
        }
    }

    private suspend fun push(order: EhomeOrder) {
        val tests = resolveTests(order).getOrElse {
            finishPush(order, false, it.message)
            return
        }
        try {
            val toSend = order.copy(tests = tests)
            val reply = request { id -> HL7Orders.buildEhomeOrder(order = toSend, controlId = id) }
            val msa = segments(reply, "MSA").firstOrNull()
            if (msa?.getOrNull(1) == "AA") {
                Log.i(TAG, "Order ${order.orderNo} accepted by the analyser")
                finishPush(order, true, msa.getOrNull(3))
            } else {
                finishPush(order, false, "${msa?.getOrNull(1) ?: "?"} ${msa?.getOrNull(3).orEmpty()}".trim())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: NotConnectedException) {
            // Link dropped before sending: the order stays queued and goes out on the next connect.
        } catch (e: Exception) {
            Log.e(TAG, "Pushing order ${order.orderNo} failed", e)
            finishPush(order, false, "Not confirmed: ${e.message}")
        }
    }

    /** The analyser tests for an order: none given = the first offered test; null-safe match by code, short name or name. */
    private fun resolveTests(order: EhomeOrder): Result<List<EhomeTest>> {
        if (order.tests.isEmpty()) {
            val first = offered.firstOrNull()
                ?: return Result.failure(IllegalStateException("The analyser offers no tests"))
            return Result.success(listOf(first))
        }
        val resolved = order.tests.map { t ->
            offered.firstOrNull {
                it.code.equals(t.code, ignoreCase = true) ||
                        it.shortName.equals(t.shortName, ignoreCase = true) ||
                        it.name.equals(t.name, ignoreCase = true)
            } ?: return Result.failure(
                IllegalStateException(
                    "Analyser does not offer \"${t.shortName.ifBlank { t.code }}\" " +
                            "(offers: ${offered.joinToString { it.shortName }.ifEmpty { "nothing" }})"
                )
            )
        }
        return Result.success(resolved)
    }

    private fun finishPush(order: EhomeOrder, accepted: Boolean, reason: String?) {
        unsent.update { list -> list.filterNot { it.orderNo == order.orderNo } }
        if (accepted) pushedOrders.add(order.orderNo) else known.remove(order.orderNo)   // failed ones can be queued again
        onOrderPushed(order.orderNo, accepted, reason)
    }

    // ---------- cancel ----------

    /**
     * Removes an order. One that was never pushed is just dropped from the queue; an accepted one is
     * cancelled on the analyser (ORC CA) and succeeds only on MSA|AA.
     */
    suspend fun cancelOrder(orderNo: String): Result<Unit> {
        if (orderNo !in pushedOrders) {
            unsent.update { list -> list.filterNot { it.orderNo == orderNo } }
            known.remove(orderNo)
            orders.remove(orderNo)
            return Result.success(Unit)
        }
        val order = orders[orderNo]
        return onAnalyser {
            val tests = order?.let { resolveTests(it).getOrNull() } ?: offered
            val reply = request { id ->
                join(
                    msh("ORM^O01", id),
                    "ORC|CA|$orderNo|$orderNo||CA",
                    *tests.mapIndexed { i, t -> "OBR|${i + 1}|||${t.code}^${t.shortName}" }.toTypedArray(),
                )
            }
            val msa = segments(reply, "MSA").firstOrNull()
            if (msa?.getOrNull(1) != "AA") {
                throw IllegalStateException("Analyser refused: ${msa?.getOrNull(1) ?: "?"} ${msa?.getOrNull(3).orEmpty()}".trim())
            }
            pushedOrders.remove(orderNo)
            known.remove(orderNo)
            orders.remove(orderNo)
            Log.i(TAG, "Order $orderNo removed from the analyser")
        }
    }

    // ---------- order status ----------

    /** Asks where an order is on the analyser (OSQ^Q06). Null when the reply carries no status. */
    suspend fun queryOrderStatus(orderNo: String): Result<AnalyserOrderStatus?> = onAnalyser { queryStatus(orderNo) }

    /** Refreshes the status of every accepted order. Returns how many answered. */
    suspend fun refreshAllStatuses(): Result<Int> = onAnalyser {
        pushedOrders.toList().count { queryStatus(it) != null }
    }

    private suspend fun queryStatus(orderNo: String): AnalyserOrderStatus? {
        val reply = request { id ->
            join(msh("OSQ^Q06", id), "QRD|${LocalDateTime.now().format(TS)}|R||||||$orderNo")
        }
        return parseStatus(reply)?.also { applyStatus(it) }
    }

    /** ORC-2 / ORC-5 of an OSU or OSR message; null when there is no usable ORC. */
    private fun parseStatus(raw: String): AnalyserOrderStatus? {
        val orc = segments(raw, "ORC").firstOrNull() ?: return null
        val orderNo = orc.getOrNull(2)?.takeIf { it.isNotBlank() } ?: orc.getOrNull(3)?.takeIf { it.isNotBlank() } ?: return null
        val code = orc.getOrNull(5)?.takeIf { it.isNotBlank() }?.uppercase() ?: return null
        return AnalyserOrderStatus(orderNo, code, statusLabel(code))
    }

    /** Reports a status for one of our orders; a completed order gets its report fetched. */
    private fun applyStatus(status: AnalyserOrderStatus) {
        if (!orders.containsKey(status.orderNo)) return   // not ours
        Log.i(TAG, "Order ${status.orderNo}: ${status.label}")
        onOrderStatus(status)
        when {
            status.code == "CM" && pushedOrders.contains(status.orderNo) -> completed.trySend(status.orderNo)
            status.code == "CA" -> pushedOrders.remove(status.orderNo)
        }
    }

    private fun statusLabel(code: String): String = when (code) {
        "SC" -> "Test started on analyser"
        "IP" -> "Waiting on analyser"
        "CM" -> "Completed"
        "CA" -> "Cancelled on analyser"
        "ER" -> "Error on analyser"
        "HD" -> "On hold"
        else -> code
    }

    // ---------- reports ----------

    /**
     * Asks the analyser for an order's report (QRY^Q02) and delivers it through [onResult].
     * A report sent later as a separate ORU is handled by the normal result path.
     */
    suspend fun fetchReportOutcome(orderNo: String): Result<FetchOutcome> = onAnalyser {
        val reply = try {
            request { id -> join(msh("QRY^Q02", id), "QRD|${LocalDateTime.now().format(TS)}|R||||||$orderNo") }
        } catch (_: SocketTimeoutException) {
            return@onAnalyser FetchOutcome.NoReport
        }
        val msa = segments(reply, "MSA").firstOrNull()
        when {
            reply.contains("OBX|") -> {
                val stored = ingestResult(reply)
                // OpenDx acknowledges a fetched report (ORF^R04) with ACK^R04.
                if (stored) {
                    val o = out
                    if (o != null) runCatching {
                        writeFrame(o, join(msh("ACK^R04", ids.next()), "MSA|AA|${field(reply, "MSH", 9)}|||0"))
                    }
                    pushedOrders.remove(orderNo)
                    FetchOutcome.Stored
                } else {
                    FetchOutcome.Failed("The report could not be stored")
                }
            }
            msa != null && msa.getOrNull(1) != "AA" -> FetchOutcome.NotFound(msa.getOrNull(3))
            else -> FetchOutcome.Failed("The analyser replied without results")
        }
    }

    /** [AnalyserLink] entry point. Fails unless the report was stored; [controlId] is ignored (our own counter is used). */
    override suspend fun fetchReport(orderNo: String, controlId: String): Result<Unit> =
        fetchReportOutcome(orderNo).mapCatching { outcome ->
            when (outcome) {
                FetchOutcome.Stored -> Unit
                is FetchOutcome.NotFound -> throw IllegalStateException("The analyser does not have order $orderNo: ${outcome.reason.orEmpty()}")
                FetchOutcome.NoReport -> throw IllegalStateException("No report yet for $orderNo (the sample may not have been run)")
                is FetchOutcome.Failed -> throw IllegalStateException(outcome.reason)
            }
        }

    /** Asks for the report of every accepted order. */
    suspend fun fetchAllResults(): Result<FetchSummary> = onAnalyser {
        var stored = 0
        var notRun = 0
        var notFound = 0
        var failed = 0
        for (orderNo in pushedOrders.toList()) {
            when (fetchReportOutcome(orderNo).getOrNull()) {
                FetchOutcome.Stored -> stored++
                FetchOutcome.NoReport -> notRun++
                is FetchOutcome.NotFound -> notFound++
                else -> failed++
            }
        }
        FetchSummary(stored, notRun, notFound, failed)
    }

    private suspend fun fetchCompleted(orderNo: String) {
        fetchReportOutcome(orderNo)
    }

    // ---------- messages from the analyser ----------

    /** Sees every frame before the assembler. Returns true when the frame was fully handled here. */
    override suspend fun handleRaw(raw: String, out: OutputStream): Boolean {
        val lines = raw.split('\r', '\n').filter { it.isNotBlank() }
        val head = lines.firstOrNull { it.startsWith("MSH|") }?.split('|')
        val msa = lines.firstOrNull { it.startsWith("MSA|") }?.split('|')

        // A reply to one of our requests?
        if (msa != null) {
            val waiter = msa.getOrNull(2)?.let { pending[it] }
            if (waiter != null) waiter.complete(raw) else Log.d(TAG, "Late or unknown reply ignored (MSA-2=${msa.getOrNull(2)})")
            return true
        }

        val type = head?.getOrNull(8).orEmpty()
        val requestId = head?.getOrNull(9).orEmpty()
        return when {
            type.startsWith("ORM") -> {
                handleOrderDeleted(lines, requestId, out)
                true
            }
            type.startsWith("OSU") -> {
                Log.i(TAG, "Analyser sent $type")
                parseStatus(raw)?.let { applyStatus(it) }
                val ack = join(msh("ACK^${type.substringAfter('^', "")}", ids.next()), "MSA|AA|$requestId|||0")
                writeFrame(out, ack)
                true
            }
            else -> false   // ORU results and everything else: BaseTcpLink as before
        }
    }

    /** The order was deleted on the analyser's screen: confirm with ORR^O02 (like OpenDx) and report it. */
    private fun handleOrderDeleted(lines: List<String>, requestId: String, out: OutputStream) {
        val orc = lines.firstOrNull { it.startsWith("ORC|") }?.split('|')
        val orderNo = orc?.getOrNull(2)?.takeIf { it.isNotBlank() } ?: orc?.getOrNull(3)?.takeIf { it.isNotBlank() }
        Log.i(TAG, "Analyser deleted order ${orderNo ?: "?"}")
        val reply = join(
            msh("ORR^O02", ids.next()),
            "MSA|AA|$requestId|success||0",
            "ORC|OC|${orderNo.orEmpty()}|${orderNo.orEmpty()}||CA",
            *lines.filter { it.startsWith("OBR") }.toTypedArray(),
        )
        writeFrame(out, reply)
        orderNo?.let {
            pushedOrders.remove(it)
            known.remove(it)
            orders.remove(it)
            onOrderDeleted(it)
        }
    }

    // ---------- helpers ----------

    private fun msh(type: String, controlId: String): String =
        "MSH|^~\\&|HIS^1.0.2||||${LocalDateTime.now().format(TS)}||$type|$controlId|P|2.9.1^1.0.0"

    private fun query(type: String, controlId: String): String = join(msh(type, controlId))

    private fun join(vararg segments: String): String = segments.joinToString(separator = "\r", postfix = "\r")

    private fun segments(message: String, name: String): List<List<String>> =
        message.split('\r', '\n').filter { it.startsWith("$name|") }.map { it.split('|') }

    private fun field(message: String, segment: String, index: Int): String =
        segments(message, segment).firstOrNull()?.getOrNull(index).orEmpty()

    /** `EQCI|index|code|shortName|name` segments from the EQ03 reply. */
    private fun parseTests(message: String): List<EhomeTest> = segments(message, "EQCI").mapNotNull { f ->
        val code = f.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val short = f.getOrNull(3)?.takeIf { it.isNotBlank() } ?: code
        EhomeTest(code = code, shortName = short, name = f.getOrNull(4)?.takeIf { it.isNotBlank() } ?: short)
    }

    /** Listens for the analyser's UDP broadcast and keeps the latest announced IP and port in [announced]. */
    private suspend fun discover(announced: MutableStateFlow<Pair<String, Int>?>) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // Many phones drop Wi-Fi broadcast packets unless this lock is held.
        val lock = wifi.createMulticastLock("analyser-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            while (currentCoroutineContext().isActive) {
                try {
                    DatagramSocket(null).use { sock ->
                        udp = sock
                        sock.reuseAddress = true
                        sock.bind(InetSocketAddress(DISCOVERY_PORT))
                        sock.soTimeout = 2_000   // lets the loop notice cancellation
                        Log.d(TAG, "Listening for the analyser on UDP $DISCOVERY_PORT")
                        while (currentCoroutineContext().isActive) {
                            val packet = receiveOrNull(sock) ?: continue
                            val found = parseAnnouncement(String(packet.data, 0, packet.length)) ?: continue
                            if (announced.value != found) Log.i(TAG, "Found analyser at ${found.first}:${found.second}")
                            announced.value = found
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (currentCoroutineContext().isActive) {
                        Log.w(TAG, "Discovery on UDP $DISCOVERY_PORT failed: ${e.message}")
                        onError(e)
                        delay(DISCOVERY_RETRY_MS)
                    }
                } finally {
                    udp = null
                }
            }
        } finally {
            runCatching { lock.release() }
        }
    }

    private fun receiveOrNull(sock: DatagramSocket): DatagramPacket? {
        val packet = DatagramPacket(ByteArray(2048), 2048)   // fresh each time: receive() shrinks the length
        return try {
            sock.receive(packet)
            packet
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    /** `{"msgType":301,"deviceIp":"…","devicePort":5200,…}` -> ip to port; null for anything else. */
    private fun parseAnnouncement(text: String): Pair<String, Int>? = runCatching {
        val json = JSONObject(text.trim())
        val ip = json.optString("deviceIp")
        val p = json.optInt("devicePort")
        if (json.optInt("msgType") == 301 && ip.isNotBlank() && p in 1..65535) ip to p else null
    }.getOrNull()

    private companion object {
        const val TAG = "TcpClient"
        const val PING = "H:Ping"

        const val DISCOVERY_PORT = 8866
        const val DISCOVERY_RETRY_MS = 5_000L
        const val KEEP_ALIVE_MS = 30_000L
        const val REQUEST_TIMEOUT_MS = 10_000L
        val TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    }
}