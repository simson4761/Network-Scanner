package com.example.sample.data

/** SERVER: we listen, analyzer connects (worklist). CLIENT: we connect to the analyzer (send orders). */
enum class TcpRole(val label: String) {
    SERVER("Server"),
    CLIENT("Client")
}
