package com.livedost.autoreply

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class AiChatEngine(private val appSettings: AppSettings) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "AiChatEngine"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    /**
     * Generate real-time conversational response in Roman Urdu.
     * Takes sender name, incoming message text, and rolling conversation history.
     */
    suspend fun generateReply(
        sender: String,
        incomingMessage: String,
        history: List<MemoryMessage>
    ): String {
        val systemPrompt = appSettings.systemPrompt.ifBlank { AppSettings.DEFAULT_SYSTEM_PROMPT }
        val engine = appSettings.aiEngine
        val geminiKey = appSettings.geminiApiKey

        return if (engine == AppSettings.ENGINE_GEMINI && geminiKey.isNotBlank()) {
            try {
                callGemini(geminiKey, systemPrompt, history, incomingMessage)
            } catch (e: Exception) {
                Log.e(TAG, "Gemini API failed, falling back to Pollinations Free AI", e)
                callPollinations(systemPrompt, history, incomingMessage)
            }
        } else {
            callPollinations(systemPrompt, history, incomingMessage)
        }
    }

    /**
     * Mode A: Free No-Key AI via Pollinations
     * Tries OpenAI-compatible POST endpoint first, falls back to GET endpoint.
     */
    private fun callPollinations(
        systemPrompt: String,
        history: List<MemoryMessage>,
        incomingMessage: String
    ): String {
        try {
            val messagesArray = JSONArray()

            // System prompt
            val sysObj = JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            }
            messagesArray.put(sysObj)

            // Multi-turn history
            for (msg in history) {
                if (msg.content.isNotBlank()) {
                    val m = JSONObject().apply {
                        put("role", if (msg.role == "assistant") "assistant" else "user")
                        put("content", msg.content)
                    }
                    messagesArray.put(m)
                }
            }

            // Current incoming message
            val curUser = JSONObject().apply {
                put("role", "user")
                put("content", incomingMessage)
            }
            messagesArray.put(curUser)

            val payload = JSONObject().apply {
                put("model", "openai")
                put("messages", messagesArray)
                put("temperature", 0.8)
            }

            val request = Request.Builder()
                .url("https://text.pollinations.ai/openai")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .header("User-Agent", "LiveAiDostAutoReply/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        val json = JSONObject(body)
                        val choices = json.optJSONArray("choices")
                        if (choices != null && choices.length() > 0) {
                            val reply = choices.getJSONObject(0)
                                .optJSONObject("message")
                                ?.optString("content", "")
                            if (!reply.isNullOrBlank()) {
                                return sanitizeReply(reply)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Pollinations POST failed, trying GET fallback...", e)
        }

        // Fallback to GET endpoint
        return callPollinationsGetFallback(systemPrompt, history, incomingMessage)
    }

    private fun callPollinationsGetFallback(
        systemPrompt: String,
        history: List<MemoryMessage>,
        incomingMessage: String
    ): String {
        val historyContext = buildString {
            if (history.isNotEmpty()) {
                append("\nRecent conversation context:")
                for (h in history.takeLast(4)) {
                    val tag = if (h.role == "assistant") "Me" else "Friend"
                    append("\n$tag: ${h.content}")
                }
                append("\n")
            }
            append("\nFriend's latest message: $incomingMessage\nYour natural Pakistani Roman Urdu reply:")
        }

        val fullPrompt = "$systemPrompt\n$historyContext"
        val encodedPrompt = URLEncoder.encode(fullPrompt, StandardCharsets.UTF_8.name())
        val url = "https://text.pollinations.ai/$encodedPrompt?model=openai"

        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", "LiveAiDostAutoReply/1.0")
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val raw = response.body?.string() ?: ""
                if (raw.isNotBlank()) {
                    return sanitizeReply(raw)
                }
            }
            throw IllegalStateException("Pollinations GET fallback returned HTTP ${response.code}")
        }
    }

    /**
     * Mode B: Free Google Gemini Endpoint (gemini-2.5-flash)
     */
    private fun callGemini(
        apiKey: String,
        systemPrompt: String,
        history: List<MemoryMessage>,
        incomingMessage: String
    ): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"

        val root = JSONObject()

        // System Instruction
        val sysInstruction = JSONObject()
        val sysParts = JSONArray().apply {
            put(JSONObject().apply { put("text", systemPrompt) })
        }
        sysInstruction.put("parts", sysParts)
        root.put("systemInstruction", sysInstruction)

        // Contents (multi-turn)
        val contentsArray = JSONArray()

        // Ensure roles alternate and first is "user"
        var lastRole: String? = null
        for (item in history) {
            val geminiRole = if (item.role == "assistant") "model" else "user"
            if (geminiRole == lastRole) {
                // Skip back-to-back identical roles to respect Gemini schema
                continue
            }
            val contentObj = JSONObject()
            contentObj.put("role", geminiRole)
            val parts = JSONArray().apply {
                put(JSONObject().apply { put("text", item.content) })
            }
            contentObj.put("parts", parts)
            contentsArray.put(contentObj)
            lastRole = geminiRole
        }

        // Add latest user message
        val latestObj = JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply {
                put(JSONObject().apply { put("text", incomingMessage) })
            })
        }
        contentsArray.put(latestObj)
        root.put("contents", contentsArray)

        // Generation Config
        val genConfig = JSONObject().apply {
            put("temperature", 0.85)
            put("maxOutputTokens", 150)
        }
        root.put("generationConfig", genConfig)

        val request = Request.Builder()
            .url(url)
            .post(root.toString().toRequestBody(JSON_MEDIA))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful || body.isNullOrBlank()) {
                throw IllegalStateException("Gemini returned HTTP ${response.code}: $body")
            }

            val json = JSONObject(body)
            val candidates = json.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val candidate = candidates.getJSONObject(0)
                val content = candidate.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    val text = parts.getJSONObject(0).optString("text", "")
                    if (text.isNotBlank()) {
                        return sanitizeReply(text)
                    }
                }
            }
            throw IllegalStateException("No candidate text found in Gemini response")
        }
    }

    /**
     * Strips bot prefixes, AI prefixes, and quotes so the message looks 100% human.
     */
    private fun sanitizeReply(raw: String): String {
        var reply = raw.trim()

        // Remove surrounding quotes if model wrapped output in ""
        if ((reply.startsWith("\"") && reply.endsWith("\"")) ||
            (reply.startsWith("'") && reply.endsWith("'"))
        ) {
            reply = reply.substring(1, reply.length - 1).trim()
        }

        // Remove common model prefixes
        val prefixesToRemove = listOf(
            "AI:", "Assistant:", "Dost:", "Me:", "Reply:", "Jawab:",
            "Bot:", "Live Dost:"
        )
        for (prefix in prefixesToRemove) {
            if (reply.startsWith(prefix, ignoreCase = true)) {
                reply = reply.substring(prefix.length).trim()
            }
        }

        // Clean any stray markdown fences
        reply = reply.replace("```", "").trim()

        return reply
    }
}
