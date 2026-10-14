package com.example.sample.viewModel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sample.data.ConnectionMode
import com.example.sample.data.DeviceConnectionState
import com.example.sample.data.DeviceScreenUiState
import com.example.sample.data.ObservationItem
import com.example.sample.data.PatientInfo
import com.example.sample.data.ResultStorage
import com.example.sample.data.TcpConnectionState
import com.example.sample.data.TestResultRecord
import com.example.sample.data.hl7.Hl7Message
import com.example.sample.serial.SerialClient
import com.example.sample.tcp.TcpServer
import com.example.sample.utils.HL7Parser
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DeviceViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "DeviceViewModel"
         const val TCP_PORT = 8080
         const val SERIAL_BAUD_RATE = 9600

    }

    private val resultStorage = ResultStorage(application.applicationContext)

    private val _uiState = MutableStateFlow(DeviceScreenUiState())
    val uiState: StateFlow<DeviceScreenUiState> = _uiState.asStateFlow()

    private var tcpServer: TcpServer? = null
    private var serialClient: SerialClient? = null
    private var stateJob: Job? = null

    /**
     * Keys (deviceId|sampleNo|testTime) of every result already stored.
     * add() returns false when the key is present, so check-and-insert is one atomic step.
     */
    private val knownKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        // Load every previously saved result on ViewModel initialization
        val saved = resultStorage.getAllResults()
        Log.i(TAG, "Loaded ${saved.size} saved results from storage")

        // Rebuild the keys from the stored raw messages so duplicates are caught after a restart too.
        saved.forEach { record ->
            runCatching { resultKey(HL7Parser.parse(record.rawMessage)) }
                .onSuccess { knownKeys.add(it) }
                .onFailure { Log.w(TAG, "Could not derive key for saved result ${record.id}: ${it.message}") }
        }

        _uiState.update { it.copy(savedResults = saved) }
    }

    fun connect() {
        disconnect()
        when (_uiState.value.connectionMode) {
            ConnectionMode.TCP -> startTcp()
            ConnectionMode.SERIAL -> startSerial()
        }
    }

    /** Starts listening for the analyzer. The port is the one configured on the device's LIS settings. */
    fun startTcp() {

        disconnect()

        // deviceIpAddress is now this phone's IP (shown in the status text); the analyzer connects to it.
        val host = _uiState.value.deviceIpAddress

        Log.i(TAG, "connect() requested: starting server on port=$TCP_PORT (host=$host)")

        val server = TcpServer(
            port = TCP_PORT,
            onError = { error ->
                // The server recovers on its own (session ended / re-bind with backoff), so don't stop it here.
                Log.e(TAG, "TcpServer onError callback: ${error.localizedMessage}", error)
                _uiState.update {
                    it.copy(
                        connectionState = DeviceConnectionState.Error(
                            error.localizedMessage ?: "Connection error"
                        )
                    )
                }
            },
            onResult = { hl7Msg ->
                Log.i(TAG, "TcpServer onResult received Hl7Message raw length=${hl7Msg.raw.length}")
                handleHl7Message(hl7Msg)
            }
        )
        tcpServer = server

        observeState(server.state, host = host, port = TCP_PORT)

        server.start(viewModelScope)
    }

    /** Starts talking to the analyzer over a USB-to-RS232 adapter (phone must support USB OTG). */
    fun startSerial() {

        disconnect()

        Log.i(TAG, "connectSerial() requested: baud=$SERIAL_BAUD_RATE")

        val client = SerialClient(
            context = getApplication(),
            baudRate = SERIAL_BAUD_RATE,
            onError = { reportError(it) },
            onResult = { hl7Msg ->
                Log.i(
                    TAG,
                    "SerialPortClient onResult received Hl7Message raw length=${hl7Msg.raw.length}"
                )
                handleHl7Message(hl7Msg)
            }
        )
        serialClient = client

        // For serial, the status text shows "USB serial:<baud>" instead of ip:port.
        observeState(client.state, host = "USB serial", port = SERIAL_BAUD_RATE)
        client.start(viewModelScope)
    }


    private fun observeState(state: StateFlow<TcpConnectionState>, host: String, port: Int) {
        stateJob = viewModelScope.launch {
            state.collect { s ->
                Log.d(TAG, "Link state collected: $s")
                _uiState.update { current ->
                    val newConnState = when (s) {
                        TcpConnectionState.DISCONNECTED -> {
                            current.connectionState as? DeviceConnectionState.Error ?: DeviceConnectionState.Disconnected
                        }
                        // CONNECTING = listening / waiting for the adapter
                        TcpConnectionState.CONNECTING -> DeviceConnectionState.Connecting
                        // CONNECTED = analyzer connected (TCP) or serial port open
                        TcpConnectionState.CONNECTED -> DeviceConnectionState.Connected(host, port)
                    }
                    current.copy(connectionState = newConnState)
                }
            }
        }
    }

    private fun reportError(error: Throwable) {
        // The link recovers on its own (re-bind / re-open with backoff), so don't stop it here.
        Log.e(TAG, "Link onError callback: ${error.localizedMessage}", error)
        _uiState.update {
            it.copy(
                connectionState = DeviceConnectionState.Error(
                    error.localizedMessage ?: "Connection error"
                )
            )
        }
    }


    fun disconnect() {
        Log.i(TAG, "disconnect() requested")
        stateJob?.cancel()
        stateJob = null
        tcpServer?.stop()
        tcpServer = null
        serialClient?.stop()
        serialClient = null
        _uiState.update {
            if (it.connectionState !is DeviceConnectionState.Error) {
                it.copy(connectionState = DeviceConnectionState.Disconnected)
            } else {
                it
            }
        }
    }

    fun clearLogs() {
        Log.d(TAG, "clearLogs() called")
        _uiState.update { it.copy(rawMessages = emptyList()) }
    }

    fun clearSavedResults() {
        Log.d(TAG, "clearSavedResults() called")
        resultStorage.clearAllResults()
        knownKeys.clear()
        _uiState.update { it.copy(savedResults = emptyList()) }
    }

    fun updateIpAddress(ipAddress: String) {
        _uiState.update {
            it.copy(
                deviceIpAddress = ipAddress
            )
        }
    }

    fun updateOpenPort(openPort: List<Int>) {
        _uiState.update {
            it.copy(
                openPorts = openPort
            )
        }
    }

    /**
     * Identity of one test result: device ID (OBR-4) + sample number (OBR-3) + test time (OBR-7).
     * Falls back to the message time (MSH-7) if the analyzer leaves OBR-7 empty.
     */
    private fun resultKey(msg: Hl7Message): String {
        val obr = msg.obr
        val testTime = obr?.observationTime?.takeIf { it.isNotBlank() } ?: msg.msh.timestamp
        return listOf(obr?.deviceId.orEmpty(), obr?.sampleNo.orEmpty(), testTime).joinToString("|")
    }

    private fun handleHl7Message(msg: Hl7Message) {
        Log.i(TAG, "handle Hl7 Message: pid=${msg.pid?.patientId}, name=${msg.pid?.name}, obxCount=${msg.obxs.size}")

        // The analyzer can re-upload a result from its history screen. Keep the first copy only.
        // The ACK is still AA (this function returns normally), so the analyzer stops retrying.
        val key = resultKey(msg)
        if (!knownKeys.add(key)) {
            Log.i(TAG, "Duplicate result ignored (key=$key)")
            _uiState.update { it.copy(rawMessages = it.rawMessages + msg.raw) }
            return
        }

        val patientId = msg.pid?.patientId?.takeIf { it.isNotBlank() } ?: "Unknown"
        val patientName = msg.pid?.name?.takeIf { it.isNotBlank() } ?: "Unknown"

        val obsList = msg.obxs.filter { it.code.isNotBlank() }.map { obx ->
            ObservationItem(
                code = obx.code,
                value = obx.value,
                unit = obx.unit,
                flag = obx.flag,
                refRange = obx.refRange
            )
        }

        val record = TestResultRecord(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            patientId = patientId,
            patientName = patientName,
            observations = obsList,
            rawMessage = msg.raw
        )

        // Save every received result persistently to disk.
        // If this fails, forget the key so the analyzer's retry (it gets AR 207) is not dropped as a duplicate.
        try {
            resultStorage.saveResult(record)
        } catch (e: Exception) {
            knownKeys.remove(key)
            throw e
        }

        _uiState.update { current ->
            // "Live data" card shows the latest result only; results from different tests are not mixed.
            current.copy(
                rawMessages = current.rawMessages + msg.raw,
                savedResults = listOf(record) + current.savedResults,
                patientInfo = PatientInfo(
                    patientId = patientId,
                    fullName = patientName,
                    observations = obsList.associate { it.code to it.displayValue }
                )
            )
        }
    }

     fun setConnectionMode(mode : ConnectionMode){
        _uiState.update {
            it.copy(
                connectionMode = mode
            )
        }
    }

    override fun onCleared() {
        Log.d(TAG, "onCleared() called")
        disconnect()
    }
}