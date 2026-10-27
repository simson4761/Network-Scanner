package com.example.sample.utils

import com.example.sample.data.hl7.Hl7Delimiters

/** HL7 escape sequences: `\F\ \S\ \R\ \E\ \T\`, plus `\.br\` and hex `\Xhh..\`. */
object Hl7Escape {

    fun unescape(value: String, d: Hl7Delimiters = Hl7Delimiters()): String {
        if (value.indexOf(d.escape) < 0) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch != d.escape) {
                out.append(ch)
                i++
                continue
            }
            val end = value.indexOf(d.escape, i + 1)
            if (end < 0) {
                out.append(value, i, value.length)
                break
            }
            val seq = value.substring(i + 1, end)
            when {
                seq == "F" -> out.append(d.field)
                seq == "S" -> out.append(d.component)
                seq == "R" -> out.append(d.repetition)
                seq == "E" -> out.append(d.escape)
                seq == "T" -> out.append(d.subComponent)
                seq == ".br" -> out.append('\n')
                seq.length > 1 && seq[0] == 'X' && seq.length % 2 == 1 ->
                    seq.substring(1).chunked(2).forEach { hex ->
                        hex.toIntOrNull(16)?.let { out.append(it.toChar()) }
                    }
                else -> out.append(d.escape).append(seq).append(d.escape)
            }
            i = end + 1
        }
        return out.toString()
    }

    fun escape(value: String, d: Hl7Delimiters = Hl7Delimiters()): String {
        val out = StringBuilder(value.length)
        for (ch in value) {
            when (ch) {
                d.escape -> out.append(d.escape).append('E').append(d.escape)
                d.field -> out.append(d.escape).append('F').append(d.escape)
                d.component -> out.append(d.escape).append('S').append(d.escape)
                d.repetition -> out.append(d.escape).append('R').append(d.escape)
                d.subComponent -> out.append(d.escape).append('T').append(d.escape)
                '\r', '\n' -> out.append(d.escape).append(".br").append(d.escape)
                else -> out.append(ch)
            }
        }
        return out.toString()
    }
}