package com.example.sample.utils

import com.example.sample.data.AckStatus
import com.example.sample.data.hl7.Hl7Message
import com.example.sample.data.ozelle.PendingOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


object HL7Orders {

    /** ORR^O02 in reply to an ORM^O01. */
    fun buildOrr(msg: Hl7Message, status: AckStatus, order: PendingOrder?): String =
        buildOrr(msg.msh.controlId, status, order)

    /** ORR^O02 with an explicit control ID (used for manual pushes, where there is no ORM). */
    fun buildOrr(controlId: String, status: AckStatus, order: PendingOrder?): String {
        val ts = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
        val msh = "MSH|^~\\&||LIS|||$ts||ORR^O02||P|2.3.1||||||UNICODE"

        if (status != AckStatus.ACCEPTED || order == null) {
            val msa = listOf(
                "MSA", status.ackCode, controlId, status.text, "", "", status.condition.toString()
            ).joinToString("|")
            return "$msh\r$msa\r"
        }

        val s = esc(order.sampleNo)
        val sb = StringBuilder()
        sb.append(msh).append('\r')
        sb.append("MSA|${status.ackCode}|$controlId\r")
        sb.append("PID|1||${esc(order.patientId)}^^^^MR||^${esc(order.name)}||${order.birthDate}|${esc(order.sex)}\r")
        sb.append("PV1|1||${esc(order.department)}^^${esc(order.bed)}\r")
        sb.append("ORC|AF|$s\r")
        sb.append("OBR|1|$s||00001^Automated Count^99MRC||$ts||||${esc(order.doctor)}||||$ts||||||||||HM\r")
        sb.append("OBX|1|IS|08002^Blood Mode^99MRC||${esc(order.bloodMode)}||||||F\r")
        sb.append("OBX|2|IS|08003^Test Mode^99MRC||${esc(order.testMode)}||||||F\r")
        var n = 3
        order.ageYears?.let { sb.append("OBX|${n++}|NM|30525-0^Age^LN||$it|yr|||||F\r") }
        if (order.remark.isNotBlank()) {
            sb.append("OBX|${n++}|ST|01001^Remark^99MRC||${esc(order.remark)}||||||F\r")
        }
        return sb.toString()
    }

    /** HL7 escape rules from section 4.3. The backslash must be replaced first. */
    private fun esc(t: String): String = t
        .replace("\\", "\\E\\")
        .replace("|", "\\F\\")
        .replace("^", "\\S\\")
        .replace("&", "\\T\\")
        .replace("~", "\\R\\")
        .replace("\r", " ")
        .replace("\n", " ")
}