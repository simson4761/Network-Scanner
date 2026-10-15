package com.example.sample.data.hl7

import com.example.sample.data.DeviceType

data class Hl7Message(
    val raw: String,
    val segmentNames: List<String>,
    val msh: Msh,
    val pid: Pid?,
    val obr: Obr?,
    val obxs: List<Obx>,
    val pv1: Pv1? = null,
    val deviceType : DeviceType = DeviceType.SEAMATY
) {
    val results: List<Obx> get() = obxs.filter { !it.isAlarm }
    val alarms: List<Obx> get() = obxs.filter { it.isAlarm }
}