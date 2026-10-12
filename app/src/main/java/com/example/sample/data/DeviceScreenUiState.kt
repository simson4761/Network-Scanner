package com.example.sample.data

data class DeviceScreenUiState(
    val deviceIpAddress : String = "",
    val openPorts : List<Int> = emptyList(),
    val connectionState: DeviceConnectionState = DeviceConnectionState.Disconnected,
    val patientInfo: PatientInfo = PatientInfo(),
    val rawMessages: List<String> = emptyList()
)