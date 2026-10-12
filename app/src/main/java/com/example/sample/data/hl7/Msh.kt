package com.example.sample.data.hl7

data class Msh(
    val sendingApp: String,        // MSH-3   "SMT"
    val deviceModel: String,       // MSH-4   e.g. "SMT-120"
    val timestamp: String,         // MSH-7   yyyyMMddHHmmss
    val messageCode: String,       // MSH-9.1 ORU
    val triggerEvent: String,      // MSH-9.2 R01
    val controlId: String,         // MSH-10  increments from 1
    val processingId: String,      // MSH-11  P
    val version: String,           // MSH-12  2.3.1
    val sequenceNumber: String,    // MSH-13  A
    val resultType: ResultType,    // MSH-16  0 sample, 1 calibration, 2 QC
    val charset: String,           // MSH-18  ASCII
    val softwareVersion: String    // MSH-21  e.g. V1.00.00.79
)
