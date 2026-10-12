package com.example.sample.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sample.data.DeviceConnectionState
import com.example.sample.data.PatientInfo
import com.example.sample.viewModel.DeviceViewModel


// --- Composable Screen ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    ipAddress : String = "",
    openPorts : List<Int> = emptyList(),
    viewModel: DeviceViewModel = viewModel(),
    onConnectClick: () -> Unit = { viewModel.connect() },
    onDisconnectClick: () -> Unit = { viewModel.disconnect() },
    onClearLogsClick: () -> Unit = { viewModel.clearLogs() }
) {

    LaunchedEffect(Unit) {
        if(ipAddress.isNotBlank()){
            viewModel.updateIpAddress(ipAddress)
        }
        if(openPorts.isNotEmpty()){
            viewModel.updateOpenPort(openPorts)
        }
    }
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HL7 Device Monitor") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Connection Status Card
            ConnectionStatusCard(
                state = uiState.connectionState,
                onConnect = onConnectClick,
                onDisconnect = onDisconnectClick
            )

            // 2. Parsed Clinical / Device Data Dashboard
            ParsedDataCard(patientInfo = uiState.patientInfo)

            // 3. Raw HL7 Stream Log
            RawStreamCard(
                messages = uiState.rawMessages,
                onClear = onClearLogsClick
            )
        }
    }
}

@Composable
fun ConnectionStatusCard(
    state: DeviceConnectionState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val (indicatorColor, statusText) = when (state) {
                        is DeviceConnectionState.Connected -> Color(0xFF4CAF50) to "Connected (${state.host}:${state.port})"
                        is DeviceConnectionState.Connecting -> Color(0xFFFFC107) to "Connecting..."
                        is DeviceConnectionState.Disconnected -> Color(0xFF9E9E9E) to "Disconnected"
                        is DeviceConnectionState.Error -> Color(0xFFF4433E) to "Error: ${state.message}"
                    }

                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(indicatorColor)
                    )
                    Text(text = statusText, fontWeight = FontWeight.SemiBold)
                }

                when (state) {
                    is DeviceConnectionState.Connected, is DeviceConnectionState.Connecting -> {
                        OutlinedButton(onClick = onDisconnect) {
                            Text("Disconnect")
                        }
                    }
                    else -> {
                        Button(onClick = onConnect) {
                            Text("Connect")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ParsedDataCard(patientInfo: PatientInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Live Device / Patient Data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Patient ID: ${patientInfo.patientId}")
                Text("Name: ${patientInfo.fullName}")
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text("Observations (OBX):", fontWeight = FontWeight.Medium, fontSize = 14.sp)
            if (patientInfo.observations.isEmpty()) {
                Text("No observations received yet.", color = Color.Gray, fontSize = 13.sp)
            } else {
                patientInfo.observations.forEach { (key, value) ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(key, fontSize = 13.sp)
                        Text(value, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun RawStreamCard(messages: List<String>, onClear: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Raw HL7 Stream", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = onClear) {
                    Text("Clear Logs")
                }
            }
            HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1E1E1E), shape = RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(messages) { msg ->
                    Text(
                        text = msg,
                        color = Color(0xFF00FF66),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}