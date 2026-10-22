package com.example.sample.tcp

import android.util.Log
import com.example.sample.data.TcpConnectionState
import com.example.sample.data.seamaty.WorklistOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket


/**
 * SERVER mode: listens on [port] as the LIS server. The analyzer connects to us.
 *  - ORU^R01 (results): handled by [BaseTcpLink] and delivered to [onResult].
 *  - Worklist request: ORM^O01 (manual) or ORU^R02 (what the Seamaty unit sends). We call
 *    [onWorklistRequest] and reply with ORR^O02
 *    (MSA|AA + PID + OBR when there is a worklist, MSA|AR when there is none).
 *
 * State: DISCONNECTED = not listening, CONNECTING = listening, CONNECTED = analyzer connected.
 * If the analyzer reconnects, the previous (possibly half-open) connection is closed.
 * If binding fails, it retries with backoff (1s, 2s, 4s ... up to 30s).
 *
 * [onWorklistRequest] returns the next pending worklist, or null if there is none. Mark the
 * item as sent inside it, otherwise the analyzer gets the same worklist on every request.
 */
class TcpServer(
    private val port: Int,
    onError: (Throwable) -> Unit = {},
    private val onWorklistRequest: suspend () -> WorklistOrder?,
    onResult: suspend (com.example.sample.data.hl7.Hl7Message) -> Unit
) : BaseTcpLink("TcpServer", onError, onResult) {


    private companion object {
        const val TAG = "TcpServer"
    }

    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var clientSocket: Socket? = null
    private var worklistCounter = 0

    override fun closeSockets() {
        runCatching { clientSocket?.close() }
        runCatching { serverSocket?.close() }
    }

    // ---------- server loop ----------

    override suspend fun runLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                withContext(Dispatchers.IO) {
                    ServerSocket().use { ss ->
                        ss.reuseAddress = true
                        ss.bind(InetSocketAddress(port))   // 0.0.0.0:port, reachable over Wi-Fi / Ethernet
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
            if (currentCoroutineContext().isActive) waitBackoff("Re-opening server")
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
}