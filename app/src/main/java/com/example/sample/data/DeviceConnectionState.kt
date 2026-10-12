package com.example.sample.data

sealed interface DeviceConnectionState {
    object Disconnected : DeviceConnectionState
    object Connecting : DeviceConnectionState
    data class Connected(val host: String, val port: Int) : DeviceConnectionState
    data class Error(val message: String) : DeviceConnectionState
}
