package com.example.sample.viewModel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sample.network.DeviceUiState
import com.example.sample.network.LanDeviceScanner
import com.example.sample.network.NetworkInfoProvider
import com.example.sample.network.NetworkScanUiState
import com.example.sample.network.PortScanner
import com.example.sample.network.PortState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

class NetworkScanViewModel(application: Application) : AndroidViewModel(application) {
    private val networkInfoProvider = NetworkInfoProvider(application)
    private val deviceScanner = LanDeviceScanner(application)
    private val portScanner = PortScanner()

    var uiState by mutableStateOf(NetworkScanUiState())
        private set

    fun startScan() {
        viewModelScope.launch {
            uiState = uiState.copy(isScanning = true, errorMessage = null, devices = emptyList())

            val networkResult = networkInfoProvider.getNetworkInfo()
            if (networkResult.isFailure) {
                uiState = uiState.copy(
                    isScanning = false,
                    errorMessage = networkResult.exceptionOrNull()?.message ?: "Unknown network error"
                )
                return@launch
            }

            val networkInfo = networkResult.getOrThrow()

            // Common ports to check during discovery
            val targetPorts = listOf(21, 22, 23, 80, 443, 445, 3000, 8080, 8443)

            deviceScanner.scanToList(networkInfo)
                .flowOn(Dispatchers.IO)
                .collect { devices ->
                    devices.forEach { discoveredDevice ->
                        val currentList = uiState.devices.toMutableList()
                        val existingIndex = currentList.indexOfFirst { it.device.ipAddress == discoveredDevice.ipAddress }

                        if (existingIndex == -1) {
                            currentList.add(
                                DeviceUiState(
                                    device = discoveredDevice,
                                    isScanningPorts = true
                                )
                            )
                            uiState = uiState.copy(devices = currentList.sortedBy { parseIpToLong(it.device.ipAddress) })

                            // Scan open ports for this specific device in background
                            launch(Dispatchers.IO) {
                                val openPorts = portScanner.scanAll(
                                    ipAddress = discoveredDevice.ipAddress,
                                    ports = targetPorts,
                                    network = networkInfo.network
                                ).filter { it.state == PortState.OPEN }.map { it.port }

                                val updatedList = uiState.devices.map {
                                    if (it.device.ipAddress == discoveredDevice.ipAddress) {
                                        it.copy(openPorts = openPorts, isScanningPorts = false)
                                    } else {
                                        it
                                    }
                                }.sortedBy { parseIpToLong(it.device.ipAddress) }
                                uiState = uiState.copy(devices = updatedList)
                            }
                        } else {
                            // Update existing entry with newly discovered device metadata (hostname, MAC address)
                            val existingItem = currentList[existingIndex]
                            currentList[existingIndex] = existingItem.copy(device = discoveredDevice)
                            uiState = uiState.copy(devices = currentList.sortedBy { parseIpToLong(it.device.ipAddress) })
                        }
                    }
                }

            uiState = uiState.copy(isScanning = false)
        }
    }

    private fun parseIpToLong(ip: String): Long {
        val parts = ip.split('.')
        if (parts.size != 4) return 0L
        return parts.fold(0L) { acc, part -> (acc shl 8) + (part.toLongOrNull() ?: 0L) }
    }
}
