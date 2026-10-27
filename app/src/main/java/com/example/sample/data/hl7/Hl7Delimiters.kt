package com.example.sample.data.hl7

/** HL7 v2 delimiters, taken from MSH-1 / MSH-2 of each message. */
data class Hl7Delimiters(
    val field: Char = '|',
    val component: Char = '^',
    val repetition: Char = '~',
    val escape: Char = '\\',
    val subComponent: Char = '&',
)
