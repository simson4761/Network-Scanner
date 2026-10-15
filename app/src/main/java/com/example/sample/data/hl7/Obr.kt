package com.example.sample.data.hl7


// Observation report (specimen type, laboratory physician, etc.)
data class Obr(
    val setId: String,             // OBR-1
    val sampleNo: String,          // OBR-3   sample number
    val deviceId: String="",          // OBR-4   device ID
    val observationTime: String,   // OBR-7   inspection time (QC: quality control time)
    val clinicalInfo: String,      // OBR-13  SMT-120: result ^ process status code ^ reagent info
    val qcLotNumber: String="",       // OBR-14  QC messages: lot number
    val sampleType: String="",        // OBR-15  Serum / Plasma / Whole Blood / Urine
    val orderingProvider: String,  // OBR-16  Chinese: examiner; QC: High / Low
    val department: String="",        // OBR-17
    val reagentTypeCode: String="",   // OBR-18.1
    val reagentBatch: String="",      // OBR-18.2
    val reagentId: String="",         // OBR-18.3
    val reagentDescription: String="",// OBR-18.4
    val hemolysis: String="",         // OBR-19.1 sample traits (Voden)
    val lipemia: String="",           // OBR-19.2
    val jaundice: String="",          // OBR-19.3
    val fillerField1: String="",      // OBR-20  laboratory / operating physician; QC type on some models
    val fillerField2: String="",      // OBR-21  department (Chinese version, SMT-680)
    val reviewDoctor: String="",      // OBR-32.1 Chinese version
    val reviewTime: String="",        // OBR-32.2
    val remarks: String="",            // OBR-33  Chinese version
    val serviceCode: String = "",     // OBR-4 ID: 00001 count result, 00003 QC
    val requestedTime: String = "",   // OBR-6 sampling time
    val receivedTime: String = "",    // OBR-14 delivery time
    val section: String = "",         // OBR-24 (HM)
    val operator: String = ""
)