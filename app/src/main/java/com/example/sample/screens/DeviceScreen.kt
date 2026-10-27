package com.example.sample.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.example.sample.data.seamaty.WorklistOrder
import com.example.sample.viewModel.DeviceViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

// --- Design tokens ---
private val CardShape = RoundedCornerShape(16.dp)
private val FieldShape = RoundedCornerShape(10.dp)
private val StatusGreen = Color(0xFF2E9E5B)
private val StatusAmber = Color(0xFFE0A100)
private val StatusGrey = Color(0xFF9AA0A6)
private val StatusRed = Color(0xFFD93025)

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
    var wifiName by remember { mutableStateOf("...") }

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

    // Seamaty is server-only: if the role was left on Client from another device, reset it.
    LaunchedEffect(uiState.deviceType, uiState.tcpRole) {
        if (uiState.deviceType == DeviceType.SEAMATY && uiState.tcpRole != TcpRole.SERVER) {
            onRoleSelected(TcpRole.SERVER)
        }
    }

    val orderStatus by viewModel.orderStatus.collectAsState()

    val pendingWorklist by viewModel.pendingWorklist.collectAsState()

    val status = statusUi(uiState.connectionState, uiState.connectionMode, uiState.tcpRole)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "HL7 Device Monitor",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                actions = {
                    StatusPill(color = status.color, text = status.shortText)
                    Spacer(Modifier.width(16.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Setup (mode / role / device)
            SetupCard(
                connectionMode = uiState.connectionMode,
                tcpRole = uiState.tcpRole,
                deviceType = uiState.deviceType,
                onModeSelected = onModeSelected,
                onRoleSelected = onRoleSelected,
                onDeviceTypeSelected = onDeviceTypeSelected
            )

            // 2. Connection status
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

            // 3. Latest result received in this session
            ParsedDataCard(patientInfo = uiState.patientInfo)

            // 4. Previous results (everything saved on the phone, newest first)
            PreviousResultsCard(
                results = uiState.savedResults,
                onClear = onClearSavedResultsClick
            )

            // 5. Orders
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
                    viewModel = viewModel
                )
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { viewModel.fetchOrder() },
                shape = FieldShape
            ) {
                Text("Fetch report status")
            }

            // 6. Raw HL7 Stream Log
            RawStreamCard(
                messages = uiState.rawMessages,
                onClear = onClearLogsClick
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Shared building blocks
// ---------------------------------------------------------------------------

/** Flat card: no elevation, hairline border. */
@Composable
private fun Panel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        content()
    }
}

@Composable
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        action?.invoke()
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun StatusPill(color: Color, text: String) {
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.12f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(
                text = text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
        }
    }
}

@Composable
private fun KeyValueRow(key: String, value: String, bold: Boolean = true) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = key,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    isError: Boolean = false,
    supportingText: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = FieldShape,
        modifier = modifier
    )
}

/** Segmented control with a highlight that slides to the selected option. */
@Composable
private fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedIndex = options.indexOf(selected).coerceAtLeast(0)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(FieldShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp)
    ) {
        val segmentWidth = maxWidth / options.size
        val indicatorOffset by animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = tween(durationMillis = 220),
            label = "segmentIndicatorOffset"
        )

        // The sliding highlight
        Surface(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(segmentWidth)
                .fillMaxHeight(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primary
        ) {}

        // The labels, drawn over the highlight
        Row(modifier = Modifier.fillMaxSize()) {
            options.forEach { option ->
                val textColor by animateColorAsState(
                    targetValue = if (option == selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(durationMillis = 220),
                    label = "segmentLabelColor"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSelected(option) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label(option),
                        color = textColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

// Kept as public entry points so existing callers still compile.
@Composable
fun ModeSelector(
    selected: ConnectionMode,
    onSelected: (ConnectionMode) -> Unit,
    modifier: Modifier = Modifier
) = SegmentedControl(ConnectionMode.entries, selected, { it.label }, onSelected, modifier)

@Composable
fun DeviceTypeSelector(
    selected: DeviceType,
    onSelected: (DeviceType) -> Unit,
    modifier: Modifier = Modifier
) = SegmentedControl(DeviceType.entries, selected, { it.label }, onSelected, modifier)

@Composable
fun TcpRoleSelector(
    selected: TcpRole,
    onSelected: (TcpRole) -> Unit,
    modifier: Modifier = Modifier
) = SegmentedControl(TcpRole.entries, selected, { it.label }, onSelected, modifier)

// ---------------------------------------------------------------------------
// Status helpers
// ---------------------------------------------------------------------------

private data class StatusUi(val color: Color, val text: String, val shortText: String)

private fun statusUi(
    state: DeviceConnectionState,
    mode: ConnectionMode,
    role: TcpRole
): StatusUi = when (state) {
    is DeviceConnectionState.Connected ->
        StatusUi(StatusGreen, "Connected (${state.host}:${state.port})", "Connected")

    is DeviceConnectionState.Connecting ->
        StatusUi(
            StatusAmber,
            if (mode == ConnectionMode.TCP) {
                if (role == TcpRole.SERVER) "Listening, waiting for analyzer..."
                else "Connecting to analyzer..."
            } else "Waiting for USB adapter...",
            "Connecting"
        )

    is DeviceConnectionState.Disconnected -> StatusUi(StatusGrey, "Stopped", "Stopped")
    is DeviceConnectionState.Error -> StatusUi(StatusRed, "Error: ${state.message}", "Error")
}

// ---------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------

@Composable
private fun SetupCard(
    connectionMode: ConnectionMode,
    tcpRole: TcpRole,
    deviceType: DeviceType,
    onModeSelected: (ConnectionMode) -> Unit,
    onRoleSelected: (TcpRole) -> Unit,
    onDeviceTypeSelected: (DeviceType) -> Unit
) {
    Panel {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldLabel("Connection")
                ModeSelector(selected = connectionMode, onSelected = onModeSelected)
            }
            // Seamaty only works as a server; Hematology can be server or client.
            if (connectionMode == ConnectionMode.TCP && deviceType != DeviceType.SEAMATY) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FieldLabel("TCP mode")
                    TcpRoleSelector(selected = tcpRole, onSelected = onRoleSelected)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldLabel("Device")
                DeviceTypeSelector(selected = deviceType, onSelected = onDeviceTypeSelected)
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
    networkName: String,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val status = statusUi(state, mode, role)
    var showSteps by remember { mutableStateOf(false) }

    Panel {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(status.color)
                    )
                    Text(
                        text = status.text,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                when (state) {
                    is DeviceConnectionState.Connected, is DeviceConnectionState.Connecting -> {
                        OutlinedButton(onClick = onDisconnect, shape = FieldShape) { Text("Stop") }
                    }

                    else -> {
                        Button(onClick = onConnect, shape = FieldShape) { Text("Start") }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Key network details at a glance
            if (mode == ConnectionMode.TCP) {
                KeyValueRow("This device", localIp)
                KeyValueRow("Network", networkName)
                KeyValueRow("Port", DeviceViewModel.TCP_PORT.toString())
            } else {
                KeyValueRow("Baud rate", DeviceViewModel.SERIAL_BAUD_RATE.toString())
                KeyValueRow("Frame format", "8N1")
            }

            TextButton(
                onClick = { showSteps = !showSteps },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                Text(
                    text = if (showSteps) "Hide setup steps" else "Show setup steps",
                    fontSize = 13.sp
                )
            }

            if (showSteps) {
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ParsedDataCard(patientInfo: PatientInfo) {
    Panel {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            SectionHeader("Latest result")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(
                modifier = Modifier.padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                KeyValueRow("Patient ID", patientInfo.patientId)
                KeyValueRow("Name", patientInfo.fullName)

                Spacer(Modifier.height(4.dp))
                FieldLabel("Observations")
                if (patientInfo.observations.isEmpty()) {
                    Text(
                        "No observations received yet.",
                        color = MaterialTheme.colorScheme.outline,
                        fontSize = 13.sp
                    )
                } else {
                    patientInfo.observations.forEach { (key, value) ->
                        KeyValueRow(key, value)
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
    Panel {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            SectionHeader(
                title = "Previous results (${results.size})",
                action = {
                    if (results.isNotEmpty()) {
                        TextButton(onClick = onClear) { Text("Clear") }
                    }
                }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (results.isEmpty()) {
                Text(
                    text = "No results saved yet.",
                    color = MaterialTheme.colorScheme.outline,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                results.forEachIndexed { index, record ->
                    PreviousResultItem(record = record)
                    if (index < results.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

/** One saved result. Collapsed it shows a summary; tap to expand the observations. */
@Composable
fun PreviousResultItem(record: TestResultRecord) {
    var expanded by remember(record.id) { mutableStateOf(false) }
    val abnormalCount = record.observations.count {
        it.flag.isNotBlank() && !it.flag.equals(
            "N",
            ignoreCase = true
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 12.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = record.patientName.ifBlank { "Unnamed patient" },
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = record.formattedTime,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "ID ${record.patientId}  ·  ${record.observations.size} results" +
                        if (abnormalCount > 0) "  ·  $abnormalCount abnormal" else "",
                fontSize = 12.sp,
                color = if (abnormalCount > 0) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (expanded) "Hide" else "Show",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (expanded && record.observations.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                record.observations.forEach { obs ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = obs.code,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = obs.displayValue,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                            if (obs.flag.isNotBlank()) {
                                Text(
                                    text = obs.flag,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = if (obs.flag.contains(
                                            "H",
                                            true
                                        ) || obs.flag.contains("L", true)
                                    )
                                        MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary
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
    Panel {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            SectionHeader(
                title = "Raw HL7 stream",
                action = { TextButton(onClick = onClear) { Text("Clear") } }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF14171A))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(messages) { msg ->
                    Text(
                        text = msg,
                        color = Color(0xFF5CE08A),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
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
        ssid == WifiManager.UNKNOWN_SSID
    ) {
        "Wi-Fi name unavailable"
    } else {
        ssid.removeSurrounding("\"")
    }
}

// ---------------------------------------------------------------------------
// Orders
// ---------------------------------------------------------------------------

@Composable
fun OrderCard(
    status: String?,
    connected: Boolean,
    viewModel: DeviceViewModel,
) {
    val patient by viewModel.selectedPatient.collectAsState()
    val matches by viewModel.patientMatches.collectAsState()
    val query by viewModel.patientQuery.collectAsState()
    val availableTests by viewModel.availableTests.collectAsState()
    val chosenTests by viewModel.selectedTests.collectAsState()
    val orders by viewModel.createdOrders.collectAsState()

    // New-patient form fields
    var registering by rememberSaveable { mutableStateOf(false) }
    var mrn by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var sex by rememberSaveable { mutableStateOf("M") }
    var age by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }

    Panel {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            SectionHeader("Create order")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(
                modifier = Modifier.padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Patient
                val p = patient
                when {
                    p != null -> {
                        Text(
                            "${p.name} · MRN ${p.mrn}",
                            style = MaterialTheme.typography.titleSmall
                        )
                        TextButton(onClick = { viewModel.selectPatient(null) }) { Text("Change patient") }
                    }

                    registering -> {
                        Field(mrn, { mrn = it }, "Patient ID (MRN) *")
                        Field(name, { name = it }, "Name *")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("M", "F").forEach { s ->
                                FilterChip(
                                    selected = sex == s,
                                    onClick = { sex = s },
                                    label = { Text(s) }
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Field(
                                age, { age = it.filter(Char::isDigit) }, "Age (yr)",
                                Modifier.weight(1f), keyboardType = KeyboardType.Number
                            )
                            Field(
                                phone, { phone = it }, "Phone",
                                Modifier.weight(1f), keyboardType = KeyboardType.Phone
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = { registering = false },
                                shape = FieldShape,
                                modifier = Modifier.weight(1f)
                            ) { Text("Cancel") }
                            Button(
                                onClick = {
                                    viewModel.registerPatient(
                                        mrn,
                                        name,
                                        sex,
                                        age.toIntOrNull(),
                                        phone
                                    )
                                    mrn = ""; name = ""; age = ""; phone = ""
                                    registering = false
                                },
                                enabled = mrn.isNotBlank() && name.isNotBlank(),
                                shape = FieldShape,
                                modifier = Modifier.weight(1f)
                            ) { Text("Save patient") }
                        }
                    }

                    else -> {
                        Field(query, viewModel::setPatientQuery, "Search name or MRN")
                        matches.forEach { m ->
                            ListItem(
                                headlineContent = { Text(m.name) },
                                supportingContent = { Text("MRN ${m.mrn}") },
                                modifier = Modifier.clickable { viewModel.selectPatient(m.id) }
                            )
                        }
                        TextButton(onClick = {
                            registering = true
                        }) { Text("Register new patient") }
                    }
                }

                // Tests
                Text("Tests", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    availableTests.forEach { t ->
                        FilterChip(
                            selected = t in chosenTests,
                            onClick = { viewModel.toggleTest(t.code) },
                            label = { Text(t.shortName) }
                        )
                    }
                }

                Button(
                    onClick = { viewModel.createOrder() },
                    enabled = patient != null,
                    shape = FieldShape,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Create order & sample ID") }

                if (status != null) {
                    Text(
                        status,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Orders created in this session
            if (orders.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SectionHeader("Pending orders")
                orders.forEach { row ->
                    val o = row.order
                    Column(
                        modifier = Modifier.padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(o.orderNo, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${o.patientName} · MRN ${o.patientNumber} · ${o.tests.joinToString { it.shortName }}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = { viewModel.cancelOrder(o.orderNo) },
                                shape = FieldShape,
                                modifier = Modifier.weight(1f)
                            ) { Text("Cancel") }
                            Button(
                                onClick = { viewModel.sendOrder(o) },
                                enabled = connected && !row.sent,
                                shape = FieldShape,
                                modifier = Modifier.weight(1f)
                            ) { Text(if (row.sent) "Sent" else "Send to analyser") }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

private data class Opt(val code: String, val label: String) {
    val display get() = if (code.isEmpty() || code == label) label else "$label ($code)"
}

private object SeamatyOptions {
    // Opt(code sent over HL7, label shown on screen)
    val sex = listOf(
        Opt("Male", "Male"),
        Opt("Female", "Female"),
        Opt("Other", "Other"),
        Opt("Unknown", "Unknown")
    )
    val ageGroup = listOf(Opt("Adult", "Adult"), Opt("Child", "Child"))
    val race = listOf(
        Opt("White", "White"), // TODO check with cat dog as race
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
    val testType = listOf("PWC").map { Opt(it, it) }
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
            trailingIcon = { Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            singleLine = true,
            shape = FieldShape,
            modifier = Modifier.fillMaxWidth()
        )
        Box(Modifier
            .matchParentSize()
            .clickable { expanded = true })
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

    Panel {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            SectionHeader("Create worklist order")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(
                modifier = Modifier.padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Field(
                    sampleNo, { sampleNo = clean(it, 22) }, "Sample number *",
                    isError = duplicate,
                    supportingText = if (duplicate) "Already in the worklist" else null
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(
                        medicalId,
                        { medicalId = clean(it, 20) },
                        "Medical record no.",
                        Modifier.weight(1f)
                    )
                    Field(
                        patientName,
                        { patientName = clean(it, 48) },
                        "Patient name",
                        Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(
                        age, { age = it.filter(Char::isDigit).take(3) }, "Age *",
                        Modifier.weight(1f),
                        isError = age.isNotEmpty() && !ageValid,
                        keyboardType = KeyboardType.Number
                    )
                    FixedDropdown(
                        "Age group",
                        ageGroup,
                        SeamatyOptions.ageGroup,
                        { ageGroup = it },
                        Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FixedDropdown("Sex", sex, SeamatyOptions.sex, { sex = it }, Modifier.weight(1f))
                    FixedDropdown(
                        "Race",
                        race,
                        SeamatyOptions.race,
                        { race = it },
                        Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FixedDropdown(
                        "Sample type",
                        sampleType,
                        SeamatyOptions.sampleType,
                        { sampleType = it },
                        Modifier.weight(1f)
                    )
                    FixedDropdown(
                        "Test type",
                        testType,
                        SeamatyOptions.testType,
                        { testType = it },
                        Modifier.weight(1f)
                    )
                }
                Field(department, { department = clean(it, 40) }, "Department")
                Field(doctor, { doctor = clean(it, 60) }, "Operator")
                Field(
                    printNotes,
                    { printNotes = clean(it, 100) },
                    "Print notes",
                    singleLine = false
                )

                Button(
                    onClick = {
                        onAdd(build())
                        sampleNo = ""; medicalId = ""; patientName = ""; age = ""; printNotes = ""
                    },
                    enabled = sampleNo.isNotBlank() && !duplicate && ageValid,
                    shape = FieldShape,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Add to worklist") }

                if (status != null) {
                    Text(
                        status,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SectionHeader("Pending worklist (${pending.size})")
            if (pending.isEmpty()) {
                Text(
                    "Nothing pending. The analyser gets \"no worklist\" when it asks.",
                    color = MaterialTheme.colorScheme.outline,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
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
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}