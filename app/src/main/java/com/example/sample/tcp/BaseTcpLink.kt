package com.example.sample.tcp

import android.util.Log
import com.example.sample.data.AckStatus
import com.example.sample.data.DeviceType
import com.example.sample.data.TcpConnectionState
import com.example.sample.data.hl7.Hl7Message
import com.example.sample.data.hl7.Hl7ParseException
import com.example.sample.data.seamaty.WorklistOrder
import com.example.sample.utils.HL7MessageAssembler
import com.example.sample.utils.HL7Parser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds


/**
 * Shared part of [TcpServer] and [TcpClient]: MLLP framing, HL7 parse/validate/ACK, and the
 * session read loop. Subclasses only decide how the socket is obtained ([runLoop]) and how
 * the mode-specific ORM messages are answered ([handleRaw] / [handleParsed]).
 *
 * [onResult] must save the data. If it throws, the device gets AR 207 and will treat
 * the upload as failed.
 */
abstract class BaseTcpLink(
    private val tag: String,
    protected val onError: (Throwable) -> Unit,
    private val onResult: suspend (Hl7Message) -> Unit
) : AnalyserLink {

    companion object {
        private const val SB = 0x0B          // MLLP start block
        private const val EB = 0x1C          // MLLP end block
        private const val CR = 0x0D          // MLLP trailer
        private const val MAX_FRAME = 1 shl 20
        private const val MAX_NOISE_LOG = 256
        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L

        /** Characters that would break HL7 delimiters or MLLP framing. */
        fun stripFramingChars(s: String): String =
            s.replace('|', ' ').replace('\r', ' ').replace('\n', ' ')
                .replace(SB.toChar(), ' ').replace(EB.toChar(), ' ')
    }

    protected val _state = MutableStateFlow(TcpConnectionState.DISCONNECTED)
    override val state: StateFlow<TcpConnectionState> = _state

    private var job: Job? = null
    protected var device = DeviceType.HEMATOLOGY
        private set

    /** Reconnect / re-bind delay. Reset to 1s once a complete MLLP frame has been received. */
    protected var backoff = MIN_BACKOFF_MS

    @Volatile
    protected var out: OutputStream? = null
    private val writeLock = Any()

    // add near the other properties (import com.example.sample.utils.WorklistOrder)
    /** Seamaty worklist: returns and removes the order for this sample number; null or "*" = next pending. */
    @Volatile
    var worklistProvider: ((String?) -> WorklistOrder?)? = null

    /** Last worklist query seen from the analyser. Used to build a manual push. */
    @Volatile
    private var lastWorklistQuery: Hl7Message? = null

    val assembler = HL7MessageAssembler()

    // ---------- lifecycle ----------

    override fun start(scope: CoroutineScope, deviceType: DeviceType) {
        if (job?.isActive == true) {
            Log.d(tag, "start() called but link is already active")
            return
        }
        device = deviceType
        backoff = MIN_BACKOFF_MS
        Log.i(tag, "Starting link")
        job = scope.launch(Dispatchers.IO) { runLoop() }
    }

    override fun stop() {
        Log.i(tag, "Stopping link")
        job?.cancel()
        job = null
        closeSockets()   // unblocks the pending accept() / read()
        _state.value = TcpConnectionState.DISCONNECTED
    }

    /** Connect (client) or listen (server) in a loop, calling [session] for each connection. */
    protected abstract suspend fun runLoop()

    /** Close every socket so blocked accept()/read() calls return. */
    protected abstract fun closeSockets()

    protected suspend fun waitBackoff(what: String) {
        Log.d(tag, "$what in ${backoff}ms...")
        delay(backoff.milliseconds)
        backoff = min(backoff * 2, MAX_BACKOFF_MS)
    }

    // ---------- session ----------

    /** Reads frames until the device closes the connection. */
    protected suspend fun session(s: Socket) {
        Log.d(tag, "Session started. Waiting for incoming data...")
        val input = BufferedInputStream(withContext(Dispatchers.IO) { s.getInputStream() })
        val output = withContext(Dispatchers.IO) { s.getOutputStream() }
        out = output
        try {
            while (true) {
                val raw = try {
                    readFrame(input)
                } catch (e: SocketTimeoutException) {
                    Log.i(tag, "Socket timed out while reading, flushing out the remaining frames")
                    flushPending(output)      // idle: process the last buffered message
                    continue
                }
                if (raw == null) {
                    Log.i(tag, "Socket input stream reached EOF (peer closed connection)")
                    return
                }
                backoff = MIN_BACKOFF_MS      // a real frame arrived, so this peer speaks MLLP
                Log.i(tag, "Received MLLP raw frame (${raw.length} chars):\n$raw")
                handle(raw, output)
            }
        } finally {
            if (out === output) out = null
            // peer closed or the session was cancelled: don't lose the last message
            // (the ACK write may fail if the peer is already gone, so ignore that)
            runCatching { withContext(NonCancellable) { flushPending(output) } }
        }
    }

    // ---------- message handling ----------

    /**
     * Mode-specific hook that sees the raw frame before the assembler.
     * Return true if the frame was fully handled (a reply was sent).
     */
    protected open suspend fun handleRaw(raw: String, out: OutputStream): Boolean = false

    /**
     * Mode-specific hook that sees the parsed + validated message before onResult.
     * Return true if the message was fully handled (a reply was sent).
     */
    protected open suspend fun handleParsed(msg: Hl7Message, status: AckStatus, out: OutputStream): Boolean = false

    private suspend fun handle(raw: String, out: OutputStream) {
        if (handleRaw(raw, out)) return

        // A worklist query is a complete message in one frame and the device is waiting for the reply,
        // so don't hold it in the assembler.
        if (isWorklistFrame(raw)) {
            flushPending(out)          // finish any half-built result message first
            processMessage(raw, out)
            return
        }

        Log.d(tag, "Handling raw HL7 payload...")
        val message = assembler.onFrame(raw) ?: return
        processMessage(message, out)
    }

    /** True when MSH-9 is a worklist query: ORU^R02 (Seamaty unit) or ORM^O01. */
    private fun isWorklistFrame(raw: String): Boolean {
        val text = raw.trim { it == '\u000B' || it == '\u001C' || it.isWhitespace() }
        if (!text.startsWith("MSH")) return false
        val msh9 = text.lineSequence().first().split('|').getOrNull(8).orEmpty()
        return msh9.startsWith("ORU^R02") || msh9.startsWith("ORM^O01")
    }

    /** Call when the connection goes idle (~500 ms without a frame) or closes, to process the last message. */
    private suspend fun flushPending(out: OutputStream) {
        val message = assembler.flush() ?: return
        processMessage(message, out)
    }

    private suspend fun processMessage(raw: String, out: OutputStream) {
        val msg = try {
            val parsed = HL7Parser.parse(raw, device)
            Log.d(
                tag,
                "Parsed message MSH controlId=${parsed.msh.controlId}, msgType=${parsed.msh.messageCode}^${parsed.msh.triggerEvent}"
            )
            parsed
        } catch (e: Hl7ParseException) {
            Log.e(tag, "HL7ParseException: ${e.message}, status=${e.status}")
            val errorAck = HL7Parser.buildErrorAck(device, e.status)
            Log.d(tag, "Sending Error ACK:\n$errorAck")
            writeFrame(out, errorAck)
            return
        }

        var status = HL7Parser.validate(msg)
        Log.d(tag, "HL7 message validation status: $status")

        lastWorklistQuery = msg
        if (handleParsed(msg, status, out)) return

        if (device == DeviceType.SEAMATY && HL7Parser.isWorklistQuery(msg)) {
            val order = if (status == AckStatus.ACCEPTED) worklistProvider?.invoke(msg.orc?.sampleNo) else null
            if (order == null) {
                Log.i(tag, "No pending worklist orders, replying AR")
            }
            else{
                val orr = HL7Parser.buildWorklistOrr(msg, status, order)
                Log.i(tag, "Sending worklist ORR (status=$status):\n$orr")
                writeFrame(out, orr)
            }
            return
        }

        if (status == AckStatus.ACCEPTED) {
            status = try {
                Log.d(tag, "Delivering HL7 message to onResult listener...")
                onResult(msg)
                AckStatus.ACCEPTED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "Error in onResult callback", e)
                onError(e)
                AckStatus.APPLICATION_INTERNAL_ERROR
            }
        }
        val ack = HL7Parser.buildAck(msg, status)

        Log.i(tag, "Sending ACK (status=$status):\n$ack")
        writeFrame(out, ack)
    }

    // ---------- MLLP ----------

    /** Returns the next message body, or null when the stream is closed. */
    private fun readFrame(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        val noise = ByteArrayOutputStream()   // bytes seen outside a frame, logged once as text
        var inFrame = false
        var bytesReadTotal = 0
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (bytesReadTotal > 0) {
                    Log.w(tag, "Stream ended prematurely after reading $bytesReadTotal bytes")
                }
                logNoise(noise)
                return null
            }
            bytesReadTotal++
            when {
                b == SB -> {
                    logNoise(noise)
                    Log.d(tag, "Detected MLLP SB byte (0x0B). Start of frame.")
                    buf.reset()
                    inFrame = true
                }

                !inFrame -> {
                    if (noise.size() < MAX_NOISE_LOG) noise.write(b)
                }

                b == EB -> {
                    val cr = input.read() // trailing <CR>
                    Log.d(
                        tag,
                        "Detected MLLP EB byte (0x1C) with CR (0x${Integer.toHexString(cr)}). End of frame (${buf.size()} bytes)."
                    )
                    return String(buf.toByteArray(), Charsets.UTF_8)
                }

                else -> {
                    buf.write(b)
                    if (buf.size() > MAX_FRAME) {
                        Log.e(tag, "Frame size exceeded MAX_FRAME ($MAX_FRAME bytes), resetting buffer.")
                        buf.reset()
                        inFrame = false
                    }
                }
            }
        }
    }

    /** Logs bytes received outside an MLLP frame as one line, so a non-HL7 peer is easy to spot. */
    private fun logNoise(noise: ByteArrayOutputStream) {
        if (noise.size() == 0) return
        val text = String(noise.toByteArray(), Charsets.ISO_8859_1).trim()
        Log.w(
            tag,
            "Ignored ${noise.size()} byte(s) outside MLLP frame (peer may not be speaking HL7/MLLP): \"$text\""
        )
        noise.reset()
    }

    protected fun writeFrame(out: OutputStream, message: String) {
        val body = message.toByteArray(Charsets.UTF_8)
        Log.d(tag, "Writing MLLP framed message (${body.size} bytes)")
        synchronized(writeLock) {
            out.write(byteArrayOf(SB.toByte()) + body + byteArrayOf(EB.toByte(), CR.toByte()))
            out.flush()
        }
    }

    override suspend fun pushWorklist(order: WorklistOrder): Result<Unit> = withContext(Dispatchers.IO) {
        Log.i(tag, "pushWorklist() called: sampleNo=${order.sampleNo}, order=$order")
        Log.d(tag, "pushWorklist state: linkState=${_state.value}, outConnected=${out != null}, hasLastQuery=${lastWorklistQuery != null}")

        runCatching {
            val output = out ?: error("Analyser is not connected")
            Log.d(tag, "pushWorklist: output stream available")

            val query = lastWorklistQuery
                ?: error("Analyser has not sent a worklist query yet")
            Log.d(
                tag,
                "pushWorklist: using last query controlId=${query.msh.controlId}, " +
                        "msgType=${query.msh.messageCode}^${query.msh.triggerEvent}, orcSampleNo=${query.orc?.sampleNo}"
            )

            val orr = HL7Parser.buildWorklistOrr(query, AckStatus.ACCEPTED, order)
            Log.i(tag, "pushWorklist: built ORR (${orr.length} chars):\n$orr")

            Log.i(tag, "pushWorklist: ORR written and flushed for sampleNo=${order.sampleNo}")
            writeFrame(output, orr)
        }.onFailure {
            Log.e(tag, "pushWorklist failed for sampleNo=${order.sampleNo}: ${it.localizedMessage}", it)
        }
    }
}