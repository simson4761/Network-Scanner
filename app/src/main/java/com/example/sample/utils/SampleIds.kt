package com.example.sample.utils

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Sample IDs: `YYMMDD` + 4-digit sequence, e.g. `2610120001` (guide §6.1).
 *
 * The date prefix makes every ID globally unique — the ID is never reused, even though the
 * 4-digit counter starts again each day. The caller must still enforce a unique index.
 */
object SampleIds {
    private val PREFIX: DateTimeFormatter = DateTimeFormatter.ofPattern("yyMMdd")
    const val LENGTH: Int = 10
    const val MAX_PER_DAY: Int = 9_999

    fun prefix(date: LocalDate): String = date.format(PREFIX)

    /**
     * Next ID for [date], given the highest ID already issued with that date's prefix.
     * @throws IllegalStateException when the day's 9,999 IDs are exhausted.
     */
    fun next(date: LocalDate = LocalDate.now(), lastIssuedToday: String? = null): String {
        val prefix = prefix(date)
        val lastSeq = lastIssuedToday
            ?.takeIf { it.length == LENGTH && it.startsWith(prefix) }
            ?.substring(prefix.length)
            ?.toIntOrNull()
            ?: 0
        check(lastSeq < MAX_PER_DAY) { "Sample ID sequence exhausted for $prefix" }
        return prefix + (lastSeq + 1).toString().padStart(4, '0')
    }

    fun isValid(id: String): Boolean = id.length == LENGTH && id.all { it.isDigit() }
}