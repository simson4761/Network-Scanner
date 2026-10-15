package com.example.sample.utils

import com.example.sample.data.AckStatus
import com.example.sample.data.DeviceType
import com.example.sample.data.hl7.ResultType
import com.example.sample.data.hl7.Hl7Message
import com.example.sample.data.hl7.Hl7ParseException
import com.example.sample.data.hl7.Msh
import com.example.sample.data.hl7.Obr
import com.example.sample.data.hl7.Obx
import com.example.sample.data.hl7.Pid
import com.example.sample.data.hl7.Pv1
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


object HL7Parser {

    private const val START_BLOCK = '\u000B' // <0x0B>
    private const val END_BLOCK = '\u001C'   // <0x1C>

    // ----- parse -----

    fun parse(input: String, device: DeviceType): Hl7Message {
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

        // MSH has the same field positions on both devices.
        // Only the result type differs: Seamaty MSH-16 (0/1/2), hematology MSH-11 (P/Q).
        val resultType = when (device) {
            DeviceType.SEAMATY -> when (m.raw(16)) {
                "0" -> ResultType.SAMPLE
                "1" -> ResultType.CALIBRATION
                "2" -> ResultType.QC
                else -> ResultType.UNKNOWN
            }
            DeviceType.HEMATOLOGY -> when (m.raw(11)) {
                "P" -> ResultType.SAMPLE
                "Q" -> ResultType.QC
                else -> ResultType.UNKNOWN
            }
        }

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
            resultType = resultType,
            charset = m.raw(18),
            softwareVersion = m.text(21)
        )

        val pid = segments.firstOrNull { it.name == "PID" }?.let {
            when (device) {
                DeviceType.SEAMATY -> Pid(
                    setId = it.raw(1),
                    patientId = it.text(2),
                    name = it.text(5),
                    age = it.comp(7, 1),
                    ageGroup = it.comp(7, 2),
                    sex = it.raw(8),
                    race = it.text(10),
                    category = it.raw(18)
                )
                DeviceType.HEMATOLOGY -> Pid(
                    setId = it.raw(1),
                    patientId = it.comp(3, 1),              // "7393670^^^^MR" -> 7393670
                    name = listOf(it.comp(5, 2), it.comp(5, 1))   // XPN: family^given -> "given family"
                        .filter { s -> s.isNotEmpty() }.joinToString(" "),
                    birthDate = it.raw(7),
                    sex = it.raw(8)                          // "Male" etc.
                )
            }
        }

        val pv1 = if (device == DeviceType.HEMATOLOGY) {
            segments.firstOrNull { it.name == "PV1" }?.let {
                Pv1(
                    patientClass = it.raw(2),
                    department = it.comp(3, 1),
                    bed = it.comp(3, 3)
                )
            }
        } else null

        val obr = segments.firstOrNull { it.name == "OBR" }?.let {
            when (device) {
                DeviceType.SEAMATY -> Obr(
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
                DeviceType.HEMATOLOGY -> Obr(
                    setId = it.raw(1),
                    sampleNo = it.comp(3, 1),
                    serviceCode = it.comp(4, 1),
                    requestedTime = it.raw(6),
                    observationTime = it.raw(7),
                    orderingProvider = it.text(10),
                    clinicalInfo = it.text(13),
                    receivedTime = it.raw(14),
                    section = it.raw(24),
                    operator = it.text(32)
                )
            }
        }

        val obxs = segments.filter { it.name == "OBX" }.map {
            // Hematology binary (histogram / scatter) comes as "^Application^Octer-stream^Base64^<data>"
            val isBinary = device == DeviceType.HEMATOLOGY && it.raw(2) == "ED"
            Obx(
                setId = it.raw(1),
                valueType = it.raw(2),
                code = it.comp(3, 1),             // Seamaty: plain name. Hematology: ID of ID^Name^System
                name = it.comp(3, 2),
                codingSystem = it.comp(3, 3),
                subId = it.text(4),
                value = if (isBinary) it.comp(5, 5) else it.text(5),
                unit = it.text(6),
                refRange = it.text(7),
                flag = it.raw(8),
                probability = it.text(9),
                status = it.raw(11),
                userFlags = it.raw(13)
            )
        }

        return Hl7Message(
            raw = raw,
            segmentNames = segments.map { it.name },
            msh = msh,
            pid = pid,
            obr = obr,
            obxs = obxs,
            pv1 = pv1,
            deviceType = device
        )
    }

    // ----- validate -----

    /**
     * Checks the message against the manual and returns the status to put in the ACK.
     * ACCEPTED means the message is well-formed; save it, then ACK with the same status.
     */
    fun validate(msg: Hl7Message): AckStatus {
        val m = msg.msh
        val device = msg.deviceType

        if (m.messageCode != "ORU") return AckStatus.UNSUPPORTED_MESSAGE_TYPE
        if (m.triggerEvent != "R01") return AckStatus.UNSUPPORTED_EVENT_CODE

        val processingOk = when (device) {
            DeviceType.SEAMATY -> m.processingId == "P"
            DeviceType.HEMATOLOGY -> m.processingId == "P" || m.processingId == "Q"
        }
        if (!processingOk) return AckStatus.UNSUPPORTED_PROCESSING_ID
        // Seamaty documents 2.3.1. The hematology unit in the field sends 2.1.3 (BA212_A3),
        // so its version is not enforced.
        if (device == DeviceType.SEAMATY && m.version != "2.3.1") return AckStatus.UNSUPPORTED_VERSION_ID

        // Seamaty: MSH, PID, OBR, {OBX}
        // Hematology: MSH, PID, [PV1], OBR, {OBX}  (one PID/OBR group per message)
        val n = msg.segmentNames
        val obrIndex = if (device == DeviceType.HEMATOLOGY && n.getOrNull(2) == "PV1") 3 else 2
        val orderOk = n.size > obrIndex && n[0] == "MSH" && n[1] == "PID" && n[obrIndex] == "OBR" &&
                n.drop(obrIndex + 1).all { it == "OBX" }
        val obr = msg.obr
        if (!orderOk || msg.pid == null || obr == null) return AckStatus.SEGMENT_SEQUENCE_ERROR

        if (m.controlId.isEmpty() || obr.sampleNo.isEmpty() ||
            msg.obxs.any { obxKeyMissing(device, it) || it.value.isEmpty() }
        ) return AckStatus.REQUIRED_FIELD_MISSING

        if (msg.obxs.any { it.valueType == "NM" && it.value != "*" && it.numericValue == null }) {
            return AckStatus.DATA_TYPE_ERROR
        }
        return AckStatus.ACCEPTED
    }

    private fun obxKeyMissing(device: DeviceType, o: Obx): Boolean = when (device) {
        DeviceType.SEAMATY -> o.subId.isEmpty()       // LIS matches the item on OBX-4
        DeviceType.HEMATOLOGY -> o.code.isEmpty()     // identified by the ID in OBX-3
    }

    // ----- ACK -----

    /**
     * ACK^R01 for [msg], in the dialect of the device it came from:
     *   Seamaty:    MSH|^~\&|SMT|DataManager|||<time>||ACK^R01|<id>|P|2.3.1|A|||0||ASCII|||
     *               MSA|AA|<id>|<text>|||0|
     *   Hematology: MSH|^~\&|LIS||||<time>||ACK^R01|<id>|<P or Q>|2.3.1||||||UNICODE
     *               MSA|AA|<id>
     * The control ID is echoed from the instrument's MSH-10.
     * Pass [app] / [facility] to override the defaults above.
     */
    fun buildAck(
        msg: Hl7Message,
        status: AckStatus = AckStatus.ACCEPTED,
        app: String? = null,
        facility: String? = null
    ): String {
        // hematology echoes P/Q (sample vs QC); Seamaty always sends P
        val processingId =
            if (msg.deviceType == DeviceType.HEMATOLOGY && msg.msh.processingId == "Q") "Q" else "P"
        return ack(msg.deviceType, msg.msh.controlId, processingId, status, app, facility)
    }

    /** For messages that could not be parsed at all (no control ID available). */
    fun buildErrorAck(
        device: DeviceType,
        status: AckStatus,
        controlId: String = "",
        app: String? = null,
        facility: String? = null
    ): String = ack(device, controlId, "P", status, app, facility)

    // ----- helpers -----

    private fun ack(
        device: DeviceType,
        controlId: String,
        processingId: String,
        status: AckStatus,
        app: String?,
        facility: String?
    ): String {
        val ts = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())

        val (msh, msa) = when (device) {
            DeviceType.SEAMATY -> Pair(
                listOf(
                    "MSH", "^~\\&", app ?: "SMT", facility ?: "DataManager", "", "", ts, "", "ACK^R01",
                    controlId, processingId, "2.3.1", "A", "", "", "0", "", "ASCII", "", "", ""
                ).joinToString("|"),
                listOf(
                    "MSA", status.ackCode, controlId, status.text, "", "", status.condition.toString(), ""
                ).joinToString("|")
            )
            DeviceType.HEMATOLOGY -> Pair(
                listOf(
                    "MSH", "^~\\&", app ?: "LIS", facility ?: "", "", "", ts, "", "ACK^R01",
                    controlId, processingId, "2.3.1", "", "", "", "", "", "UNICODE"
                ).joinToString("|"),
                if (status == AckStatus.ACCEPTED) {
                    "MSA|${status.ackCode}|$controlId"
                } else {
                    listOf(
                        "MSA", status.ackCode, controlId, status.text, "", "", status.condition.toString()
                    ).joinToString("|")
                }
            )
        }
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