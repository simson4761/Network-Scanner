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
import java.net.ServerSocket
import java.net.Socket
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

/**
 * Listens on [port] as the LIS server. The analyzer (SD-1) connects to us; we read MLLP-framed
 * ORU^R01 messages, parse and validate them with [HL7Parser], hand valid ones to [onResult],
 * and send the ACK^R01 back.
 *
 * State mapping (reusing the existing enum):
 *  - DISCONNECTED: not listening
 *  - CONNECTING:   listening, waiting for the analyzer
 *  - CONNECTED:    analyzer is connected
 *
 * If the analyzer reconnects, the previous (possibly half-open) connection is closed.
 * If binding the port fails, it retries with backoff (1s, 2s, 4s ... up to 30s).
 *
 * [onResult] must save the data. If it throws, the device gets AR 207 and will treat
 * the upload as failed.
 */
class TcpServer(
    private val port: Int,
    private val onError: (Throwable) -> Unit = {},
    private val onResult: suspend (Hl7Message) -> Unit
) {

    companion object {
        private const val TAG = "TcpServer"
        private const val SB = 0x0B          // MLLP start block
        private const val EB = 0x1C          // MLLP end block
        private const val CR = 0x0D          // MLLP trailer
        private const val MAX_FRAME = 1 shl 20
        private const val MIN_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val MAX_NOISE_LOG = 256
    }

    private val _state = MutableStateFlow(TcpConnectionState.DISCONNECTED)
    val state: StateFlow<TcpConnectionState> = _state

    private var job: Job? = null
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var clientSocket: Socket? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) {
            Log.d(TAG, "start() called but server is already active")
            return
        }
        Log.i(TAG, "Starting TCP server on port $port")
        job = scope.launch(Dispatchers.IO) { runLoop() }
    }

    fun stop() {
        Log.i(TAG, "Stopping TCP server")
        job?.cancel()
        job = null
        // Closing the sockets unblocks the pending accept() / read().
        runCatching { clientSocket?.close() }
        runCatching { serverSocket?.close() }
        _state.value = TcpConnectionState.DISCONNECTED
    }

    // ---------- server loop ----------

    private suspend fun runLoop() {
        var backoff = MIN_BACKOFF_MS
        while (currentCoroutineContext().isActive) {
            try {
                withContext(Dispatchers.IO) {
                    ServerSocket().use { ss ->
                        ss.reuseAddress = true
                        ss.bind(InetSocketAddress(port))   // 0.0.0.0:port, reachable over Wi-Fi
                        serverSocket = ss
                        backoff = MIN_BACKOFF_MS
                        _state.value = TcpConnectionState.CONNECTING
                        Log.i(TAG, "Listening on 0.0.0.0:${ss.localPort}")
                        acceptLoop(ss)
                    }
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "Server loop cancelled")
                throw e
            } catch (e: Exception) {
                if (currentCoroutineContext().isActive) {
                    Log.e(TAG, "Server error on port $port: ${e.localizedMessage}", e)
                    onError(e)
                }
            } finally {
                serverSocket = null
                _state.value = TcpConnectionState.DISCONNECTED
            }
            if (currentCoroutineContext().isActive) {
                Log.d(TAG, "Re-opening server in ${backoff}ms...")
                delay(backoff.milliseconds)
                backoff = min(backoff * 2, MAX_BACKOFF_MS)
            }
        }
    }

    private suspend fun acceptLoop(ss: ServerSocket) = coroutineScope {
        var sessionJob: Job? = null
        while (isActive) {
            val s = ss.accept()   // blocks; stop() closes ss to unblock it
            Log.i(TAG, "Analyzer connected from ${s.inetAddress?.hostAddress}:${s.port}")

            // Drop any older connection; the analyzer has reconnected.
            runCatching { clientSocket?.close() }
            sessionJob?.cancelAndJoin()

            clientSocket = s
            sessionJob = launch(Dispatchers.IO) {
                try {
                    s.use {
                        it.keepAlive = true
                        it.tcpNoDelay = true
                        _state.value = TcpConnectionState.CONNECTED
                        session(it)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Session error: ${e.localizedMessage}", e)
                        onError(e)
                    }
                } finally {
                    if (clientSocket === s) {
                        clientSocket = null
                        if (serverSocket != null) _state.value = TcpConnectionState.CONNECTING
                    }
                    Log.d(TAG, "Analyzer session ended")
                }
            }
        }
    }

    /** Reads frames until the device closes the connection. */
    private suspend fun session(s: Socket) {
        Log.d(TAG, "Session started. Waiting for incoming data...")
        val input = BufferedInputStream(s.getInputStream())
        val output = s.getOutputStream()
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
        val noise = ByteArrayOutputStream()   // bytes seen outside a frame, logged once as text
        var inFrame = false
        var bytesReadTotal = 0
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (bytesReadTotal > 0) {
                    Log.w(TAG, "Stream ended prematurely after reading $bytesReadTotal bytes")
                }
                logNoise(noise)
                return null
            }
            bytesReadTotal++
            when {
                b == SB -> {
                    logNoise(noise)
                    Log.d(TAG, "Detected MLLP SB byte (0x0B). Start of frame.")
                    buf.reset()
                    inFrame = true
                }
                !inFrame -> {
                    if (noise.size() < MAX_NOISE_LOG) noise.write(b)
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

    /** Logs bytes received outside an MLLP frame as one line, so a non-HL7 peer is easy to spot. */
    private fun logNoise(noise: ByteArrayOutputStream) {
        if (noise.size() == 0) return
        val text = String(noise.toByteArray(), Charsets.ISO_8859_1).trim()
        Log.w(TAG, "Ignored ${noise.size()} byte(s) outside MLLP frame (peer may not be speaking HL7/MLLP): \"$text\"")
        noise.reset()
    }

    private fun writeFrame(out: OutputStream, message: String) {
        val body = message.toByteArray(Charsets.UTF_8)
        Log.d(TAG, "Writing MLLP framed ACK (${body.size} bytes)")
        out.write(byteArrayOf(SB.toByte()) + body + byteArrayOf(EB.toByte(), CR.toByte()))
        out.flush()
    }
}