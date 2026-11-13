package com.livedost.autoreply

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class WhatsappAiService : NotificationListenerService() {

    companion object {
        private const val TAG = "WhatsappAiService"
        private const val WHATSAPP_PKG = "com.whatsapp"
        private const val WHATSAPP_BUSINESS_PKG = "com.whatsapp.w4b"

        // Deduplication cache: key -> timestamp of processing
        private val recentReplies = ConcurrentHashMap<String, Long>()
        private const val DEDUPLICATION_WINDOW_MS = 30_000L // 30 seconds
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var appSettings: AppSettings
    private lateinit var memoryManager: ConversationMemoryManager
    private lateinit var aiChatEngine: AiChatEngine

    override fun onCreate() {
        super.onCreate()
        appSettings = AppSettings(applicationContext)
        memoryManager = ConversationMemoryManager(applicationContext)
        aiChatEngine = AiChatEngine(appSettings)
        Log.i(TAG, "WhatsappAiService initialized.")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        Log.i(TAG, "WhatsappAiService destroyed.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val pkg = sbn.packageName ?: return
        if (pkg != WHATSAPP_PKG && pkg != WHATSAPP_BUSINESS_PKG) {
            return
        }

        if (!appSettings.isMasterEnabled) {
            Log.d(TAG, "Auto-reply is paused by Master Switch.")
            return
        }

        val notification = sbn.notification ?: return

        // 1. Ignore ongoing notifications (calls, uploading status, etc.)
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) {
            return
        }

        // 2. Ignore group summary notifications
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            return
        }

        val extras = notification.extras ?: return

        // Extract Title and Text
        val titleSeq = extras.getCharSequence(Notification.EXTRA_TITLE)
        val textSeq = extras.getCharSequence(Notification.EXTRA_TEXT)

        if (titleSeq.isNullOrBlank() || textSeq.isNullOrBlank()) {
            return
        }

        val title = titleSeq.toString().trim()
        val text = textSeq.toString().trim()

        // 3. Ignore self-echo notifications (e.g. title is "You")
        if (title.equals("You", ignoreCase = true) || title.equals("Aap", ignoreCase = true)) {
            return
        }

        // 4. Ignore pure media notifications without text (photos, voice notes, etc.)
        if (isMediaOnlyMessage(text)) {
            Log.d(TAG, "Ignoring media-only message: $text")
            return
        }

        // 5. Detect group conversations
        val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) ||
                extras.containsKey("android.conversationTitle") ||
                title.contains(":") ||
                title.contains("@g.us")

        if (isGroup && appSettings.ignoreGroups) {
            Log.d(TAG, "Ignoring group chat message from: $title")
            return
        }

        // 6. Clean sender name
        val sender = cleanSenderName(title)

        // 7. Check Blacklist (e.g., Ammi, Abba, Boss)
        if (appSettings.isSenderBlacklisted(sender)) {
            Log.d(TAG, "Sender '$sender' is in Do-Not-Reply list. Skipping.")
            return
        }

        // 8. Deduplication check without blocking continuous conversation
        val messageHash = "${sender.lowercase()}:${text.lowercase()}"
        val now = System.currentTimeMillis()
        val lastProcessed = recentReplies[messageHash]

        if (lastProcessed != null && (now - lastProcessed) < DEDUPLICATION_WINDOW_MS) {
            Log.d(TAG, "Skipping duplicate notification for '$sender': $text")
            return
        }

        // 9. Extract RemoteInput Action
        val replyAction = findReplyAction(notification)
        if (replyAction == null) {
            Log.w(TAG, "No RemoteInput Reply Action found in notification for: $sender")
            return
        }

        // Mark as processed to prevent duplicate triggers from notification updates
        recentReplies[messageHash] = now
        cleanupDeduplicationCache(now)

        // 10. Process in background coroutine: Fetch AI response and send reply
        serviceScope.launch {
            handleIncomingMessage(sender, text, replyAction)
        }
    }

    private suspend fun handleIncomingMessage(
        sender: String,
        messageText: String,
        action: Notification.Action
    ) {
        try {
            Log.d(TAG, "Processing message from '$sender': $messageText")

            // Fetch rolling memory history for this contact
            val history = memoryManager.getHistory(sender)

            // Save user incoming message to memory
            memoryManager.addMessage(sender, role = "user", content = messageText)

            // Generate AI Roman Urdu response
            val aiReply = aiChatEngine.generateReply(sender, messageText, history)

            if (aiReply.isBlank()) {
                Log.w(TAG, "AI generated an empty reply, aborting send.")
                return
            }

            // Natural human typing delay
            val delaySeconds = appSettings.typingDelaySeconds.coerceIn(1, 15)
            Log.d(TAG, "Simulating human typing delay: ${delaySeconds}s...")
            delay(delaySeconds * 1000L)

            // Fire RemoteInput PendingIntent
            val sentSuccessfully = sendRemoteInputReply(action, aiReply)

            if (sentSuccessfully) {
                // Save AI reply to memory for multi-turn context
                memoryManager.addMessage(sender, role = "assistant", content = aiReply)

                // Add to live log
                val logItem = ReplyLogItem(
                    sender = sender,
                    incomingMessage = messageText,
                    aiReply = aiReply,
                    engine = appSettings.aiEngine
                )
                appSettings.addLog(logItem)

                // Broadcast update to MainActivity
                sendBroadcast(Intent(AppSettings.ACTION_LOGS_UPDATED).apply {
                    setPackage(packageName)
                })

                Log.i(TAG, "Successfully replied to '$sender' in Roman Urdu: '$aiReply'")
            } else {
                Log.e(TAG, "Failed to send RemoteInput reply to '$sender'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message from '$sender'", e)
        }
    }

    /**
     * Traverses notification actions to extract the RemoteInput Reply Action.
     */
    private fun findReplyAction(notification: Notification): Notification.Action? {
        // Standard notification actions
        val actions = notification.actions
        if (actions != null) {
            for (action in actions) {
                if (action.remoteInputs != null && action.remoteInputs.isNotEmpty()) {
                    return action
                }
            }
        }

        // WearableExtender actions fallback
        val wearableExtender = Notification.WearableExtender(notification)
        for (action in wearableExtender.actions) {
            if (action.remoteInputs != null && action.remoteInputs.isNotEmpty()) {
                return action
            }
        }

        return null
    }

    /**
     * Fills the RemoteInput bundle and sends the pending intent.
     */
    private fun sendRemoteInputReply(action: Notification.Action, replyText: String): Boolean {
        return try {
            val remoteInputs = action.remoteInputs ?: return false
            val intent = Intent()
            val bundle = Bundle()

            for (input in remoteInputs) {
                bundle.putCharSequence(input.resultKey, replyText)
            }

            RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
            action.actionIntent.send(applicationContext, 0, intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Exception while triggering PendingIntent", e)
            false
        }
    }

    private fun cleanSenderName(title: String): String {
        return if (title.contains(":")) {
            title.substringBefore(":").trim()
        } else {
            title
        }
    }

    private fun isMediaOnlyMessage(text: String): Boolean {
        val lower = text.lowercase()
        return lower.startsWith("📷 photo") ||
                lower.startsWith("📹 video") ||
                lower.startsWith("🎤 voice message") ||
                lower.startsWith("🎵 audio") ||
                lower.startsWith("📄 document") ||
                lower.startsWith("contact:") ||
                lower == "photo" ||
                lower == "sticker" ||
                lower == "gif"
    }

    private fun cleanupDeduplicationCache(currentTime: Long) {
        val iterator = recentReplies.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (currentTime - entry.value > DEDUPLICATION_WINDOW_MS) {
                iterator.remove()
            }
        }
    }
}
