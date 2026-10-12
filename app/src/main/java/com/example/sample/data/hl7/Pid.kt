package com.example.sample.data.hl7

data class Pid(
    val setId: String,             // PID-1
    val patientId: String,         // PID-2   medical record number (blank for QC)
    val name: String,              // PID-5   patient name (blank for QC)
    val age: String,               // PID-7.1 age (SMT-120) / year of birth (SD1)
    val ageGroup: String,          // PID-7.2 A = adult, C = child
    val sex: String,               // PID-8   M / F / O / U
    val race: String,              // PID-10  non-Chinese sample tests only
    val category: String           // PID-18  fixed 0
)