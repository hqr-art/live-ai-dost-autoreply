package com.livedost.autoreply

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ReplyLogItem(
    val id: String = System.currentTimeMillis().toString(),
    val sender: String,
    val incomingMessage: String,
    val aiReply: String,
    val engine: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("sender", sender)
        json.put("incomingMessage", incomingMessage)
        json.put("aiReply", aiReply)
        json.put("engine", engine)
        json.put("timestamp", timestamp)
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): ReplyLogItem {
            return ReplyLogItem(
                id = json.optString("id", System.currentTimeMillis().toString()),
                sender = json.optString("sender", "Unknown"),
                incomingMessage = json.optString("incomingMessage", ""),
                aiReply = json.optString("aiReply", ""),
                engine = json.optString("engine", "Pollinations"),
                timestamp = json.optLong("timestamp", System.currentTimeMillis())
            )
        }
    }
}
