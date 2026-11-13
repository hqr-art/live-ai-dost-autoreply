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

data class AiGenerationResult(
    val reply: String,
    val engineUsed: String,
    val durationMs: Long,
    val isSuccess: Boolean = true,
    val errorDetails: String? = null
)

class AiChatEngine(private val appSettings: AppSettings) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    companion object {
        private const val TAG = "AiChatEngine"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    /**
     * Generate real-time conversational response in Roman Urdu with detailed performance metadata.
     */
    suspend fun generateReplyWithDetails(
        sender: String,
        incomingMessage: String,
        history: List<MemoryMessage>,
        forcePollinations: Boolean = false
    ): AiGenerationResult {
        val startTime = System.currentTimeMillis()
        val systemPrompt = appSettings.systemPrompt.ifBlank { AppSettings.DEFAULT_SYSTEM_PROMPT }
        val engine = if (forcePollinations) AppSettings.ENGINE_POLLINATIONS else appSettings.aiEngine
        val geminiKey = appSettings.geminiApiKey

        if (engine == AppSettings.ENGINE_GEMINI && geminiKey.isNotBlank()) {
            try {
                val reply = callGemini(geminiKey, systemPrompt, history, incomingMessage)
                val duration = System.currentTimeMillis() - startTime
                return AiGenerationResult(
                    reply = reply,
                    engineUsed = "Gemini 1.5/2.0",
                    durationMs = duration,
                    isSuccess = true
                )
            } catch (e: Exception) {
                val geminiError = e.message ?: "Unknown Gemini error"
                Log.w(TAG, "Gemini failed: $geminiError. Falling back to Pollinations Free AI...", e)
                try {
                    val fallbackReply = callPollinations(systemPrompt, history, incomingMessage)
                    val duration = System.currentTimeMillis() - startTime
                    return AiGenerationResult(
                        reply = fallbackReply,
                        engineUsed = "Pollinations (Fallback)",
                        durationMs = duration,
                        isSuccess = true,
                        errorDetails = "Gemini: $geminiError (Auto-recovered via Pollinations)"
                    )
                } catch (fallbackEx: Exception) {
                    val duration = System.currentTimeMillis() - startTime
                    val combinedError = "Gemini: $geminiError | Fallback: ${fallbackEx.message}"
                    Log.e(TAG, "Both Gemini and Pollinations failed: $combinedError", fallbackEx)
                    return AiGenerationResult(
                        reply = "",
                        engineUsed = "Gemini -> Pollinations",
                        durationMs = duration,
                        isSuccess = false,
                        errorDetails = combinedError
                    )
                }
            }
        } else {
            try {
                val reply = callPollinations(systemPrompt, history, incomingMessage)
                val duration = System.currentTimeMillis() - startTime
                return AiGenerationResult(
                    reply = reply,
                    engineUsed = "Pollinations Free",
                    durationMs = duration,
                    isSuccess = true
                )
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - startTime
                val errorMsg = e.message ?: "Unknown Pollinations error"
                Log.e(TAG, "Pollinations failed: $errorMsg", e)
                return AiGenerationResult(
                    reply = "",
                    engineUsed = "Pollinations Free",
                    durationMs = duration,
                    isSuccess = false,
                    errorDetails = errorMsg
                )
            }
        }
    }

    /**
     * Mode A: Free No-Key AI via Pollinations
     */
    private fun callPollinations(
        systemPrompt: String,
        history: List<MemoryMessage>,
        incomingMessage: String
    ): String {
        try {
            val messagesArray = JSONArray()

            val sysObj = JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            }
            messagesArray.put(sysObj)

            for (msg in history.takeLast(10)) {
                if (msg.content.isNotBlank()) {
                    val m = JSONObject().apply {
                        put("role", if (msg.role == "assistant") "assistant" else "user")
                        put("content", msg.content)
                    }
                    messagesArray.put(m)
                }
            }

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

        return callPollinationsGetFallback(systemPrompt, history, incomingMessage)
    }

    private fun callPollinationsGetFallback(
        systemPrompt: String,
        history: List<MemoryMessage>,
        incomingMessage: String
    ): String {
        val historyContext = buildString {
            if (history.isNotEmpty()) {
                append("\nRecent conversation:")
                for (h in history.takeLast(4)) {
                    val tag = if (h.role == "assistant") "Me" else "Friend"
                    append("\n$tag: ${h.content}")
                }
                append("\n")
            }
            append("\nFriend: $incomingMessage\nYour natural Pakistani Roman Urdu reply:")
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
            throw IllegalStateException("Pollinations HTTP ${response.code}: ${response.message}")
        }
    }

    /**
     * Mode B: Google Gemini Endpoint with multi-model fallback (gemini-1.5-flash -> gemini-2.0-flash)
     */
    private fun callGemini(
        apiKey: String,
        systemPrompt: String,
        history: List<MemoryMessage>,
        incomingMessage: String
    ): String {
        val models = listOf("gemini-1.5-flash", "gemini-2.0-flash", "gemini-2.5-flash")
        var lastException: Exception? = null

        for (model in models) {
            try {
                return executeGeminiRequest(model, apiKey, systemPrompt, history, incomingMessage)
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Gemini with model $model failed: ${e.message}, trying next model...")
            }
        }

        throw lastException ?: IllegalStateException("All Gemini models failed")
    }

    private fun executeGeminiRequest(
        modelName: String,
        apiKey: String,
        systemPrompt: String,
        history: List<MemoryMessage>,
        incomingMessage: String
    ): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"

        val root = JSONObject()

        val sysInstruction = JSONObject()
        val sysParts = JSONArray().apply {
            put(JSONObject().apply { put("text", systemPrompt) })
        }
        sysInstruction.put("parts", sysParts)
        root.put("systemInstruction", sysInstruction)

        val contentsArray = JSONArray()

        var lastRole: String? = null
        for (item in history.takeLast(10)) {
            val geminiRole = if (item.role == "assistant") "model" else "user"
            if (geminiRole == lastRole) continue
            val contentObj = JSONObject()
            contentObj.put("role", geminiRole)
            val parts = JSONArray().apply {
                put(JSONObject().apply { put("text", item.content) })
            }
            contentObj.put("parts", parts)
            contentsArray.put(contentObj)
            lastRole = geminiRole
        }

        val latestObj = JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply {
                put(JSONObject().apply { put("text", incomingMessage) })
            })
        }
        contentsArray.put(latestObj)
        root.put("contents", contentsArray)

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
                val errorDetails = try {
                    val errJson = JSONObject(body ?: "{}")
                    errJson.optJSONObject("error")?.optString("message") ?: body
                } catch (e: Exception) {
                    body
                }
                throw IllegalStateException("Gemini ($modelName) HTTP ${response.code}: $errorDetails")
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
            throw IllegalStateException("No candidate text found in Gemini ($modelName) response")
        }
    }

    private fun sanitizeReply(raw: String): String {
        var reply = raw.trim()

        if ((reply.startsWith("\"") && reply.endsWith("\"")) ||
            (reply.startsWith("'") && reply.endsWith("'"))
        ) {
            reply = reply.substring(1, reply.length - 1).trim()
        }

        val prefixesToRemove = listOf(
            "AI:", "Assistant:", "Dost:", "Me:", "Reply:", "Jawab:",
            "Bot:", "Live Dost:"
        )
        for (prefix in prefixesToRemove) {
            if (reply.startsWith(prefix, ignoreCase = true)) {
                reply = reply.substring(prefix.length).trim()
            }
        }

        reply = reply.replace("```", "").trim()
        return reply
    }
}
