package com.example.sample.data.ozelle

/** What your LIS knows about a sample. Only sampleNo is required. */
data class PendingOrder(
    val sampleNo: String,
    val patientId: String = "",
    val name: String = "",
    val birthDate: String = "",     // yyyyMMddHHmmss, or empty
    val sex: String = "",           // "Male" / "Female"
    val department: String = "",
    val bed: String = "",
    val doctor: String = "",
    val ageYears: Int? = null,
    val bloodMode: String = "W",    // W, P, W_WBCHGB, ...
    val testMode: String = "CBC",   // CBC, CBC+DIFF, CBC+5DIFF
    val remark: String = ""
)