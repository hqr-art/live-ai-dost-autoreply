package com.livedost.autoreply

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.livedost.autoreply.databinding.ItemReplyLogBinding

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
            tvAiReply.text = item.aiReply
            tvTime.text = item.formattedTime

            val isGemini = item.engine.equals(AppSettings.ENGINE_GEMINI, ignoreCase = true)
            tvEngineBadge.text = if (isGemini) "Gemini 2.5" else "Pollinations"
            tvEngineBadge.setBackgroundColor(
                if (isGemini) Color.parseColor("#7C3AED") else Color.parseColor("#2563EB")
            )
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateList(newItems: List<ReplyLogItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
