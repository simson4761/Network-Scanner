package com.example.sample.utils

import com.example.sample.data.AckStatus
import com.example.sample.data.hl7.ResultType
import com.example.sample.data.hl7.Hl7Message
import com.example.sample.data.hl7.Hl7ParseException
import com.example.sample.data.hl7.Msh
import com.example.sample.data.hl7.Obr
import com.example.sample.data.hl7.Obx
import com.example.sample.data.hl7.Pid
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


object HL7Parser {

    private const val START_BLOCK = '\u000B' // <0x0B>
    private const val END_BLOCK = '\u001C'   // <0x1C>

    // ----- parse -----

    fun parse(input: String): Hl7Message {
        // strip MLLP framing bytes and surrounding whitespace if present
        val raw = input.trim { it == START_BLOCK || it == END_BLOCK || it.isWhitespace() }
        if (raw.length < 8 || !raw.startsWith("MSH")) {
            throw Hl7ParseException(
                AckStatus.SEGMENT_SEQUENCE_ERROR,
                "Message does not start with MSH"
            )
        }

        val fieldSep = raw[3]
        val enc = raw.substring(4).substringBefore(fieldSep)
        val separators = Separators(
            field = fieldSep,
            component = enc.getOrElse(0) { '^' },
            repetition = enc.getOrElse(1) { '~' },
            escape = enc.getOrElse(2) { '\\' },
            sub = enc.getOrElse(3) { '&' }
        )

        val segments = raw.split(Regex("[\r\n]+"))
            .filter { it.isNotBlank() }
            .map { Segment(it.split(fieldSep), separators) }

        val m = segments.first()   // starts with MSH, checked above

        val msh = Msh(
            sendingApp = m.text(3),
            deviceModel = m.text(4),
            timestamp = m.raw(7),
            messageCode = m.comp(9, 1),
            triggerEvent = m.comp(9, 2),
            controlId = m.raw(10),
            processingId = m.raw(11),
            version = m.raw(12),
            sequenceNumber = m.raw(13),
            resultType = when (m.raw(16)) {
                "0" -> ResultType.SAMPLE
                "1" -> ResultType.CALIBRATION
                "2" -> ResultType.QC
                else -> ResultType.UNKNOWN
            },
            charset = m.raw(18),
            softwareVersion = m.text(21)
        )

        val pid = segments.firstOrNull { it.name == "PID" }?.let {
            Pid(
                setId = it.raw(1),
                patientId = it.text(2),
                name = it.text(5),
                age = it.comp(7, 1),
                ageGroup = it.comp(7, 2),
                sex = it.raw(8),
                race = it.text(10),
                category = it.raw(18)
            )
        }

        val obr = segments.firstOrNull { it.name == "OBR" }?.let {
            Obr(
                setId = it.raw(1),
                sampleNo = it.text(3),
                deviceId = it.text(4),
                observationTime = it.raw(7),
                clinicalInfo = it.text(13),
                qcLotNumber = it.text(14),
                sampleType = it.text(15),
                orderingProvider = it.text(16),
                department = it.text(17),
                reagentTypeCode = it.comp(18, 1),
                reagentBatch = it.comp(18, 2),
                reagentId = it.comp(18, 3),
                reagentDescription = it.comp(18, 4),
                hemolysis = it.comp(19, 1),
                lipemia = it.comp(19, 2),
                jaundice = it.comp(19, 3),
                fillerField1 = it.text(20),
                fillerField2 = it.text(21),
                reviewDoctor = it.comp(32, 1),
                reviewTime = it.comp(32, 2),
                remarks = it.text(33)
            )
        }

        val testResultObject = segments.filter { it.name == "OBX" }.map {
            Obx(
                setId = it.raw(1),
                valueType = it.raw(2),
                code = it.text(3),
                subId = it.text(4),
                value = it.text(5),
                unit = it.text(6),
                refRange = it.text(7),
                flag = it.raw(8),
                probability = it.text(9),
                status = it.raw(11)
            )
        }

        return Hl7Message(raw, segments.map { it.name }, msh, pid, obr, testResultObject)
    }

    // ----- validate -----

    /**
     * Checks the message against the manual and returns the status to put in the ACK.
     * ACCEPTED means the message is well-formed; save it, then ACK with the same status.
     */
    fun validate(msg: Hl7Message): AckStatus {
        val m = msg.msh
        if (m.messageCode != "ORU") return AckStatus.UNSUPPORTED_MESSAGE_TYPE
        if (m.triggerEvent != "R01") return AckStatus.UNSUPPORTED_EVENT_CODE
        if (m.processingId != "P") return AckStatus.UNSUPPORTED_PROCESSING_ID
        if (m.version != "2.3.1") return AckStatus.UNSUPPORTED_VERSION_ID

        // order must be MSH, PID, OBR, {OBX}
        val n = msg.segmentNames
        val orderOk = n.size >= 3 && n[0] == "MSH" && n[1] == "PID" && n[2] == "OBR" &&
                n.drop(3).all { it == "OBX" }
        val obr = msg.obr
        if (!orderOk || msg.pid == null || obr == null) return AckStatus.SEGMENT_SEQUENCE_ERROR

        if (m.controlId.isEmpty() || obr.sampleNo.isEmpty() ||
            msg.obxs.any { it.subId.isEmpty() || it.value.isEmpty() }
        ) return AckStatus.REQUIRED_FIELD_MISSING

        if (msg.obxs.any { it.valueType == "NM" && it.value != "*" && it.numericValue == null }) {
            return AckStatus.DATA_TYPE_ERROR
        }
        return AckStatus.ACCEPTED
    }

    // ----- ACK -----

    /**
     * ACK^R01 for [msg]:
     *   MSH|^~\&|SMT|DataManager|||<time>||ACK^R01|<id>|P|2.3.1|A|||0||ASCII|||
     *   MSA|AA|<id>|<text>|||0|
     * The control ID is echoed from the instrument's MSH-10.
     */
    fun buildAck(
        msg: Hl7Message,
        status: AckStatus = AckStatus.ACCEPTED,
        app: String = "SMT",
        facility: String = "DataManager"
    ): String = ack(msg.msh.controlId, status, app, facility)

    /** For messages that could not be parsed at all (no control ID available). */
    fun buildErrorAck(
        status: AckStatus,
        controlId: String = "",
        app: String = "SMT",
        facility: String = "DataManager"
    ): String = ack(controlId, status, app, facility)

    // ----- helpers -----

    private fun ack(controlId: String, status: AckStatus, app: String, facility: String): String {
        val ts = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
        val msh = listOf(
            "MSH", "^~\\&", app, facility, "", "", ts, "", "ACK^R01",
            controlId, "P", "2.3.1", "A", "", "", "0", "", "ASCII", "", "", ""
        ).joinToString("|")
        val msa = listOf(
            "MSA", status.ackCode, controlId, status.text, "", "", status.condition.toString(), ""
        ).joinToString("|")
        return "$msh\r$msa\r"
    }

    private class Separators(
        val field: Char,
        val component: Char,
        val repetition: Char,
        val escape: Char,
        val sub: Char
    ) {
        fun unescape(t: String): String {
            if (escape !in t) return t
            val sb = StringBuilder()
            var i = 0
            while (i < t.length) {
                if (t[i] == escape) {
                    val end = t.indexOf(escape, i + 1)
                    if (end > 0) {
                        val rep = when (t.substring(i + 1, end)) {
                            "F" -> field.toString()
                            "S" -> component.toString()
                            "T" -> sub.toString()
                            "R" -> repetition.toString()
                            "E" -> escape.toString()
                            ".br" -> "\n"
                            else -> null
                        }
                        if (rep != null) {
                            sb.append(rep)
                            i = end + 1
                            continue
                        }
                    }
                }
                sb.append(t[i])
                i++
            }
            return sb.toString()
        }
    }

    private class Segment(val parts: List<String>, val separators: Separators) {
        val name: String = parts[0].trim()

        /** Trimmed raw field n. In MSH the separator itself is MSH-1, so MSH-n sits at index n-1. */
        fun raw(n: Int): String =
            parts.getOrNull(if (name == "MSH") n - 1 else n).orEmpty().trim()

        /** Unescaped field n. */
        fun text(n: Int): String = separators.unescape(raw(n))

        /** Component c (1-based) of field n, first repetition only, unescaped. */
        fun comp(n: Int, c: Int): String =
            separators.unescape(
                raw(n).split(separators.repetition)[0]
                    .split(separators.component)
                    .getOrNull(c - 1).orEmpty().trim()
            )
    }
}