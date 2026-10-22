package com.example.sample.viewModel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sample.data.ConnectionMode
import com.example.sample.data.DeviceConnectionState
import com.example.sample.data.DeviceScreenUiState
import com.example.sample.data.DeviceType
import com.example.sample.data.ObservationItem
import com.example.sample.data.PatientInfo
import com.example.sample.data.ResultStorage
import com.example.sample.data.TcpConnectionState
import com.example.sample.data.TcpRole
import com.example.sample.data.TestResultRecord
import com.example.sample.data.hl7.Hl7Message
import com.example.sample.data.ozelle.PendingOrder
import com.example.sample.data.seamaty.WorklistOrder
import com.example.sample.serial.SerialClient
import com.example.sample.tcp.AnalyserLink
import com.example.sample.tcp.TcpClient
import com.example.sample.tcp.TcpServer
import com.example.sample.tcp.*
import com.example.sample.utils.HL7Parser
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class DeviceViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "DeviceViewModel"
        const val TCP_PORT = 5100
        const val SERIAL_BAUD_RATE = 9600
    }

    private val resultStorage = ResultStorage(application.applicationContext)

    private val _uiState = MutableStateFlow(DeviceScreenUiState())
    val uiState: StateFlow<DeviceScreenUiState> = _uiState.asStateFlow()

    /** Whichever TCP side is active (TcpServer or TcpClient). Only the role decides which one is built. */
    private var tcpLink: AnalyserLink? = null
    private var serialClient: SerialClient? = null
    private var stateJob: Job? = null

    private val _tcpRole = MutableStateFlow(TcpRole.SERVER)
    val tcpRole: StateFlow<TcpRole> = _tcpRole.asStateFlow()

    // SERVER mode, Seamaty: orders waiting for the analyser to ask. Each one is removed once it has been sent.
    private val _pendingWorklist = MutableStateFlow<List<WorklistOrder>>(emptyList())
    val pendingWorklist: StateFlow<List<WorklistOrder>> = _pendingWorklist.asStateFlow()

    /**
     * Keys (deviceId|sampleNo|testTime) of every result already stored.
     * add() returns false when the key is present, so check-and-insert is one atomic step.
     */
    private val knownKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // CLIENT mode: orders created in the form. In memory only: they are lost when the app process dies.
    private val orders = ConcurrentHashMap<String, PendingOrder>()

    // SERVER mode: worklists waiting for the analyser to ask. poll() removes the item, so each is sent once.
    private val worklistQueue = ConcurrentLinkedQueue<WorklistOrder>()

    private val _orderStatus = MutableStateFlow<String?>(null)
    val orderStatus: StateFlow<String?> = _orderStatus.asStateFlow()

    fun setTcpRole(role: TcpRole) {
        _tcpRole.value = role
        _uiState.update { it.copy(tcpRole = role) }
    }

    /** SERVER mode: queues a worklist to be returned on the analyser's next ORM^O01 request. */
    fun addWorklist(item: WorklistOrder) {
        if (item.sampleNo.isBlank()) {
            _orderStatus.value = "Sample number is required"
            return
        }
        Log.d(TAG,"add worklist : $item")
        worklistQueue.add(item)
        Log.d(TAG,"worklist : $worklistQueue")
        _orderStatus.value =
            "Worklist ${item.sampleNo} queued. It will be sent when the analyser requests it."
    }

    /** CLIENT mode: stores the order so an ORM query for this sample number is answered from it. */
    fun saveOrder(order: PendingOrder) {
        if (order.sampleNo.isBlank()) {
            _orderStatus.value = "Sample number is required"
            return
        }
        orders[order.sampleNo] = order
        _orderStatus.value =
            "Order ${order.sampleNo} saved. It will be returned when the analyser queries it."
    }

    /** CLIENT mode: saves the order, then pushes it to the analyser right now. */
    fun sendOrder(order: PendingOrder) {
        saveOrder(order)
        if (order.sampleNo.isBlank()) return
        val link = tcpLink
        if (link == null || _tcpRole.value != TcpRole.CLIENT) {
            _orderStatus.value =
                "Order ${order.sampleNo} saved, but not sent: TCP client mode is not running"
            return
        }
        viewModelScope.launch {
            link.sendOrder(order)
                .onSuccess {
                    _orderStatus.value =
                        "Order ${order.sampleNo} sent. Check the analyser's order list."
                }
                .onFailure {
                    _orderStatus.value =
                        "Order ${order.sampleNo} saved, send failed: ${it.localizedMessage}"
                }
        }
    }

    init {
        // Load every previously saved result on ViewModel initialization
        val saved = resultStorage.getAllResults()
        Log.i(TAG, "Loaded ${saved.size} saved results from storage")

        // Rebuild the keys from the stored raw messages so duplicates are caught after a restart too.
        saved.forEach { record ->
            runCatching {
                resultKey(
                    HL7Parser.parse(
                        record.rawMessage,
                        device = _uiState.value.deviceType
                    )
                )
            }
                .onSuccess { knownKeys.add(it) }
                .onFailure {
                    Log.w(
                        TAG,
                        "Could not derive key for saved result ${record.id}: ${it.message}"
                    )
                }
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

    /**
     * Starts the TCP link in the selected [tcpRole].
     *  - SERVER: listens on [TCP_PORT]; the analyser connects to this phone. deviceIpAddress is this phone's IP.
     *  - CLIENT: connects to deviceIpAddress:[TCP_PORT], the analyser's IP.
     */
    fun startTcp() {
        disconnect()

        val host = _uiState.value.deviceIpAddress
        val role = _tcpRole.value
        Log.i(TAG, "startTcp() requested: role=$role port=$TCP_PORT host=$host")

        val onError: (Throwable) -> Unit = { error ->
            // The link recovers on its own (re-bind / reconnect with backoff), so don't stop it here.
            reportError(error)
        }
        val onResult: suspend (Hl7Message) -> Unit = { hl7Msg ->
            Log.i(TAG, "Tcp onResult received Hl7Message raw length=${hl7Msg.raw.length}")
            handleHl7Message(hl7Msg)
        }

        val link: AnalyserLink = when (role) {
            TcpRole.SERVER -> TcpServer(
                port = TCP_PORT,
                onError = onError,
                onWorklistRequest = {
                    Log.d(TAG, "worklist requested")
                    val item = worklistQueue.poll()
                    Log.d(TAG, "worklist item: $item")
                    item
                },
                onResult = onResult
            )

            TcpRole.CLIENT -> TcpClient(
                host = host,
                port = TCP_PORT,
                onError = onError,
                onOrderQuery = { sampleNo -> orders[sampleNo] },
                onResult = onResult
            )
        }
        tcpLink = link

        (link as? BaseTcpLink)?.worklistProvider = { sampleNo -> takeWorklist() }

        observeState(link.state, host = host, port = TCP_PORT)
        link.start(viewModelScope, _uiState.value.deviceType)
    }

    /** Starts talking to the analyser over a USB-to-RS232 adapter (phone must support USB OTG). */
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
        client.start(viewModelScope, deviceType = _uiState.value.deviceType)
    }

    private fun observeState(state: StateFlow<TcpConnectionState>, host: String, port: Int) {
        stateJob = viewModelScope.launch {
            state.collect { s ->
                Log.d(TAG, "Link state collected: $s")
                _uiState.update { current ->
                    val newConnState = when (s) {
                        TcpConnectionState.DISCONNECTED -> {
                            current.connectionState as? DeviceConnectionState.Error
                                ?: DeviceConnectionState.Disconnected
                        }
                        // CONNECTING = listening / connecting / waiting for the adapter
                        TcpConnectionState.CONNECTING -> DeviceConnectionState.Connecting
                        // CONNECTED = analyser connected (TCP) or serial port open
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
        tcpLink?.stop()
        tcpLink = null
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
        _uiState.update { it.copy(deviceIpAddress = ipAddress) }
    }

    fun updateOpenPort(openPort: List<Int>) {
        _uiState.update { it.copy(openPorts = openPort) }
    }

    /**
     * Identity of one test result: device ID (OBR-4) + sample number (OBR-3) + test time (OBR-7).
     * Falls back to the message time (MSH-7) if the analyser leaves OBR-7 empty.
     */
    private fun resultKey(msg: Hl7Message): String {
        val obr = msg.obr
        val testTime = obr?.observationTime?.takeIf { it.isNotBlank() } ?: msg.msh.timestamp
        return listOf(obr?.deviceId.orEmpty(), obr?.sampleNo.orEmpty(), testTime).joinToString("|")
    }

    private fun handleHl7Message(msg: Hl7Message) {
        Log.i(
            TAG,
            "handle Hl7 Message: pid=${msg.pid?.patientId}, name=${msg.pid?.name}, obxCount=${msg.obxs.size}"
        )

        // The analyser can re-upload a result from its history screen. Keep the first copy only.
        // The ACK is still AA (this function returns normally), so the analyser stops retrying.
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
                code = obx.displayName,
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
        // If this fails, forget the key so the analyser's retry (it gets AR 207) is not dropped as a duplicate.
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

    fun setConnectionMode(mode: ConnectionMode) {
        _uiState.update { it.copy(connectionMode = mode) }
    }

    fun setDeviceType(deviceType: DeviceType) {
        _uiState.update { it.copy(deviceType = deviceType) }
    }

    fun addSeamatyOrder(order: WorklistOrder) {
        if (order.sampleNo.isBlank()) {
            _orderStatus.value = "Sample number is required"
            return
        }
        addWorklist(order)
        // same sample number replaces the earlier entry
        _pendingWorklist.update { list -> list.filterNot { it.sampleNo == order.sampleNo } + order }
        _orderStatus.value =
            "Worklist ${order.sampleNo} queued. It is sent when the analyser requests it."

        viewModelScope.launch {
            //sendWorklistOrder()
        }
    }

    fun removeSeamatyOrder(sampleNo: String) {
        _pendingWorklist.update { list -> list.filterNot { it.sampleNo == sampleNo } }
    }

    /** Called by TcpServer when the analyser asks. Returns the oldest pending item and removes it, or null if none. */
    private fun takeWorklist(): WorklistOrder? {
        Log.d(TAG,"Take work list requested")
        return worklistQueue.poll()
    }

    private suspend fun sendWorklistOrder(){
        Log.d(TAG,"Sending work list manually")
        val worklist = takeWorklist()
        if(worklist !=null){
            tcpLink?.pushWorklist(worklist)
        }
        else{
            Log.d(TAG,"work list empty while sending manually")
        }
    }

    override fun onCleared() {
        Log.d(TAG, "onCleared() called")
        disconnect()
    }
}