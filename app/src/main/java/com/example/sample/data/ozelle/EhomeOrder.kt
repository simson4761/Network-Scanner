package com.example.sample.data.ozelle

import java.time.LocalDate

/** An order pushed to the analyzer with `ORM^O01`. [orderNo] is our sample ID. */
data class EhomeOrder(
    val orderNo: String,
    val patientNumber: String,
    val patientName: String,
    /** `M` / `F` (or `male` / `female`); anything else is sent empty. */
    val sex: String?,
    val birthDate: LocalDate?,
    val phone: String?,
    val tests: List<EhomeTest>,
)
