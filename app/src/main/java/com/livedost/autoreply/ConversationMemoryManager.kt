package com.livedost.autoreply

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class MemoryMessage(
    val role: String, // "user" or "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("role", role)
        obj.put("content", content)
        obj.put("timestamp", timestamp)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): MemoryMessage {
            return MemoryMessage(
                role = obj.optString("role", "user"),
                content = obj.optString("content", ""),
                timestamp = obj.optLong("timestamp", System.currentTimeMillis())
            )
        }
    }
}

class ConversationMemoryManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("live_ai_dost_memory", Context.MODE_PRIVATE)

    companion object {
        private const val MAX_HISTORY_TURNS = 12
    }

    private fun getStorageKey(sender: String): String {
        val clean = sender.trim().lowercase()
        return try {
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(clean.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            clean.replace("[^a-zA-Z0-9]".toRegex(), "_")
        }
    }

    @Synchronized
    fun getHistory(sender: String): List<MemoryMessage> {
        val key = getStorageKey(sender)
        val jsonStr = prefs.getString(key, null) ?: return emptyList()
        val list = mutableListOf<MemoryMessage>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                list.add(MemoryMessage.fromJson(arr.getJSONObject(i)))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    @Synchronized
    fun addMessage(sender: String, role: String, content: String) {
        val current = getHistory(sender).toMutableList()
        current.add(MemoryMessage(role = role, content = content))

        // Keep only rolling last 12 messages
        val trimmed = if (current.size > MAX_HISTORY_TURNS) {
            current.subList(current.size - MAX_HISTORY_TURNS, current.size)
        } else {
            current
        }

        val arr = JSONArray()
        trimmed.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(getStorageKey(sender), arr.toString()).apply()
    }

    @Synchronized
    fun clearSenderHistory(sender: String) {
        prefs.edit().remove(getStorageKey(sender)).apply()
    }

    @Synchronized
    fun clearAllHistory() {
        prefs.edit().clear().apply()
    }
}
