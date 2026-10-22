package com.example.sample.tcp

import android.util.Log
import com.example.sample.data.AckStatus
import com.example.sample.data.TcpConnectionState
import com.example.sample.data.hl7.Hl7Message
import com.example.sample.data.ozelle.PendingOrder
import com.example.sample.utils.HL7Orders
import kotlinx.coroutines.*
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * CLIENT mode: connects to the analyzer at [host]:[port]. Results (ORU^R01) are handled by
 * [BaseTcpLink]. ORM queries are answered from [onOrderQuery], and [sendOrder] pushes an order
 * to the analyzer without waiting for a query.
 *
 * Reconnects automatically (1s, 2s, 4s ... up to 30s) when the link drops. The backoff only
 * resets to 1s after a complete MLLP frame has been received, so a server that accepts the
 * connection and immediately closes it (wrong port, non-HL7 service) keeps backing off.
 */
class TcpClient(
    private val host: String,
    private val port: Int,
    onError: (Throwable) -> Unit = {},
    private val onOrderQuery: suspend (sampleNo: String) -> PendingOrder? = { null },
    onResult: suspend (Hl7Message) -> Unit
) : BaseTcpLink("TcpClient", onError, onResult) {

    @Volatile private var socket: Socket? = null

    override fun closeSockets() {
        runCatching { socket?.close() }   // unblocks the pending read
    }

    // ---------- connection loop ----------

    override suspend fun runLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                Log.d(TAG, "Connecting to $host:$port...")
                _state.value = TcpConnectionState.CONNECTING
                Socket().use { s ->
                    s.keepAlive = true
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(host, port), 5_000)
                    socket = s
                    _state.value = TcpConnectionState.CONNECTED
                    Log.i(TAG, "Connected successfully to $host:$port (local port ${s.localPort})")
                    session(s)
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "Connection loop cancelled")
                throw e
            } catch (e: Exception) {
                if (currentCoroutineContext().isActive) {
                    Log.e(TAG, "Error in connection/session for $host:$port: ${e.localizedMessage}", e)
                    onError(e)
                }
            } finally {
                socket = null
                _state.value = TcpConnectionState.DISCONNECTED
                Log.d(TAG, "Disconnected from $host:$port")
            }
            if (currentCoroutineContext().isActive) waitBackoff("Reconnecting")
        }
    }

    // ---------- orders ----------

    /** ORM messages are answered with an ORR built from [onOrderQuery]. */
    override suspend fun handleParsed(msg: Hl7Message, status: AckStatus, out: OutputStream): Boolean {
        if (msg.msh.messageCode != "ORM") return false
        handleOrderQuery(msg, status, out)
        return true
    }

    private suspend fun handleOrderQuery(msg: Hl7Message, validation: AckStatus, out: OutputStream) {
        var status = validation
        var order: PendingOrder? = null

        if (status == AckStatus.ACCEPTED) {
            val sampleNo = msg.orc?.sampleNo.orEmpty()
            Log.i(TAG, "Order query for sample '$sampleNo'")
            try {
                order = onOrderQuery(sampleNo)
                if (order == null) status = AckStatus.UNKNOWN_KEY_IDENTIFIER
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error in onOrderQuery callback", e)
                onError(e)
                status = AckStatus.APPLICATION_INTERNAL_ERROR
            }
        }

        val orr = HL7Orders.buildOrr(msg, status, order)
        Log.i(TAG, "Sending ORR (status=$status):\n$orr")
        writeFrame(out, orr)
    }

    /** Pushes an order to the analyser without waiting for an ORM. Fails if there is no open session. */
    override suspend fun sendOrder(order: PendingOrder): Result<Unit> = withContext(Dispatchers.IO) {
        val o = out ?: return@withContext Result.failure(IllegalStateException("Analyser not connected"))
        runCatching {
            val controlId = "M" + (System.currentTimeMillis() % 100_000_000L)
            val orr = HL7Orders.buildOrr(controlId, AckStatus.ACCEPTED, order)
            Log.i(TAG, "Sending manual order ORR:\n$orr")
            writeFrame(o, orr)
        }
    }

    private companion object {
        const val TAG = "TcpClient"
    }
}