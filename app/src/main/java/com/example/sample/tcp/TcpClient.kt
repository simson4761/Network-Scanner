package com.example.sample.tcp

import android.util.Log
import com.example.sample.data.AckStatus
import com.example.sample.data.TcpConnectionState
import com.example.sample.utils.HL7Parser
import com.example.sample.data.hl7.Hl7Message
import com.example.sample.data.hl7.Hl7ParseException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

/**
 * Connects to the analyzer over TCP, reads MLLP-framed ORU^R01 messages, parses and
 * validates them with [HL7Parser], hands valid ones to [onResult], and sends the ACK^R01 back.
 *
 * Reconnects automatically (1s, 2s, 4s ... up to 30s) when the link drops.
 *
 * [onResult] must save the data. If it throws, the device gets AR 207 and will treat
 * the upload as failed.
 */
class TcpClient(
    private val host: String,
    private val port: Int,
    private val onError: (Throwable) -> Unit = {},
    private val onResult: suspend (Hl7Message) -> Unit
) {

    companion object {
        private const val TAG = "TcpClient"
        private const val SB = 0x0B          // MLLP start block
        private const val EB = 0x1C          // MLLP end block
        private const val CR = 0x0D          // MLLP trailer
        private const val MAX_FRAME = 1 shl 20
    }

    private val _state = MutableStateFlow(TcpConnectionState.DISCONNECTED)
    val state: StateFlow<TcpConnectionState> = _state

    private var job: Job? = null
    @Volatile private var socket: Socket? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) {
            Log.d(TAG, "start() called but client is already active")
            return
        }
        Log.i(TAG, "Starting TCP client to $host:$port")
        job = scope.launch(Dispatchers.IO) { runLoop() }
    }

    fun stop() {
        Log.i(TAG, "Stopping TCP client")
        job?.cancel()
        job = null
        runCatching { socket?.close() }   // unblocks the pending read
        _state.value = TcpConnectionState.DISCONNECTED
    }

    // ---------- connection loop ----------

    private suspend fun runLoop() {
        var backoff = 1_000L
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
                    backoff = 1_000L
                    Log.i(TAG, "Connected successfully to $host:$port")
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
            if (currentCoroutineContext().isActive) {
                Log.d(TAG, "Reconnecting in ${backoff}ms...")
                delay(backoff.milliseconds)
                backoff = min(backoff * 2, 30_000L)
            }
        }
    }

    /** Reads frames until the device closes the connection. */
    private suspend fun session(s: Socket) {
        Log.d(TAG, "Session started. Waiting for incoming data...")
        val input = BufferedInputStream(withContext(Dispatchers.IO) {
            s.getInputStream()
        })
        val output = withContext(Dispatchers.IO) {
            s.getOutputStream()
        }
        while (true) {
            val raw = readFrame(input)
            if (raw == null) {
                Log.i(TAG, "Socket input stream reached EOF (peer closed connection)")
                return
            }
            Log.i(TAG, "Received MLLP raw frame (${raw.length} chars):\n$raw")
            handle(raw, output)
        }
    }

    // ---------- message handling ----------

    private suspend fun handle(raw: String, out: OutputStream) {
        Log.d(TAG, "Handling raw HL7 payload...")
        val msg = try {
            val parsed = HL7Parser.parse(raw)
            Log.d(TAG, "Parsed message MSH controlId=${parsed.msh.controlId}, msgType=${parsed.msh.messageCode}^${parsed.msh.triggerEvent}")
            parsed
        } catch (e: Hl7ParseException) {
            Log.e(TAG, "HL7ParseException: ${e.message}, status=${e.status}")
            val errorAck = HL7Parser.buildErrorAck(e.status)
            Log.d(TAG, "Sending Error ACK:\n$errorAck")
            writeFrame(out, errorAck)
            return
        }

        var status = HL7Parser.validate(msg)
        Log.d(TAG, "HL7 message validation status: $status")
        if (status == AckStatus.ACCEPTED) {
            status = try {
                Log.d(TAG, "Delivering HL7 message to onResult listener...")
                onResult(msg)
                AckStatus.ACCEPTED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error in onResult callback", e)
                onError(e)
                AckStatus.APPLICATION_INTERNAL_ERROR
            }
        }
        val ack = HL7Parser.buildAck(msg, status)
        Log.i(TAG, "Sending ACK (status=$status):\n$ack")
        writeFrame(out, ack)
    }

    // ---------- MLLP ----------

    /** Returns the next message body, or null when the stream is closed. */
    private fun readFrame(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var inFrame = false
        var bytesReadTotal = 0
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (bytesReadTotal > 0) {
                    Log.w(TAG, "Stream ended prematurely after reading $bytesReadTotal bytes")
                }
                return null
            }
            bytesReadTotal++
            when {
                b == SB -> {
                    Log.d(TAG, "Detected MLLP SB byte (0x0B). Start of frame.")
                    buf.reset()
                    inFrame = true
                }
                !inFrame -> {
                    Log.v(TAG, "Ignored noise byte outside frame: 0x${Integer.toHexString(b)}")
                }
                b == EB -> {
                    val cr = input.read() // trailing <CR>
                    Log.d(TAG, "Detected MLLP EB byte (0x1C) with CR (0x${Integer.toHexString(cr)}). End of frame (${buf.size()} bytes).")
                    return String(buf.toByteArray(), Charsets.UTF_8)
                }
                else -> {
                    buf.write(b)
                    if (buf.size() > MAX_FRAME) {
                        Log.e(TAG, "Frame size exceeded MAX_FRAME ($MAX_FRAME bytes), resetting buffer.")
                        buf.reset()
                        inFrame = false
                    }
                }
            }
        }
    }

    private fun writeFrame(out: OutputStream, message: String) {
        val body = message.toByteArray(Charsets.UTF_8)
        Log.d(TAG, "Writing MLLP framed ACK (${body.size} bytes)")
        out.write(byteArrayOf(SB.toByte()) + body + byteArrayOf(EB.toByte(), CR.toByte()))
        out.flush()
    }
}
