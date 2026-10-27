package com.example.sample.utils

import java.time.LocalDate
import java.time.LocalDate.now
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

/** MSH-10 values for our requests: `yyyyMMdd` + 4-digit counter, as OpenDx does. */
class EhomeControlIds(private val today: () -> LocalDate = LocalDate::now) {
    private val counter = AtomicInteger(0)

    fun next(): String {
        val n = counter.updateAndGet { if (it >= 9_999) 1 else it + 1 }
        return today().format(DateTimeFormatter.BASIC_ISO_DATE) + n.toString().padStart(4, '0')
    }
}