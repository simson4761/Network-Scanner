package com.example.sample.utils

import com.example.sample.data.AckStatus
import com.example.sample.data.DeviceType
import com.example.sample.data.hl7.ResultType
import com.example.sample.data.hl7.Hl7Message
import com.example.sample.data.hl7.Hl7ParseException
import com.example.sample.data.hl7.Msh
import com.example.sample.data.hl7.Obr
import com.example.sample.data.hl7.Obx
import com.example.sample.data.hl7.Orc
import com.example.sample.data.hl7.Pid
import com.example.sample.data.hl7.Pv1
import com.example.sample.data.seamaty.WorklistOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


object HL7Parser {

    private const val START_BLOCK = '\u000B' // <0x0B>
    private const val END_BLOCK = '\u001C'   // <0x1C>
    private const val OBR_NOTES_FIELD = 38

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

        // ORC: the worklist / order query (ORM^O01), both devices. The sample number is ORC-3
        // (Seamaty sends "*" to ask for the next pending order).
        val orc = segments.firstOrNull { it.name == "ORC" }?.let {
            Orc(
                orderControl = it.raw(1),          // RF
                placerOrderNo = it.comp(2, 1),
                sampleNo = it.comp(3, 1),
                orderStatus = it.raw(5)            // IP
            )
        }

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
            orc = orc,
            deviceType = device
        )
    }

    // ----- validate -----

    /**
     * Checks the message against the manual and returns the status.
     *   ORU^R01 (results)      -> reply with buildAck(msg, status)
     *   Worklist query         -> see isWorklistQuery(msg)
     *                             Seamaty:    reply with buildWorklistOrr(msg, status, order)
     *                             Hematology: reply with Hl7Orders.buildOrr(msg, status, order)
     * ACCEPTED means the message is well-formed.
     */
    fun validate(msg: Hl7Message): AckStatus {
        val m = msg.msh
        val device = msg.deviceType

        // Worklist queries: ORM^O01 (both devices) or ORU^R02 (what the Seamaty unit actually sends)
        val isWorklist = isWorklistQuery(msg)
        if (!isWorklist) {
            if (m.messageCode != "ORU") return AckStatus.UNSUPPORTED_MESSAGE_TYPE
            if (m.triggerEvent != "R01") return AckStatus.UNSUPPORTED_EVENT_CODE
        }

        val processingOk = when (device) {
            DeviceType.SEAMATY -> m.processingId == "P"
            DeviceType.HEMATOLOGY -> m.processingId == "P" || m.processingId == "Q"
        }
        if (!processingOk) return AckStatus.UNSUPPORTED_PROCESSING_ID
        // Seamaty documents 2.3.1. The hematology unit in the field sends 2.1.3 (BA212_A3),
        // so its version is not enforced.
        if (device == DeviceType.SEAMATY && m.version != "2.3.1") return AckStatus.UNSUPPORTED_VERSION_ID

        return if (isWorklist) validateWorklist(msg) else validateOru(msg)
    }

    /**
     * True when [msg] is a worklist query that should be answered with an ORR, not an ACK.
     *   Seamaty:    ORU^R02 (MSH only, as sent by the unit) or ORM^O01 (as in the manual)
     *   Hematology: ORM^O01
     */
    fun isWorklistQuery(msg: Hl7Message): Boolean {
        val m = msg.msh
        val isOrm = m.messageCode == "ORM" && m.triggerEvent == "O01"
        return when (msg.deviceType) {
            DeviceType.SEAMATY -> isOrm || (m.messageCode == "ORU" && m.triggerEvent == "R02")
            DeviceType.HEMATOLOGY -> isOrm
        }
    }

    /**
     * Worklist query: MSH, {ORC}.
     *   Seamaty may send MSH only (no ORC) -> no sample number, treat as "next pending order".
     *   Hematology looks the order up by the sample number in ORC-3, so ORC is required there.
     */
    private fun validateWorklist(msg: Hl7Message): AckStatus {
        val n = msg.segmentNames
        if (n.isEmpty() || n[0] != "MSH" || !n.drop(1).all { it == "ORC" }) {
            return AckStatus.SEGMENT_SEQUENCE_ERROR
        }
        if (msg.msh.controlId.isEmpty()) return AckStatus.REQUIRED_FIELD_MISSING
        if (msg.deviceType == DeviceType.HEMATOLOGY) {
            val orc = msg.orc ?: return AckStatus.SEGMENT_SEQUENCE_ERROR
            if (orc.sampleNo.isEmpty()) return AckStatus.REQUIRED_FIELD_MISSING
        }
        return AckStatus.ACCEPTED
    }

    /** ORU^R01 results. */
    private fun validateOru(msg: Hl7Message): AckStatus {
        val m = msg.msh
        val device = msg.deviceType

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

    // ----- build replies -----

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

    /**
     * Seamaty worklist reply (ORR^O02) to an ORM^O01 query (manual section 4.2).
     *
     * Found:      MSH|^~\&|Heska|COMPUTER|||<time>||ORR^O02|<id>|P|2.3.1|A|||0||UNICODE|||
     *             MSA|AA|<id>|||||
     *             PID|1|<medicalId>|||<owner>^<pet>||<ageGroup>|<sex>||<animalType>||||||||1|...
     *             OBR|1||<sampleNo>|<lisServerId>|||<time>||||||||<bloodType>||<dept>|<testType>|<doctor>|...
     * Not found:  same MSH, then
     *             MSA|AR|<id>|||||0
     *
     * Pass [order] = null (or a non-ACCEPTED [status]) to reject. The control ID is echoed from the
     * instrument's MSH-10, the same as the ACK.
     */
    // DEBUG: true = fill every PID/OBR field with its own number so the device screen shows the mapping
    // Round 2: unknown PID fields get their own field number (small values pass an age range check)
    // Print notes position in the worklist OBR (confirmed on the device)
    fun buildWorklistOrr(
        msg: Hl7Message,
        status: AckStatus = AckStatus.ACCEPTED,
        order: WorklistOrder? = null,      // null = nothing pending -> MSA|AR
        app: String? = null,
        facility: String? = null
    ): String {
        require(msg.deviceType == DeviceType.SEAMATY) { "buildWorklistOrr is Seamaty only" }

        val id = msg.msh.controlId
        val requestNo = msg.orc?.placerOrderNo?.takeIf { it.isNotBlank() } ?: id
        val ts = now()

        val msh = listOf(
            "MSH", "^~\\&", app ?: "TVAM", facility ?: "APP", "", "", ts, "", "ORR^O02",
            id, "P", "2.3.1", "A", "", "", "0", "", msg.msh.charset.ifEmpty { "UNICODE" }, "", "", ""
        ).joinToString("|")

        if (status != AckStatus.ACCEPTED || order == null) {
            val code = if (status == AckStatus.ACCEPTED) "AR" else status.ackCode
            val cond = if (status == AckStatus.ACCEPTED) "0" else status.condition.toString()
            return "$msh\r${seg("MSA", 7, 1 to code, 2 to requestNo, 7 to cond)}\r"
        }

        val msa = seg("MSA", 7, 1 to "AA", 2 to requestNo)

        val pid = seg(
            "PID", 31,
            1 to "1",
            2 to esc(order.medicalId),      // Patient ID
            5 to esc(order.patientName),    // Patient name
            7 to esc(order.ageGroup),       // Age group: Adult / Child
            8 to esc(order.sex),            // Male / Female / Other / Unknown
            10 to esc(order.race),          // Origin
            18 to "0"                       // Patient category, fixed 0
        )

        val obrFields = mutableListOf(
            1 to "1",
            2 to esc(order.sampleNo),       // Sample ID on the device
            3 to esc(order.sampleNo),
            4 to esc(order.lisServerId),
            7 to order.orderTime.ifEmpty { ts },
            15 to esc(order.sampleType),
            17 to esc(order.department),
            18 to esc(order.testType),
            19 to esc(order.doctor)         // Operator
        )
        if (order.printNotes.isNotBlank()) obrFields += OBR_NOTES_FIELD to esc(order.printNotes)
        val obr = seg("OBR", 47, *obrFields.toTypedArray())

        return "$msh\r$msa\r$pid\r$obr\r"
    }
    // ----- helpers -----

    private fun now(): String = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())

    /** Segment with fields placed by HL7 field number (1-based); [size] is the last field to emit. */
    private fun seg(name: String, size: Int, vararg fields: Pair<Int, String>): String {
        val parts = Array(size + 1) { "" }
        parts[0] = name
        fields.forEach { (n, v) -> parts[n] = v }
        return parts.joinToString("|")
    }

    /** Escape HL7 delimiters in free text going out to the instrument. */
    private fun esc(t: String): String = t
        .replace("\\", "\\E\\")
        .replace("|", "\\F\\")
        .replace("^", "\\S\\")
        .replace("&", "\\T\\")
        .replace("~", "\\R\\")

    private fun ack(
        device: DeviceType,
        controlId: String,
        processingId: String,
        status: AckStatus,
        app: String?,
        facility: String?
    ): String {
        val ts = now()

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
