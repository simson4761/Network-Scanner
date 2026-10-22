package com.example.sample.data.seamaty

/**
 * One worklist entry the LIS sends back in an ORR^O02 reply to a Seamaty worklist query (human).
 * Field mapping (Seamaty manual, PID/OBR tables):
 *   PID-2 medicalId, PID-5 patientName, PID-7 age^ageGroup, PID-8 sex, PID-10 race, PID-18 fixed 0
 *   OBR-3 sampleNo, OBR-4 lisServerId, OBR-7 orderTime, OBR-15 sampleType,
 *   OBR-17 department, OBR-18 testType, OBR-20 doctor
 */
data class WorklistOrder(
    val sampleNo: String,
    val medicalId: String = "",
    val patientName: String = "",
    val age: String = "",             // digits only
    val ageGroup: String = "",        // A adult / C child
    val sex: String = "",             // M / F / O / U
    val race: String = "",            // White / Black / Yellow / Brown / Other, blank = not sent
    val lisServerId: String = "",
    val orderTime: String = "",       // yyyyMMddHHmmss; defaults to now when empty
    val sampleType: String = "",      // Serum / Plasma / Whole Blood / Urine
    val department: String = "",
    val testType: String = "",
    val doctor: String = "",
    val printNotes: String = ""
)