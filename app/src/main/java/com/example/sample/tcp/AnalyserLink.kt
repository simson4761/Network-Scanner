package com.example.sample.tcp

import com.example.sample.data.DeviceType
import com.example.sample.data.TcpConnectionState
import com.example.sample.data.ozelle.PendingOrder
import com.example.sample.data.seamaty.WorklistOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow


/** What the ViewModel needs from a link to the analyser, whichever side opens the connection. */
interface AnalyserLink {
    val state: StateFlow<TcpConnectionState>
    fun start(scope: CoroutineScope, deviceType: DeviceType)
    fun stop()

    /** Client mode only: push an order to the analyser. Server mode returns a failure. */
    suspend fun sendOrder(order: PendingOrder): Result<Unit> =
        Result.failure(UnsupportedOperationException("Sending orders is only available in client mode"))

    suspend fun pushWorklist(order: WorklistOrder): Result<Unit> =
    Result.failure(UnsupportedOperationException("Sending orders is only available in Server mode"))
}
