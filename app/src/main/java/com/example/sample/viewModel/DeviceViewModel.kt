package com.example.sample.viewModel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sample.data.DeviceConnectionState
import com.example.sample.data.PatientInfo
import com.example.sample.data.TcpConnectionState
import com.example.sample.data.DeviceScreenUiState
import com.example.sample.tcp.TcpClient
import com.example.sample.data.hl7.Hl7Message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch


class DeviceViewModel : ViewModel() {

    companion object {
        private const val TAG = "DeviceViewModel"
    }

    private val _uiState = MutableStateFlow(DeviceScreenUiState())
    val uiState: StateFlow<DeviceScreenUiState> = _uiState.asStateFlow()

    private var tcpClient: TcpClient? = null

    fun connect() {

        disconnect()

        val host = _uiState.value.deviceIpAddress
        val port =  _uiState.value.openPorts.getOrNull(0) ?: 0

        Log.i(TAG, "connect() requested for host=$host, port=$port")

        val client = TcpClient(
            host = host,
            port = port,
            onError = { error ->
                Log.e(TAG, "TcpClient onError callback: ${error.localizedMessage}", error)
                _uiState.update {
                    it.copy(
                        connectionState = DeviceConnectionState.Error(
                            error.localizedMessage ?: "Connection error"
                        )
                    )
                }
                tcpClient?.stop()
            },
            onResult = { hl7Msg ->
                Log.i(TAG, "TcpClient onResult received Hl7Message raw length=${hl7Msg.raw.length}")
                handleHl7Message(hl7Msg)
            }
        )
        tcpClient = client

        viewModelScope.launch {
            client.state.collect { state ->
                Log.d(TAG, "TcpClient state collected: $state")
                _uiState.update { current ->
                    val newConnState = when (state) {
                        TcpConnectionState.DISCONNECTED -> {
                            current.connectionState as? DeviceConnectionState.Error ?: DeviceConnectionState.Disconnected
                        }
                        TcpConnectionState.CONNECTING -> DeviceConnectionState.Connecting
                        TcpConnectionState.CONNECTED -> DeviceConnectionState.Connected(host, port)
                    }
                    current.copy(connectionState = newConnState)
                }
            }
        }

        client.start(viewModelScope)
    }

    fun disconnect() {
        Log.i(TAG, "disconnect() requested")
        tcpClient?.stop()
        tcpClient = null
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

    fun updateIpAddress(ipAddress : String){
        _uiState.update {
            it.copy(
                deviceIpAddress = ipAddress
            )
        }
    }

    fun updateOpenPort(openPort : List<Int>){
        _uiState.update {
            it.copy(
                openPorts = openPort
            )
        }
    }

    private fun handleHl7Message(msg: Hl7Message) {
        Log.i(TAG, "handle Hl7 Message: pid=${msg.pid?.patientId}, name=${msg.pid?.name}, obxCount=${msg.obxs.size}")
        _uiState.update { current ->
            val updatedRawMessages = current.rawMessages + msg.raw

            val currentPatient = current.patientInfo
            val newPatientId = msg.pid?.patientId?.takeIf { it.isNotBlank() } ?: currentPatient.patientId
            val newFullName = msg.pid?.name?.takeIf { it.isNotBlank() } ?: currentPatient.fullName

            val updatedObs = currentPatient.observations.toMutableMap()
            msg.obxs.forEach { obx ->
                if (obx.code.isNotBlank()) {
                    val formattedVal = if (obx.unit.isNotBlank()) {
                        "${obx.value} ${obx.unit}"
                    } else {
                        obx.value
                    }
                    updatedObs[obx.code] = formattedVal
                    Log.d(TAG, "Added observation: ${obx.code} -> $formattedVal")
                }
            }

            current.copy(
                rawMessages = updatedRawMessages,
                patientInfo = PatientInfo(
                    patientId = newPatientId,
                    fullName = newFullName,
                    observations = updatedObs
                )
            )
        }
    }

    override fun onCleared() {
        Log.d(TAG, "onCleared() called")
        disconnect()
    }
}
