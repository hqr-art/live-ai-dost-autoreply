package com.livedost.autoreply

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ReplyLogItem(
    val id: String = System.currentTimeMillis().toString(),
    val sender: String,
    val incomingMessage: String,
    val aiReply: String = "",
    val engine: String = "Pollinations",
    val status: String = STATUS_SENT, // STATUS_QUEUED, STATUS_PROCESSING, STATUS_RETRYING, STATUS_SENT, STATUS_FAILED
    val attempts: Int = 1,
    val errorReason: String? = null,
    val durationMs: Long = 0L,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_QUEUED = "QUEUED"
        const val STATUS_PROCESSING = "PROCESSING"
        const val STATUS_RETRYING = "RETRYING"
        const val STATUS_SENT = "SENT"
        const val STATUS_FAILED = "FAILED"

        fun fromJson(json: JSONObject): ReplyLogItem {
            return ReplyLogItem(
                id = json.optString("id", System.currentTimeMillis().toString()),
                sender = json.optString("sender", "Unknown"),
                incomingMessage = json.optString("incomingMessage", ""),
                aiReply = json.optString("aiReply", ""),
                engine = json.optString("engine", "Pollinations"),
                status = json.optString("status", STATUS_SENT),
                attempts = json.optInt("attempts", 1),
                errorReason = if (json.isNull("errorReason")) null else json.optString("errorReason"),
                durationMs = json.optLong("durationMs", 0L),
                timestamp = json.optLong("timestamp", System.currentTimeMillis())
            )
        }
    }

    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("sender", sender)
        json.put("incomingMessage", incomingMessage)
        json.put("aiReply", aiReply)
        json.put("engine", engine)
        json.put("status", status)
        json.put("attempts", attempts)
        if (errorReason != null) {
            json.put("errorReason", errorReason)
        } else {
            json.put("errorReason", JSONObject.NULL)
        }
        json.put("durationMs", durationMs)
        json.put("timestamp", timestamp)
        return json
    }
}
