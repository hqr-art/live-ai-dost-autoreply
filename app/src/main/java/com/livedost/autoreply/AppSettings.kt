package com.livedost.autoreply

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

class AppSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("live_ai_dost_settings", Context.MODE_PRIVATE)

    companion object {
        const val ENGINE_POLLINATIONS = "pollinations"
        const val ENGINE_GEMINI = "gemini"

        const val DEFAULT_SYSTEM_PROMPT =
            "Tum mere WhatsApp par mere dost se meri jagah baat kar rahe ho. Hamesha bilkul natural, casual Pakistani Roman Urdu mein jawab do (jaise 'haan yaar', 'bhai', 'sahi hai', 'Alhamdulillah', 'kya scene hai'). Kabhi bhi mat bolna ke tum AI ya bot ho, aur kabhi 'main busy hoon' ka rata-rataya bahana mat banana. Dost jo bhi baat kare—haal chaal, mazak, padhai, cricket, WWE, ya koi sawal—bilkul ek asli dost ki tarah chote aur natural WhatsApp message (1 se 2 lines, bina kisi formal heading ya bullet points ke) mein jawab do."

        const val ACTION_LOGS_UPDATED = "com.livedost.autoreply.ACTION_LOGS_UPDATED"
    }

    var isMasterEnabled: Boolean
        get() = prefs.getBoolean("is_master_enabled", true)
        set(value) = prefs.edit().putBoolean("is_master_enabled", value).apply()

    var aiEngine: String
        get() = prefs.getString("ai_engine", ENGINE_POLLINATIONS) ?: ENGINE_POLLINATIONS
        set(value) = prefs.edit().putString("ai_engine", value).apply()

    var geminiApiKey: String
        get() = prefs.getString("gemini_api_key", "") ?: ""
        set(value) = prefs.edit().putString("gemini_api_key", value.trim()).apply()

    var systemPrompt: String
        get() = prefs.getString("system_prompt", DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
        set(value) = prefs.edit().putString("system_prompt", value).apply()

    var ignoreGroups: Boolean
        get() = prefs.getBoolean("ignore_groups", true)
        set(value) = prefs.edit().putBoolean("ignore_groups", value).apply()

    var blacklist: String
        get() = prefs.getString("blacklist", "") ?: ""
        set(value) = prefs.edit().putString("blacklist", value).apply()

    var typingDelaySeconds: Int
        get() = prefs.getInt("typing_delay_seconds", 3)
        set(value) = prefs.edit().putInt("typing_delay_seconds", value).apply()

    fun isSenderBlacklisted(sender: String): Boolean {
        val list = blacklist.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (list.isEmpty()) return false
        val cleanSender = sender.trim().lowercase()
        return list.any { cleanSender.contains(it) || it.contains(cleanSender) }
    }

    fun getLogs(): List<ReplyLogItem> {
        val jsonStr = prefs.getString("reply_logs_json", "[]") ?: "[]"
        val list = mutableListOf<ReplyLogItem>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                list.add(ReplyLogItem.fromJson(arr.getJSONObject(i)))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    @Synchronized
    fun addLog(item: ReplyLogItem) {
        val current = getLogs().toMutableList()
        current.add(0, item) // Newest first
        // Keep only the last 15 items
        val trimmed = if (current.size > 15) current.subList(0, 15) else current
        val arr = JSONArray()
        trimmed.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("reply_logs_json", arr.toString()).apply()
    }

    fun clearLogs() {
        prefs.edit().remove("reply_logs_json").apply()
    }
}
