package com.livedost.autoreply

import android.app.Notification
import java.util.UUID

data class QueuedMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: String,
    val text: String,
    val action: Notification.Action,
    val timestamp: Long = System.currentTimeMillis(),
    var retryCount: Int = 0,
    val maxRetries: Int = 3,
    var lastError: String? = null
)
