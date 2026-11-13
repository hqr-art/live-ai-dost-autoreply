package com.livedost.autoreply

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.livedost.autoreply.databinding.ItemReplyLogBinding
import java.util.Locale

class ReplyLogAdapter(
    private var items: List<ReplyLogItem> = emptyList()
) : RecyclerView.Adapter<ReplyLogAdapter.LogViewHolder>() {

    class LogViewHolder(val binding: ItemReplyLogBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding = ItemReplyLogBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val item = items[position]
        with(holder.binding) {
            tvSender.text = item.sender
            tvIncomingMessage.text = item.incomingMessage
            tvTime.text = item.formattedTime

            // Status Badge & Color
            tvStatusBadge.text = item.status
            when (item.status) {
                ReplyLogItem.STATUS_SENT -> {
                    tvStatusBadge.setBackgroundColor(Color.parseColor("#059669")) // Green
                }
                ReplyLogItem.STATUS_RETRYING -> {
                    tvStatusBadge.setBackgroundColor(Color.parseColor("#D97706")) // Amber
                }
                ReplyLogItem.STATUS_FAILED -> {
                    tvStatusBadge.setBackgroundColor(Color.parseColor("#DC2626")) // Red
                }
                ReplyLogItem.STATUS_PROCESSING -> {
                    tvStatusBadge.setBackgroundColor(Color.parseColor("#2563EB")) // Blue
                }
                else -> {
                    tvStatusBadge.setBackgroundColor(Color.parseColor("#64748B")) // Slate
                }
            }

            // Engine Badge
            val isGemini = item.engine.contains("gemini", ignoreCase = true)
            tvEngineBadge.text = item.engine
            tvEngineBadge.setBackgroundColor(
                if (isGemini) Color.parseColor("#7C3AED") else Color.parseColor("#1D4ED8")
            )

            // Attempt Counter
            tvAttemptBadge.text = "• Attempt ${item.attempts}/3"

            // Latency / Duration
            if (item.durationMs > 0) {
                val secs = item.durationMs / 1000.0
                tvDurationBadge.text = String.format(Locale.getDefault(), "• %.1fs", secs)
                tvDurationBadge.visibility = View.VISIBLE
            } else {
                tvDurationBadge.visibility = View.GONE
            }

            // AI Reply
            if (item.aiReply.isNotBlank()) {
                tvAiReply.text = item.aiReply
                layoutReplyBox.visibility = View.VISIBLE
            } else if (item.status == ReplyLogItem.STATUS_PROCESSING || item.status == ReplyLogItem.STATUS_RETRYING) {
                tvAiReply.text = "Thinking in Roman Urdu..."
                layoutReplyBox.visibility = View.VISIBLE
            } else {
                layoutReplyBox.visibility = View.GONE
            }

            // Error & Diagnostic details
            if (!item.errorReason.isNullOrBlank()) {
                tvErrorReason.text = item.errorReason
                layoutErrorBox.visibility = View.VISIBLE
            } else {
                layoutErrorBox.visibility = View.GONE
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateList(newItems: List<ReplyLogItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
