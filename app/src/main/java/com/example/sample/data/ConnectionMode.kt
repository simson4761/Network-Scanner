package com.example.sample.data

/** How the app talks to the analyzer. Shown as the tabs at the top of the screen. */
enum class ConnectionMode(val label: String) {
    TCP("Wi-Fi (TCP)"),
    SERIAL("USB Serial")
}