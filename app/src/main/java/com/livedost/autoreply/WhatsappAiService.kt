package com.livedost.autoreply

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class WhatsappAiService : NotificationListenerService() {

    companion object {
        private const val TAG = "WhatsappAiService"
        private const val WHATSAPP_PKG = "com.whatsapp"
        private const val WHATSAPP_BUSINESS_PKG = "com.whatsapp.w4b"

        private const val FOREGROUND_CHANNEL_ID = "live_ai_foreground_service_channel"
        private const val FOREGROUND_NOTIFICATION_ID = 2026

        // Deduplication cache: key -> timestamp of processing
        private val recentReplies = ConcurrentHashMap<String, Long>()
        private const val DEDUPLICATION_WINDOW_MS = 25_000L // 25 seconds
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    // Message backoff queue
    private val messageQueue = Channel<QueuedMessage>(capacity = Channel.UNLIMITED)

    private lateinit var appSettings: AppSettings
    private lateinit var memoryManager: ConversationMemoryManager
    private lateinit var aiChatEngine: AiChatEngine

    override fun onCreate() {
        super.onCreate()
        appSettings = AppSettings(applicationContext)
        memoryManager = ConversationMemoryManager(applicationContext)
        aiChatEngine = AiChatEngine(appSettings)

        startForegroundServiceNotification()
        startQueueWorker()
        Log.i(TAG, "WhatsappAiService initialized with foreground notification and queue worker.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceNotification()
        return START_STICKY
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "NotificationListener connected.")
        startForegroundServiceNotification()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        messageQueue.close()
        Log.i(TAG, "WhatsappAiService destroyed.")
    }

    private fun startForegroundServiceNotification() {
        try {
            createNotificationChannel()

            val launchIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val engineName = if (appSettings.aiEngine == AppSettings.ENGINE_GEMINI) "Google Gemini" else "Pollinations Free AI"

            val notification = NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
                .setContentTitle("Live AI Dost is Active")
                .setContentText("Auto-replying in Roman Urdu ($engineName)")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pendingIntent)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    FOREGROUND_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not start foreground notification", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                "Live AI Dost Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Live AI Dost active in background to auto-reply without missing messages"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
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

        // 1. Ignore ongoing notifications (voice/video calls, uploading media)
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

        // 4. Ignore pure media-only notifications
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

        // 7. Check Blacklist
        if (appSettings.isSenderBlacklisted(sender)) {
            Log.d(TAG, "Sender '$sender' is blacklisted. Skipping.")
            return
        }

        // 8. Deduplication check without blocking rapid conversation
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
            Log.w(TAG, "No RemoteInput Reply Action found for: $sender")
            return
        }

        recentReplies[messageHash] = now
        cleanupDeduplicationCache(now)

        // 10. Enqueue message into backoff processing queue
        val queuedMsg = QueuedMessage(
            sender = sender,
            text = text,
            action = replyAction
        )

        // Log initial queued state
        val initialLog = ReplyLogItem(
            id = queuedMsg.id,
            sender = sender,
            incomingMessage = text,
            engine = appSettings.aiEngine,
            status = ReplyLogItem.STATUS_QUEUED,
            attempts = 0
        )
        appSettings.addLog(initialLog)
        notifyLogsUpdated()

        serviceScope.launch {
            messageQueue.send(queuedMsg)
        }
    }

    /**
     * Dedicated background queue worker processing messages one by one with retry and exponential backoff.
     */
    private fun startQueueWorker() {
        serviceScope.launch {
            for (msg in messageQueue) {
                processQueuedMessageWithBackoff(msg)
            }
        }
    }

    private suspend fun processQueuedMessageWithBackoff(msg: QueuedMessage) {
        val sender = msg.sender
        val text = msg.text
        var success = false

        while (msg.retryCount < msg.maxRetries && !success) {
            msg.retryCount++
            val isRetry = msg.retryCount > 1

            // Update log status to PROCESSING or RETRYING
            val currentStatus = if (isRetry) ReplyLogItem.STATUS_RETRYING else ReplyLogItem.STATUS_PROCESSING
            appSettings.updateLog(
                ReplyLogItem(
                    id = msg.id,
                    sender = sender,
                    incomingMessage = text,
                    engine = appSettings.aiEngine,
                    status = currentStatus,
                    attempts = msg.retryCount,
                    errorReason = msg.lastError
                )
            )
            notifyLogsUpdated()

            try {
                // Natural human typing delay on first attempt only
                if (!isRetry) {
                    val delaySeconds = appSettings.typingDelaySeconds.coerceIn(1, 10)
                    delay(delaySeconds * 1000L)
                }

                // Fetch multi-turn memory history
                val history = memoryManager.getHistory(sender)

                // Call AI Engine (if retryCount >= 2 and Gemini was failing, force Pollinations fallback)
                val forceFallback = (msg.retryCount >= 2 && appSettings.aiEngine == AppSettings.ENGINE_GEMINI)
                val result = aiChatEngine.generateReplyWithDetails(
                    sender = sender,
                    incomingMessage = text,
                    history = history,
                    forcePollinations = forceFallback
                )

                if (result.isSuccess && result.reply.isNotBlank()) {
                    // Send RemoteInput
                    val sent = sendRemoteInputReply(msg.action, result.reply)

                    if (sent) {
                        // Persist turns to memory
                        memoryManager.addMessage(sender, role = "user", content = text)
                        memoryManager.addMessage(sender, role = "assistant", content = result.reply)

                        // Finalize log as SENT
                        appSettings.updateLog(
                            ReplyLogItem(
                                id = msg.id,
                                sender = sender,
                                incomingMessage = text,
                                aiReply = result.reply,
                                engine = result.engineUsed,
                                status = ReplyLogItem.STATUS_SENT,
                                attempts = msg.retryCount,
                                durationMs = result.durationMs,
                                errorReason = result.errorDetails
                            )
                        )
                        notifyLogsUpdated()
                        Log.i(TAG, "Sent reply to '$sender' (attempt ${msg.retryCount}) in ${result.durationMs}ms: '${result.reply}'")
                        success = true
                        break
                    } else {
                        msg.lastError = "RemoteInput PendingIntent failed to fire"
                        Log.w(TAG, "Attempt ${msg.retryCount}: ${msg.lastError}")
                    }
                } else {
                    msg.lastError = result.errorDetails ?: "Empty AI response"
                    Log.w(TAG, "Attempt ${msg.retryCount} AI generation failed: ${msg.lastError}")
                }
            } catch (e: Exception) {
                msg.lastError = e.message ?: e.javaClass.simpleName
                Log.e(TAG, "Attempt ${msg.retryCount} encountered exception", e)
            }

            // If not successful and more retries available, apply exponential backoff
            if (!success && msg.retryCount < msg.maxRetries) {
                val backoffMs = 1500L * (1 shl (msg.retryCount - 1)) // 1.5s, 3s
                Log.w(TAG, "Backing off for ${backoffMs}ms before retry ${msg.retryCount + 1} for '$sender'...")
                delay(backoffMs)
            }
        }

        // If all retries exhausted without success
        if (!success) {
            appSettings.updateLog(
                ReplyLogItem(
                    id = msg.id,
                    sender = sender,
                    incomingMessage = text,
                    engine = appSettings.aiEngine,
                    status = ReplyLogItem.STATUS_FAILED,
                    attempts = msg.retryCount,
                    errorReason = msg.lastError ?: "All ${msg.maxRetries} reply attempts failed"
                )
            )
            notifyLogsUpdated()
            Log.e(TAG, "Message from '$sender' permanently failed after ${msg.maxRetries} attempts: ${msg.lastError}")
        }
    }

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

    private fun findReplyAction(notification: Notification): Notification.Action? {
        val actions = notification.actions
        if (actions != null) {
            for (action in actions) {
                if (action.remoteInputs != null && action.remoteInputs.isNotEmpty()) {
                    return action
                }
            }
        }

        val wearableExtender = Notification.WearableExtender(notification)
        for (action in wearableExtender.actions) {
            if (action.remoteInputs != null && action.remoteInputs.isNotEmpty()) {
                return action
            }
        }

        return null
    }

    private fun notifyLogsUpdated() {
        try {
            sendBroadcast(Intent(AppSettings.ACTION_LOGS_UPDATED).apply {
                setPackage(packageName)
            })
        } catch (e: Exception) {
            e.printStackTrace()
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
