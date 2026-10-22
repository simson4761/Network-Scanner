package com.example.sample.serial

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.sample.data.AckStatus
import com.example.sample.data.DeviceType
import com.example.sample.data.TcpConnectionState
import com.example.sample.data.hl7.Hl7Message
import com.example.sample.data.hl7.Hl7ParseException
import com.example.sample.utils.HL7Parser
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

/**
 * Talks to the analyser over a USB-to-RS232 adapter plugged into the phone (USB OTG).
 * Reads MLLP-framed ORU^R01 messages, parses and validates them with [HL7Parser], hands valid
 * ones to [onResult], and sends the ACK^R01 back. Same framing and ACK logic as [com.example.sample.tcp.TcpServer].
 *
 * State mapping (reusing the existing enum):
 *  - DISCONNECTED: stopped
 *  - CONNECTING:   waiting for the adapter / USB permission / opening the port
 *  - CONNECTED:    serial port is open (this does not prove the analyser is attached)
 *
 * If the adapter is unplugged or the port fails, it retries every [RETRY_MS].
 *
 * [onResult] must save the data. If it throws, the device gets AR 207 and will treat
 * the upload as failed.
 */
class SerialClient(
    context: Context,
    private val baudRate: Int,
    private val dataBits: Int = 8,
    private val stopBits: Int = UsbSerialPort.STOPBITS_1,
    private val parity: Int = UsbSerialPort.PARITY_NONE,
    private val onError: (Throwable) -> Unit = {},
    private val onResult: suspend (Hl7Message) -> Unit
) {

    companion object {
        private const val TAG = "SerialPortClient"
        private const val ACTION_USB_PERMISSION = "com.example.sample.USB_PERMISSION"
        private const val SB = 0x0B          // MLLP start block
        private const val EB = 0x1C          // MLLP end block
        private const val CR = 0x0D          // MLLP trailer
        private const val MAX_FRAME = 1 shl 20
        private const val RETRY_MS = 2_000L
        private const val READ_TIMEOUT_MS = 1_000
        private const val WRITE_TIMEOUT_MS = 2_000
        private const val MAX_NOISE_LOG = 256
    }

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _state = MutableStateFlow(TcpConnectionState.DISCONNECTED)
    val state: StateFlow<TcpConnectionState> = _state

    private var job: Job? = null
    @Volatile private var port: UsbSerialPort? = null

    private var device = DeviceType.HEMATOLOGY

    fun start(scope: CoroutineScope,deviceType : DeviceType) {
        if (job?.isActive == true) {
            Log.d(TAG, "start() called but serial client is already active")
            return
        }
        device = deviceType
        Log.i(TAG, "Starting serial client at $baudRate baud, $dataBits data bits")
        job = scope.launch(Dispatchers.IO) { runLoop() }
    }

    fun stop() {
        Log.i(TAG, "Stopping serial client")
        job?.cancel()
        job = null
        runCatching { port?.close() }   // unblocks the pending read
        _state.value = TcpConnectionState.DISCONNECTED
    }

    // ---------- connection loop ----------

    private suspend fun runLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                _state.value = TcpConnectionState.CONNECTING
                val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).firstOrNull()
                if (driver == null) {
                    Log.d(TAG, "No USB serial adapter found, waiting...")
                } else {
                    if (!ensurePermission(driver.device)) throw IOException("USB permission denied")
                    val connection = usbManager.openDevice(driver.device)
                        ?: throw IOException("Could not open USB device")
                    val p = driver.ports.first()
                    p.open(connection)
                    try {
                        p.setParameters(baudRate, dataBits, stopBits, parity)
                        runCatching { p.dtr = true }   // some adapters / analysers need the control lines up
                        runCatching { p.rts = true }
                        port = p
                        _state.value = TcpConnectionState.CONNECTED
                        Log.i(TAG, "Serial port open: ${driver.device.deviceName}")
                        session(p)
                    } finally {
                        runCatching { p.close() }
                        port = null
                    }
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "Serial loop cancelled")
                throw e
            } catch (e: Exception) {
                if (currentCoroutineContext().isActive) {
                    Log.e(TAG, "Serial error: ${e.localizedMessage}", e)
                    onError(e)
                }
            } finally {
                _state.value =
                    if (currentCoroutineContext().isActive) TcpConnectionState.CONNECTING
                    else TcpConnectionState.DISCONNECTED
            }
            if (currentCoroutineContext().isActive) delay(RETRY_MS.milliseconds)
        }
    }

    /** Shows the system "Allow access to USB device?" dialogue if needed. Returns true when granted. */
    private suspend fun ensurePermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    runCatching { appContext.unregisterReceiver(this) }
                    val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (cont.isActive) cont.resume(granted)
                }
            }
            ContextCompat.registerReceiver(
                appContext, receiver, IntentFilter(ACTION_USB_PERMISSION),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                appContext, 0,
                Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName),
                flags
            )
            usbManager.requestPermission(device, pi)
            cont.invokeOnCancellation { runCatching { appContext.unregisterReceiver(receiver) } }
        }
    }

    /** Reads frames until the port fails (adapter unplugged) or stop() closes it. */
    private suspend fun session(p: UsbSerialPort) {
        Log.d(TAG, "Session started. Waiting for incoming data...")
        val input = PortInputStream(p)
        val output = PortOutputStream(p)
        while (true) {
            val raw = readFrame(input) ?: return
            Log.i(TAG, "Received MLLP raw frame (${raw.length} chars):\n$raw")
            handle(raw, output)
        }
    }

    // ---------- message handling ----------

    private suspend fun handle(raw: String, out: OutputStream) {
        Log.d(TAG, "Handling raw HL7 payload...")
        val msg = try {
            val parsed = HL7Parser.parse(raw,device)
            Log.d(TAG, "Parsed message MSH controlId=${parsed.msh.controlId}, msgType=${parsed.msh.messageCode}^${parsed.msh.triggerEvent}")
            parsed
        } catch (e: Hl7ParseException) {
            Log.e(TAG, "HL7ParseException: ${e.message}, status=${e.status}")
            val errorAck = HL7Parser.buildErrorAck(status = e.status,device = device)
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

    // ---------- UsbSerialPort as streams ----------

    /** Blocks until a byte arrives. Throws IOException when the adapter is unplugged or the port is closed. */
    private class PortInputStream(private val p: UsbSerialPort) : InputStream() {
        private val buf = ByteArray(4096)
        private var pos = 0
        private var len = 0

        override fun read(): Int {
            while (pos >= len) {
                val n = p.read(buf, READ_TIMEOUT_MS)   // 0 on timeout, so keep waiting
                if (n > 0) {
                    pos = 0
                    len = n
                }
            }
            return buf[pos++].toInt() and 0xFF
        }
    }

    private class PortOutputStream(private val p: UsbSerialPort) : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            p.write(b.copyOfRange(off, off + len), WRITE_TIMEOUT_MS)
        }
    }
}