package com.example.sample.data

data class PatientInfo(
    val patientId: String = "---",
    val fullName: String = "Waiting for data...",
    val observations: Map<String, String> = emptyMap()
)