package com.vineetm1204m.voxsentrymobile

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class CallRecord(
    val id: String,
    val timestamp: Long,
    val duration: Long,
    val finalStatus: String, // "real", "suspicious", "cloned", "uncertain", "unavailable"
    val maxConfidence: Float,
    val callType: String, // "native", "whatsapp", etc.
    val reason: String? = null
)

class HistoryStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("voxsentry_history", Context.MODE_PRIVATE)
    private val HISTORY_KEY = "call_history"

    fun addRecord(record: CallRecord) {
        val history = getHistoryAsJsonArray()
        
        val recordJson = JSONObject().apply {
            put("id", record.id)
            put("timestamp", record.timestamp)
            put("duration", record.duration)
            put("finalStatus", record.finalStatus)
            put("maxConfidence", record.maxConfidence.toDouble())
            put("callType", record.callType)
            if (record.reason != null) put("reason", record.reason)
        }
        
        history.put(recordJson)
        
        prefs.edit().putString(HISTORY_KEY, history.toString()).apply()
    }

    fun getHistory(): String {
        return prefs.getString(HISTORY_KEY, "[]") ?: "[]"
    }
    
    private fun getHistoryAsJsonArray(): JSONArray {
        val historyString = prefs.getString(HISTORY_KEY, "[]") ?: "[]"
        return try {
            JSONArray(historyString)
        } catch (e: Exception) {
            JSONArray()
        }
    }
    
    fun clearHistory() {
        prefs.edit().remove(HISTORY_KEY).apply()
    }
}
