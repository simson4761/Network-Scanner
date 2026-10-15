package com.example.sample.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
data class TestResultRecord(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val patientId: String = "---",
    val patientName: String = "Unknown",
    val observations: List<ObservationItem> = emptyList(),
    val rawMessage: String = ""
) {
    val formattedTime: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}
