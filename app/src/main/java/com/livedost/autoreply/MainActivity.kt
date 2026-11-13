package com.livedost.autoreply

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.livedost.autoreply.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var appSettings: AppSettings
    private lateinit var memoryManager: ConversationMemoryManager
    private lateinit var logAdapter: ReplyLogAdapter

    private val logUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshLogs()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appSettings = AppSettings(this)
        memoryManager = ConversationMemoryManager(this)

        setupRecyclerView()
        setupPermissionBanner()
        setupBatteryOptimizationCard()
        setupMasterSwitch()
        setupAiEngineSection()
        setupPersonaSection()
        setupSafetyControls()
        setupLogActions()

        // Start Foreground Service
        startServiceIfPermitted()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        updateBatteryOptimizationStatus()
        refreshLogs()
        startServiceIfPermitted()

        val filter = IntentFilter(AppSettings.ACTION_LOGS_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logUpdateReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(logUpdateReceiver)
        } catch (e: Exception) {
            // Receiver not registered
        }
    }

    private fun startServiceIfPermitted() {
        try {
            val serviceIntent = Intent(this, WhatsappAiService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupRecyclerView() {
        logAdapter = ReplyLogAdapter()
        binding.rvReplyLogs.layoutManager = LinearLayoutManager(this)
        binding.rvReplyLogs.adapter = logAdapter
    }

    private fun setupPermissionBanner() {
        binding.btnGrantPermission.setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open Notification Settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupBatteryOptimizationCard() {
        binding.btnBatteryOpt.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    try {
                        startActivity(fallbackIntent)
                    } catch (ex: Exception) {
                        Toast.makeText(this, "Please disable Battery Saver for this app in Settings", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun updateBatteryOptimizationStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val isIgnored = pm.isIgnoringBatteryOptimizations(packageName)
            if (isIgnored) {
                binding.cardBatteryOpt.setCardBackgroundColor(Color.parseColor("#ECFDF5"))
                binding.cardBatteryOpt.strokeColor = Color.parseColor("#A7F3D0")
                binding.btnBatteryOpt.text = "Protected"
                binding.btnBatteryOpt.isEnabled = false
            } else {
                binding.cardBatteryOpt.setCardBackgroundColor(Color.parseColor("#EFF6FF"))
                binding.cardBatteryOpt.strokeColor = Color.parseColor("#BFDBFE")
                binding.btnBatteryOpt.text = "Disable Sleep"
                binding.btnBatteryOpt.isEnabled = true
            }
        }
    }

    private fun updatePermissionStatus() {
        val isGranted = NotificationManagerCompat.getEnabledListenerPackages(this)
            .contains(packageName)

        if (isGranted) {
            binding.cardPermission.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_granted_bg))
            binding.cardPermission.strokeColor = ContextCompat.getColor(this, R.color.primary)
            binding.tvPermissionTitle.text = getString(R.string.permission_banner_granted_title)
            binding.tvPermissionTitle.setTextColor(ContextCompat.getColor(this, R.color.status_granted_text))
            binding.tvPermissionDesc.text = getString(R.string.permission_banner_granted_desc)
            binding.btnGrantPermission.visibility = View.GONE
        } else {
            binding.cardPermission.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_missing_bg))
            binding.cardPermission.strokeColor = ContextCompat.getColor(this, R.color.status_missing_text)
            binding.tvPermissionTitle.text = getString(R.string.permission_banner_missing_title)
            binding.tvPermissionTitle.setTextColor(ContextCompat.getColor(this, R.color.status_missing_text))
            binding.tvPermissionDesc.text = getString(R.string.permission_banner_missing_desc)
            binding.btnGrantPermission.visibility = View.VISIBLE
        }
    }

    private fun setupMasterSwitch() {
        binding.switchMaster.isChecked = appSettings.isMasterEnabled
        binding.switchMaster.setOnCheckedChangeListener { _, isChecked ->
            appSettings.isMasterEnabled = isChecked
            val stateText = if (isChecked) "enabled" else "paused"
            Toast.makeText(this, "Live AI Auto-Reply $stateText", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupAiEngineSection() {
        val currentEngine = appSettings.aiEngine
        if (currentEngine == AppSettings.ENGINE_GEMINI) {
            binding.rbGemini.isChecked = true
            binding.tilGeminiKey.visibility = View.VISIBLE
        } else {
            binding.rbPollinations.isChecked = true
            binding.tilGeminiKey.visibility = View.GONE
        }

        binding.etGeminiKey.setText(appSettings.geminiApiKey)

        binding.rgAiEngine.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbGemini) {
                appSettings.aiEngine = AppSettings.ENGINE_GEMINI
                binding.tilGeminiKey.visibility = View.VISIBLE
            } else {
                appSettings.aiEngine = AppSettings.ENGINE_POLLINATIONS
                binding.tilGeminiKey.visibility = View.GONE
            }
        }

        binding.etGeminiKey.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                appSettings.geminiApiKey = s?.toString()?.trim() ?: ""
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupPersonaSection() {
        binding.etSystemPrompt.setText(appSettings.systemPrompt)

        binding.etSystemPrompt.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val text = s?.toString()?.trim()
                if (!text.isNullOrBlank()) {
                    appSettings.systemPrompt = text
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnResetPrompt.setOnClickListener {
            binding.etSystemPrompt.setText(AppSettings.DEFAULT_SYSTEM_PROMPT)
            appSettings.systemPrompt = AppSettings.DEFAULT_SYSTEM_PROMPT
            Toast.makeText(this, "Reset to default Roman Urdu Dost persona", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSafetyControls() {
        binding.switchIgnoreGroups.isChecked = appSettings.ignoreGroups
        binding.switchIgnoreGroups.setOnCheckedChangeListener { _, isChecked ->
            appSettings.ignoreGroups = isChecked
        }

        binding.etBlacklist.setText(appSettings.blacklist)
        binding.etBlacklist.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                appSettings.blacklist = s?.toString() ?: ""
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val delay = appSettings.typingDelaySeconds
        binding.sliderDelay.value = delay.toFloat()
        binding.tvDelayValue.text = "${delay}s"

        binding.sliderDelay.addOnChangeListener { _, value, _ ->
            val delayInt = value.toInt()
            appSettings.typingDelaySeconds = delayInt
            binding.tvDelayValue.text = "${delayInt}s"
        }
    }

    private fun setupLogActions() {
        binding.btnClearLogs.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear Live Logs")
                .setMessage("Clear the recent activity and error logs?")
                .setPositiveButton("Clear") { _, _ ->
                    appSettings.clearLogs()
                    refreshLogs()
                    Toast.makeText(this, "Activity logs cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnCopyLogs.setOnClickListener {
            val logs = appSettings.getLogs()
            if (logs.isEmpty()) {
                Toast.makeText(this, "No logs to copy", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val logText = buildString {
                append("=== LIVE AI DOST DIAGNOSTIC LOGS ===\n")
                logs.forEach { log ->
                    append("[${log.formattedTime}] Status: ${log.status} | Attempts: ${log.attempts} | Engine: ${log.engine}\n")
                    append("Sender: ${log.sender}\n")
                    append("Message: ${log.incomingMessage}\n")
                    if (log.aiReply.isNotBlank()) append("Reply: ${log.aiReply}\n")
                    if (!log.errorReason.isNullOrBlank()) append("Error Details: ${log.errorReason}\n")
                    append("Duration: ${log.durationMs}ms\n")
                    append("------------------------------------\n")
                }
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Live AI Dost Logs", logText)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Logs copied to clipboard!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshLogs() {
        val logs = appSettings.getLogs()
        logAdapter.updateList(logs)

        // Calculate Stats
        val sentCount = logs.count { it.status == ReplyLogItem.STATUS_SENT }
        val retryCount = logs.count { it.attempts > 1 }
        val failedCount = logs.count { it.status == ReplyLogItem.STATUS_FAILED }

        binding.chipSentCount.text = "Sent: $sentCount"
        binding.chipRetryCount.text = "Retried: $retryCount"
        binding.chipFailedCount.text = "Failed: $failedCount"

        if (logs.isEmpty()) {
            binding.tvLogsEmpty.visibility = View.VISIBLE
            binding.rvReplyLogs.visibility = View.GONE
        } else {
            binding.tvLogsEmpty.visibility = View.GONE
            binding.rvReplyLogs.visibility = View.VISIBLE
        }
    }
}
