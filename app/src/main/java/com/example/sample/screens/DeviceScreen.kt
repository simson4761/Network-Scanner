package com.example.sample.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sample.data.ConnectionMode
import com.example.sample.data.DeviceConnectionState
import com.example.sample.data.DeviceType
import com.example.sample.data.PatientInfo
import com.example.sample.data.TcpRole
import com.example.sample.data.TestResultRecord
import com.example.sample.data.ozelle.PendingOrder
import com.example.sample.data.seamaty.WorklistOrder
import com.example.sample.viewModel.DeviceViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

// --- Composable Screen ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    ipAddress: String = "",
    openPorts: List<Int> = emptyList(),
    viewModel: DeviceViewModel = viewModel(),
    onConnectClick: () -> Unit = { viewModel.connect() },
    onDisconnectClick: () -> Unit = { viewModel.disconnect() },
    onModeSelected: (ConnectionMode) -> Unit = { viewModel.setConnectionMode(it) },
    onDeviceTypeSelected: (DeviceType) -> Unit = { viewModel.setDeviceType(it) },
    onRoleSelected: (TcpRole) -> Unit = { viewModel.setTcpRole(it) },
    onClearLogsClick: () -> Unit = { viewModel.clearLogs() },
    onClearSavedResultsClick: () -> Unit = { viewModel.clearSavedResults() }
) {

    val context = LocalContext.current
    val permission = Manifest.permission.ACCESS_FINE_LOCATION

    var localIp by remember { mutableStateOf("...") }
    var wifiName by remember { mutableStateOf("...")}

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        wifiName = if (granted) {
            getConnectedWifiName(context)
        } else {
            "Location permission denied"
        }
    }

    LaunchedEffect(Unit) {
        if (ipAddress.isNotBlank()) {
            viewModel.updateIpAddress(ipAddress)
        }
        if (openPorts.isNotEmpty()) {
            viewModel.updateOpenPort(openPorts)
        }
        localIp = withContext(Dispatchers.IO) { getLocalIpAddress() } ?: "Not on a local network"

        if (
            ContextCompat.checkSelfPermission(
                context,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            wifiName = getConnectedWifiName(context)
        } else {
            permissionLauncher.launch(permission)
        }
        // The analyzer connects to this phone, so show this phone's IP in the status text.
        if (ipAddress.isBlank() && localIp.isNotBlank() && localIp[0].isDigit()) {
            viewModel.updateIpAddress(localIp)
        }
    }
    val uiState by viewModel.uiState.collectAsState()

    val orderStatus by viewModel.orderStatus.collectAsState()

    val pendingWorklist by viewModel.pendingWorklist.collectAsState()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("HL7 Device Monitor") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Connection Mode",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        ModeSelector(
                            selected = uiState.connectionMode,
                            onSelected = onModeSelected
                        )
                    }
                    if (uiState.connectionMode == ConnectionMode.TCP) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "TCP Mode",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TcpRoleSelector(
                                selected = uiState.tcpRole,
                                onSelected = onRoleSelected
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Device Type",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        DeviceTypeSelector(
                            selected = uiState.deviceType,
                            onSelected = onDeviceTypeSelected
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Connection Status Card
            ConnectionStatusCard(
                mode = uiState.connectionMode,
                role = uiState.tcpRole,
                deviceType = uiState.deviceType,
                state = uiState.connectionState,
                localIp = localIp,
                networkName = wifiName,
                onConnect = onConnectClick,
                onDisconnect = onDisconnectClick
            )

            // 2. Latest result received in this session
            ParsedDataCard(patientInfo = uiState.patientInfo)

            // 3. Previous results (everything saved on the phone, newest first)
            PreviousResultsCard(
                results = uiState.savedResults,
                onClear = onClearSavedResultsClick
            )

            if (uiState.deviceType == DeviceType.SEAMATY) {
                SeamatyOrderCard(
                    status = orderStatus,
                    pending = pendingWorklist,
                    onAdd = viewModel::addSeamatyOrder,
                    onRemove = viewModel::removeSeamatyOrder
                )
            } else {
                OrderCard(
                    status = orderStatus,
                    connected = uiState.connectionState is DeviceConnectionState.Connected,
                    onSave = viewModel::saveOrder,
                    onSend = viewModel::sendOrder
                )
            }

            // 4. Raw HL7 Stream Log
            RawStreamCard(
                messages = uiState.rawMessages,
                onClear = onClearLogsClick
            )
        }
    }
}

/**
 * Two-option selector with a highlight box that slides to the selected option.
 * Only the highlight moves; nothing else on the screen changes layout.
 */
@Composable
fun ModeSelector(
    selected: ConnectionMode,
    onSelected: (ConnectionMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val modes = ConnectionMode.entries

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp)
    ) {
        val segmentWidth = maxWidth / modes.size
        val indicatorOffset by animateDpAsState(
            targetValue = segmentWidth * selected.ordinal,
            animationSpec = tween(durationMillis = 250),
            label = "modeIndicatorOffset"
        )

        // The sliding highlight
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(segmentWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.primary)
        )

        // The labels, drawn over the highlight
        Row(modifier = Modifier.fillMaxSize()) {
            modes.forEach { mode ->
                val textColor by animateColorAsState(
                    targetValue = if (mode == selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(durationMillis = 250),
                    label = "modeLabelColor"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSelected(mode) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = mode.label,
                        color = textColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

/**
 * Two-option selector for DeviceType (Seamaty vs Hematology) with a highlight box that slides to the selected option.
 */
@Composable
fun DeviceTypeSelector(
    selected: DeviceType,
    onSelected: (DeviceType) -> Unit,
    modifier: Modifier = Modifier
) {
    val types = DeviceType.entries

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp)
    ) {
        val segmentWidth = maxWidth / types.size
        val indicatorOffset by animateDpAsState(
            targetValue = segmentWidth * selected.ordinal,
            animationSpec = tween(durationMillis = 250),
            label = "deviceTypeIndicatorOffset"
        )

        // The sliding highlight
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(segmentWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.primary)
        )

        // The labels, drawn over the highlight
        Row(modifier = Modifier.fillMaxSize()) {
            types.forEach { type ->
                val textColor by animateColorAsState(
                    targetValue = if (type == selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(durationMillis = 250),
                    label = "deviceTypeLabelColor"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSelected(type) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = type.label,
                        color = textColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

/**
 * Two-option selector for TCP Mode (Server vs Client) with a highlight box that slides to the selected option.
 */
@Composable
fun TcpRoleSelector(
    selected: TcpRole,
    onSelected: (TcpRole) -> Unit,
    modifier: Modifier = Modifier
) {
    val roles = TcpRole.entries

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp)
    ) {
        val segmentWidth = maxWidth / roles.size
        val indicatorOffset by animateDpAsState(
            targetValue = segmentWidth * selected.ordinal,
            animationSpec = tween(durationMillis = 250),
            label = "tcpRoleIndicatorOffset"
        )

        // The sliding highlight
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(segmentWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.primary)
        )

        // The labels, drawn over the highlight
        Row(modifier = Modifier.fillMaxSize()) {
            roles.forEach { role ->
                val textColor by animateColorAsState(
                    targetValue = if (role == selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(durationMillis = 250),
                    label = "tcpRoleLabelColor"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSelected(role) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = role.label,
                        color = textColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ConnectionStatusCard(
    mode: ConnectionMode,
    role: TcpRole = TcpRole.SERVER,
    deviceType: DeviceType,
    state: DeviceConnectionState,
    localIp: String,
    networkName : String,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Use weight(1f) so this row respects the button's space
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val (indicatorColor, statusText) = when (state) {
                        is DeviceConnectionState.Connected -> Color(0xFF4CAF50) to "Connected (${state.host}:${state.port})"
                        is DeviceConnectionState.Connecting -> Color(0xFFFFC107) to
                                if (mode == ConnectionMode.TCP) {
                                    if (role == TcpRole.SERVER) "Listening, waiting for analyzer..."
                                    else "Connecting to analyzer..."
                                } else "Waiting for USB adapter..."
                        is DeviceConnectionState.Disconnected -> Color(0xFF9E9E9E) to "Stopped"
                        is DeviceConnectionState.Error -> Color(0xFFF4433E) to "Error: ${state.message}"
                    }

                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(indicatorColor)
                    )

                    // 2. Prevent text overflow from crushing layout
                    Text(
                        text = statusText,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Optional: Keep a minimum separation between text and button
                Spacer(modifier = Modifier.width(8.dp))

                // Action Button
                when (state) {
                    is DeviceConnectionState.Connected, is DeviceConnectionState.Connecting -> {
                        OutlinedButton(onClick = onDisconnect) {
                            Text("Stop")
                        }
                    }
                    else -> {
                        Button(onClick = onConnect) {
                            Text("Start")
                        }
                    }
                }
            }

            Text(
                text = when (mode) {
                    ConnectionMode.TCP -> when (role) {
                        TcpRole.SERVER ->
                            "1. Connect the ${deviceType.label} analyzer to the same network as this device ($networkName).\n" +
                            "2. On the analyzer, set the connection mode to Network.\n" +
                            "3. Make sure the analyzer is set to client mode.\n" +
                            "4. Set the LIS server IP to $localIp.\n" +
                            "5. Set the port to ${DeviceViewModel.TCP_PORT}."
                        TcpRole.CLIENT ->
                            "1. Connect the ${deviceType.label} analyzer to the same network as this device ($networkName).\n" +
                            "2. On the analyzer, set the connection mode to Network / LIS Server.\n" +
                            "3. Make sure the analyzer is set to server mode.\n" +
                            "4. Set the analyzer IP address in the app.\n" +
                            "5. Set the port to ${DeviceViewModel.TCP_PORT}."
                    }
                    ConnectionMode.SERIAL ->
                        "1. Connect the ${deviceType.label} analyzer to this device through a USB-RS232 adapter (USB OTG).\n" +
                        "2. On the analyzer, set the baud rate to ${DeviceViewModel.SERIAL_BAUD_RATE}.\n" +
                        "3. Set the frame format to 8N1."
                },
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.outline
            )
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
            Text(text = "Latest Device / Patient Data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
fun PreviousResultsCard(
    results: List<TestResultRecord>,
    onClear: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Previous Results (${results.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (results.isNotEmpty()) {
                    TextButton(onClick = onClear) {
                        Text("Clear Saved")
                    }
                }
            }
            HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)

            if (results.isEmpty()) {
                Text(
                    text = "No results saved yet.",
                    color = Color.Gray,
                    fontSize = 13.sp
                )
            } else {
                Text(
                    text = "Tap a result to see its values.",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
                results.forEach { record ->
                    PreviousResultItem(record = record)
                }
            }
        }
    }
}

/** One saved result. Collapsed it shows a one-line summary; tap to expand the observations. */
@Composable
fun PreviousResultItem(record: TestResultRecord) {
    var expanded by remember(record.id) { mutableStateOf(false) }
    val abnormalCount = record.observations.count { it.flag.isNotBlank() && !it.flag.equals("N", ignoreCase = true) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Patient ID: ${record.patientId}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = record.formattedTime,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Text(
                text = "Name: ${record.patientName}",
                fontSize = 13.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${record.observations.size} results" +
                            if (abnormalCount > 0) ", $abnormalCount abnormal" else "",
                    fontSize = 12.sp,
                    color = if (abnormalCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                )
                Text(
                    text = if (expanded) "Hide" else "Show",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (expanded && record.observations.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                record.observations.forEach { obs ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = obs.code,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Normal
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = obs.displayValue,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            if (obs.flag.isNotBlank()) {
                                Text(
                                    text = "[${obs.flag}]",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = if (obs.flag.contains("H", true) || obs.flag.contains("L", true)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                )
                            }
                        }
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
            .height(280.dp)
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

/**
 * Returns this phone's private IPv4 address (Wi-Fi, or the hotspot address if the phone
 * is sharing its connection), or null if there is none. No permission needed.
 * Cellular addresses are skipped because they are not on the local network.
 */
private fun getLocalIpAddress(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { iface ->
            iface.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .map { iface.name to it.hostAddress }
        }.maxByOrNull { (name, _) ->
            name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")
        }?.second
}.getOrNull()

@Suppress("DEPRECATION")
fun getConnectedWifiName(context: Context): String {
    val wifiManager = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager

    val ssid = wifiManager.connectionInfo.ssid

    return if (
        ssid == null ||
        ssid == WifiManager.UNKNOWN_SSID ) {
        "Wi-Fi name unavailable"
    } else {
        ssid.removeSurrounding("\"")
    }
}

@Composable
fun OrderCard(
    status: String?,
    connected: Boolean,
    onSave: (PendingOrder) -> Unit,
    onSend: (PendingOrder) -> Unit
) {
    var sampleNo by remember { mutableStateOf("") }
    var patientId by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var sex by remember { mutableStateOf("Male") }
    var age by remember { mutableStateOf("") }
    var doctor by remember { mutableStateOf("") }
    var bloodMode by remember { mutableStateOf("W") }
    var testMode by remember { mutableStateOf("CBC") }

    fun build() = PendingOrder(
        sampleNo = sampleNo.trim(),
        patientId = patientId.trim(),
        name = name.trim(),
        sex = sex.trim(),
        ageYears = age.toIntOrNull(),
        doctor = doctor.trim(),
        bloodMode = bloodMode.trim().ifEmpty { "W" },
        testMode = testMode.trim().ifEmpty { "CBC" }
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Create Order", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider()

            OutlinedTextField(
                value = sampleNo, onValueChange = { sampleNo = it },
                label = { Text("Sample number *") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = patientId, onValueChange = { patientId = it },
                    label = { Text("Patient ID") }, singleLine = true, modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = sex, onValueChange = { sex = it },
                    label = { Text("Sex") }, singleLine = true, modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = age, onValueChange = { age = it.filter(Char::isDigit) },
                    label = { Text("Age (yr)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
            OutlinedTextField(
                value = doctor, onValueChange = { doctor = it },
                label = { Text("Doctor") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = bloodMode, onValueChange = { bloodMode = it },
                    label = { Text("Blood mode (W/P)") }, singleLine = true, modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = testMode, onValueChange = { testMode = it },
                    label = { Text("Test mode") }, singleLine = true, modifier = Modifier.weight(1f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onSave(build()) },
                    enabled = sampleNo.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text("Save order") }
                Button(
                    onClick = { onSend(build()) },
                    enabled = connected && sampleNo.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text("Send to analyser") }
            }

            if (status != null) {
                Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}
private data class Opt(val code: String, val label: String) {
    val display get() = if (code.isEmpty() || code == label) label else "$label ($code)"
}

private object SeamatyOptions {
    // Opt(code sent over HL7, label shown on screen)
    val sex = listOf(Opt("Male", "Male"), Opt("Female", "Female"), Opt("Other", "Other"), Opt("Unknown", "Unknown"))
    val ageGroup = listOf(Opt("Adult", "Adult"), Opt("Child", "Child"))
    val race = listOf(
        Opt("White", "White"),
        Opt("African", "African"),
        Opt("Asian", "Asian"),
        Opt("Others", "Others")
    )
    val sampleType = listOf(
        Opt("Plasma", "Plasma"),
        Opt("Serum", "Serum"),
        Opt("Capillary Blood", "Capillary Blood"),
        Opt("Whole Blood", "Whole Blood")
    )
    val testType = listOf("PWC").map { Opt(it, it) }   // TODO: replace with your human panel / reagent type codes
}

private val HL7_RESERVED = setOf('|', '^', '&', '~', '\\', '\r', '\n')

private fun clean(s: String, max: Int) = s.filterNot { it in HL7_RESERVED }.take(max)

@Composable
private fun FixedDropdown(
    label: String,
    selected: String,
    options: List<Opt>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = options.firstOrNull { it.code == selected }?.display ?: selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Text("▾") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Box(Modifier.matchParentSize().clickable { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(o.display) },
                    onClick = { onSelect(o.code); expanded = false }
                )
            }
        }
    }
}

@Composable
fun SeamatyOrderCard(
    status: String?,
    pending: List<WorklistOrder>,
    onAdd: (WorklistOrder) -> Unit,
    onRemove: (String) -> Unit
) {
    var sampleNo by remember { mutableStateOf("123456789") }
    var medicalId by remember { mutableStateOf("12345") }
    var patientName by remember { mutableStateOf("SIMSON") }
    var age by remember { mutableStateOf("25") }
    var ageGroup by remember { mutableStateOf("Adult") }
    var sex by remember { mutableStateOf("Male") }
    var race by remember { mutableStateOf("White") }
    var sampleType by remember { mutableStateOf("Serum") }
    var department by remember { mutableStateOf("DEVELOPMENT") }
    var testType by remember { mutableStateOf("PWC") }
    var doctor by remember { mutableStateOf("DOCTOR") }
    var printNotes by remember { mutableStateOf("notes") }

    val duplicate = pending.any { it.sampleNo == sampleNo.trim() }
    val ageValid = age.toIntOrNull()?.let { it in 0..150 } == true

    fun build() = WorklistOrder(
        sampleNo = sampleNo.trim(),
        medicalId = medicalId.trim(),
        patientName = patientName.trim(),
        age = age.toInt().toString(),
        ageGroup = ageGroup,
        sex = sex,
        race = race,
        lisServerId = "LIS",
        sampleType = sampleType,
        department = department.trim(),
        testType = testType,
        doctor = doctor.trim(),
        printNotes = printNotes.trim()
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Create Worklist Order", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider()

            OutlinedTextField(
                value = sampleNo, onValueChange = { sampleNo = clean(it, 22) },
                label = { Text("Sample number *") }, singleLine = true,
                isError = duplicate,
                supportingText = if (duplicate) ({ Text("Already in the worklist") }) else null,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = medicalId, onValueChange = { medicalId = clean(it, 20) },
                    label = { Text("Medical record no.") }, singleLine = true, modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = patientName, onValueChange = { patientName = clean(it, 48) },
                    label = { Text("Patient name") }, singleLine = true, modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = age, onValueChange = { age = it.filter(Char::isDigit).take(3) },
                    label = { Text("Age *") }, singleLine = true,
                    isError = age.isNotEmpty() && !ageValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                FixedDropdown("Age group", ageGroup, SeamatyOptions.ageGroup, { ageGroup = it }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FixedDropdown("Sex", sex, SeamatyOptions.sex, { sex = it }, Modifier.weight(1f))
                FixedDropdown("Race", race, SeamatyOptions.race, { race = it }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FixedDropdown("Sample type", sampleType, SeamatyOptions.sampleType, { sampleType = it }, Modifier.weight(1f))
                FixedDropdown("Test type", testType, SeamatyOptions.testType, { testType = it }, Modifier.weight(1f))
            }
            OutlinedTextField(
                value = department, onValueChange = { department = clean(it, 40) },
                label = { Text("Department") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = doctor, onValueChange = { doctor = clean(it, 60) },
                label = { Text("Operator") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = printNotes, onValueChange = { printNotes = clean(it, 100) },
                label = { Text("Print notes") }, modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    onAdd(build())
                    sampleNo = ""; medicalId = ""; patientName = ""; age = ""; printNotes = ""
                },
                enabled = sampleNo.isNotBlank() && !duplicate && ageValid,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Add to worklist") }

            if (status != null) {
                Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
            }

            HorizontalDivider()
            Text("Pending worklist (${pending.size})", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            if (pending.isEmpty()) {
                Text("Nothing pending. The analyser gets \"no worklist\" when it asks.", color = Color.Gray, fontSize = 12.sp)
            } else {
                pending.forEach { o ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${o.sampleNo} · ${o.patientName.ifBlank { "-" }} (${o.age}${o.ageGroup}, ${o.sex})",
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(onClick = { onRemove(o.sampleNo) }) { Text("Remove") }
                    }
                }
            }
        }
    }
}