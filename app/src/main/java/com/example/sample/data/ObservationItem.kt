package com.example.sample.data

data class ObservationItem(
    val code: String,
    val value: String,
    val unit: String = "",
    val flag: String = "",
    val refRange: String = ""
) {
    val displayValue: String
        get() = if (unit.isNotBlank()) "$value $unit" else value
}
