package com.example.sample.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ResultStorage(private val context: Context) {

    companion object {
        private const val TAG = "ResultStorage"
        private const val FILE_NAME = "saved_hl7_results.json"
    }

    private val file: File
        get() = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun getAllResults(): List<TestResultRecord> {
        if (!file.exists()) return emptyList()
        return try {
            val jsonString = file.readText()
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<TestResultRecord>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.optString("id", "")
                val timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                val patientId = obj.optString("patientId", "---")
                val patientName = obj.optString("patientName", "Unknown")
                val rawMessage = obj.optString("rawMessage", "")

                val obsArray = obj.optJSONArray("observations") ?: JSONArray()
                val obsList = mutableListOf<ObservationItem>()
                for (j in 0 until obsArray.length()) {
                    val obsObj = obsArray.getJSONObject(j)
                    obsList.add(
                        ObservationItem(
                            code = obsObj.optString("code", ""),
                            value = obsObj.optString("value", ""),
                            unit = obsObj.optString("unit", ""),
                            flag = obsObj.optString("flag", ""),
                            refRange = obsObj.optString("refRange", "")
                        )
                    )
                }

                list.add(
                    TestResultRecord(
                        id = id,
                        timestamp = timestamp,
                        patientId = patientId,
                        patientName = patientName,
                        observations = obsList,
                        rawMessage = rawMessage
                    )
                )
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read saved results", e)
            emptyList()
        }
    }

    @Synchronized
    fun saveResult(record: TestResultRecord) {
        val current = getAllResults().toMutableList()
        current.add(0, record) // Add latest at top
        saveAll(current)
    }

    @Synchronized
    fun saveAll(records: List<TestResultRecord>) {
        try {
            val jsonArray = JSONArray()
            for (record in records) {
                val obj = JSONObject().apply {
                    put("id", record.id)
                    put("timestamp", record.timestamp)
                    put("patientId", record.patientId)
                    put("patientName", record.patientName)
                    put("rawMessage", record.rawMessage)

                    val obsArray = JSONArray()
                    for (obs in record.observations) {
                        val obsObj = JSONObject().apply {
                            put("code", obs.code)
                            put("value", obs.value)
                            put("unit", obs.unit)
                            put("flag", obs.flag)
                            put("refRange", obs.refRange)
                        }
                        obsArray.put(obsObj)
                    }
                    put("observations", obsArray)
                }
                jsonArray.put(obj)
            }
            file.writeText(jsonArray.toString(2))
            Log.d(TAG, "Saved ${records.size} results to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save results", e)
        }
    }

    @Synchronized
    fun clearAllResults() {
        try {
            if (file.exists()) {
                file.delete()
            }
            Log.d(TAG, "Cleared all saved results")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear results", e)
        }
    }
}
