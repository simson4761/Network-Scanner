package com.example.sample.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.sample.network.DeviceUiState
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import com.example.sample.viewModel.NetworkScanViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    viewModel: NetworkScanViewModel,
    onDeviceSelected: (ipAddress: String, openPorts: String) -> Unit
) {
    val state = viewModel.uiState

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Local Network Scanner") },
                actions = {
                    Button(
                        onClick = { viewModel.startScan() },
                        enabled = !state.isScanning,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(if (state.isScanning) "Scanning..." else "Scan")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                state.errorMessage != null -> {
                    Text(
                        text = "Error: ${state.errorMessage}",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp)
                    )
                }
                state.devices.isEmpty() && state.isScanning -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                state.devices.isEmpty() -> {
                    Text(
                        text = "No devices found. Tap 'Scan' to begin.",
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(state.devices) { deviceState ->
                            DeviceCard(
                                deviceState = deviceState,
                                onClick = {
                                    val ip = deviceState.device.ipAddress
                                    val portsStr = deviceState.openPorts.joinToString(",")
                                    // URL encode params safely for Navigation passing
                                    val encodedIp = URLEncoder.encode(ip, StandardCharsets.UTF_8.toString())
                                    val encodedPorts = URLEncoder.encode(portsStr, StandardCharsets.UTF_8.toString())
                                    onDeviceSelected(encodedIp, encodedPorts)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceCard(deviceState: DeviceUiState, onClick: () -> Unit) {
    val deviceName = deviceState.device.hostname
        ?: deviceState.device.advertisedServices.firstOrNull()?.substringBefore(" (")
        ?: "Device (${deviceState.device.ipAddress})"

    val macText = deviceState.device.macAddress
        ?: "Restricted (Android 10+ Privacy)"

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = deviceName,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "IP: ${deviceState.device.ipAddress}",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "MAC: $macText",
                style = MaterialTheme.typography.bodySmall,
                color = if (deviceState.device.macAddress != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = "Open Ports:", style = MaterialTheme.typography.bodySmall)
                if (deviceState.isScanningPorts) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else if (deviceState.openPorts.isEmpty()) {
                    Text(text = "None detected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                } else {
                    Text(
                        text = deviceState.openPorts.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}