package com.example.sample.data.hl7

data class Hl7Message(
    val raw: String,
    val segmentNames: List<String>,
    val msh: Msh,
    val pid: Pid?,
    val obr: Obr?,
    val obxs: List<Obx>
) {
    val results: List<Obx> get() = obxs.filter { !it.isAlarm }
    val alarms: List<Obx> get() = obxs.filter { it.isAlarm }
}