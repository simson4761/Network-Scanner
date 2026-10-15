package com.example.sample.data.hl7


// Test result
data class Obx(
    val setId: String,             // OBX-1
    val valueType: String,         // OBX-2   NM, or ST for alarm messages
    val code: String,              // OBX-3   item name ("Alarm" for warning messages)
    val subId: String,             // OBX-4   key the LIS must use to match the item (alarm: code, e.g. W3001)
    val value: String,             // OBX-5   result; "*" = could not be calculated; alarm: text
    val unit: String,              // OBX-6
    val refRange: String,          // OBX-7   (QC: target^SD)
    val flag: String,              // OBX-8   N / H / L
    val probability: String,       // OBX-9   e.g. "> 650" when above the linear range
    val status: String,             // OBX-11  F = final
    // new, hematology
    val name: String = "",            // OBX-3 component 2
    val codingSystem: String = "",    // OBX-3 component 3 (LN / 99MRC)
    val userFlags: String = ""        // OBX-13: O reagent expired, E/e edited
) {
    val isAlarm: Boolean get() = valueType == "ST" && code.equals("Alarm", ignoreCase = true)
    val numericValue: Double? get() = value.toDoubleOrNull()

    val displayName: String get() = name.ifEmpty { code }
}