package com.example.sample.data.hl7

/** New: hematology only (PV1-2 class, PV1-3 department ^ room ^ bed). */
data class Pv1(
    val patientClass: String = "",
    val department: String = "",
    val bed: String = ""
)
