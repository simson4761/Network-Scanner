package com.example.sample.utils

import com.example.sample.data.ozelle.EhomeOrder
import com.example.sample.data.ozelle.EhomeTest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter


object HL7Orders {

    private val TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** New order (ORC-1 `NW`): one OBR per test. */
    fun buildEhomeOrder(
        order: EhomeOrder,
        controlId: String,
        now: LocalDateTime = LocalDateTime.now()
    ): String {
        require(order.tests.isNotEmpty()) { "An order needs at least one test" }
        val sex = when (order.sex?.uppercase()) {
            "M", "MALE" -> "M"
            "F", "FEMALE" -> "F"
            else -> ""
        }
        val pid = "PID|||${e(order.patientNumber)}||${e(order.patientName)}||${
            order.birthDate?.format(DAY).orEmpty()
        }|$sex|||||${e(order.phone)}"
        val orc = "ORC|NW|${e(order.orderNo)}|${e(order.orderNo)}|||P"
        return join(msh("ORM^O01", controlId, now), pid, orc, *obrs(order.tests))
    }

    fun reportQuery(
        orderNo: String,
        controlId: String,
        now: LocalDateTime = LocalDateTime.now()
    ): String =
        join(msh("QRY^Q02", controlId, now), qrd(orderNo, now))

    private fun qrd(orderNo: String, now: LocalDateTime): String =
        "QRD|${now.format(TS)}|R||||||${e(orderNo)}"

    private fun join(vararg segments: String): String =
        segments.joinToString(separator = "\r", postfix = "\r")

    private fun msh(type: String, controlId: String, now: LocalDateTime): String =
        "MSH|^~\\&|HIS^1.0.2||||${now.format(TS)}||$type|$controlId|P|2.9.1^1.0.0"


    private fun obrs(tests: List<EhomeTest>): Array<String> =
        tests.mapIndexed { i, t -> "OBR|${i + 1}|||${e(t.code)}^${e(t.shortName)}" }.toTypedArray()

    private fun e(value: String?): String = value?.let { Hl7Escape.escape(it.trim()) }.orEmpty()

}