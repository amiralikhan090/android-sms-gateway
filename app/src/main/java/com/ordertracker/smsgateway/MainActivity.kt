package com.ordertracker.smsgateway

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView

class MainActivity : AppCompatActivity() {

    private lateinit var tvCardStatus: TextView
    private lateinit var tvCardQueue: TextView
    private lateinit var tvCardFailed: TextView
    private lateinit var tvCardTotalSent: TextView
    private lateinit var tvSimDetails: TextView
    private lateinit var tvServerUrl: TextView
    private lateinit var etPort: EditText
    private lateinit var etApiKey: EditText
    private lateinit var etIpSubnet: EditText
    private lateinit var switchAutoStart: SwitchCompat
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnExportCsv: Button
    private lateinit var tvLogs: TextView
    private lateinit var cardPermissionWarning: MaterialCardView
    private lateinit var btnGrantPermission: Button

    private lateinit var db: SmsDatabase
    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateUiState()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = SmsDatabase(this)

        tvCardStatus = findViewById(R.id.tvCardStatus)
        tvCardQueue = findViewById(R.id.tvCardQueue)
        tvCardFailed = findViewById(R.id.tvCardFailed)
        tvCardTotalSent = findViewById(R.id.tvCardTotalSent)
        tvSimDetails = findViewById(R.id.tvSimDetails)
        tvServerUrl = findViewById(R.id.tvServerUrl)
        etPort = findViewById(R.id.etPort)
        etApiKey = findViewById(R.id.etApiKey)
        etIpSubnet = findViewById(R.id.etIpSubnet)
        switchAutoStart = findViewById(R.id.switchAutoStart)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnExportCsv = findViewById(R.id.btnExportCsv)
        tvLogs = findViewById(R.id.tvLogs)
        cardPermissionWarning = findViewById(R.id.cardPermissionWarning)
        btnGrantPermission = findViewById(R.id.btnGrantPermission)

        loadSavedPreferences()

        btnStart.setOnClickListener {
            if (checkAndRequestPermissions()) {
                savePreferences()
                startGatewayService()
            }
        }

        btnStop.setOnClickListener {
            stopGatewayService()
        }

        btnExportCsv.setOnClickListener {
            exportCsvLogs()
        }

        btnGrantPermission.setOnClickListener {
            checkAndRequestPermissions()
        }

        switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            val prefs = getSharedPreferences("SmsGatewayPrefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("auto_start", isChecked).apply()
        }

        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        handler.post(updateRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
    }

    private fun loadSavedPreferences() {
        val prefs = getSharedPreferences("SmsGatewayPrefs", Context.MODE_PRIVATE)
        etPort.setText(prefs.getInt("port", 8080).toString())
        etApiKey.setText(prefs.getString("api_key", "MY_SECRET_API_KEY_123"))
        etIpSubnet.setText(prefs.getString("allowed_ip", "192.168."))
        switchAutoStart.isChecked = prefs.getBoolean("auto_start", true)
    }

    private fun savePreferences() {
        val prefs = getSharedPreferences("SmsGatewayPrefs", Context.MODE_PRIVATE)
        val port = etPort.text.toString().toIntOrNull() ?: 8080
        val key = etApiKey.text.toString().trim()
        val ipSubnet = etIpSubnet.text.toString().trim()

        prefs.edit()
            .putInt("port", port)
            .putString("api_key", key)
            .putString("allowed_ip", ipSubnet)
            .putBoolean("server_enabled", true)
            .apply()

        SmsServerService.activePort = port
        SmsServerService.activeApiKey = key
        SmsServerService.allowedIpSubnet = ipSubnet
    }

    private fun startGatewayService() {
        val port = etPort.text.toString().toIntOrNull() ?: 8080
        val apiKey = etApiKey.text.toString().trim()

        val intent = Intent(this, SmsServerService::class.java).apply {
            putExtra("PORT", port)
            putExtra("API_KEY", apiKey)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Starting Production SMS Gateway...", Toast.LENGTH_SHORT).show()
    }

    private fun stopGatewayService() {
        val intent = Intent(this, SmsServerService::class.java)
        stopService(intent)
        val prefs = getSharedPreferences("SmsGatewayPrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("server_enabled", false).apply()
        Toast.makeText(this, "Stopping Production SMS Gateway...", Toast.LENGTH_SHORT).show()
    }

    private fun exportCsvLogs() {
        val file = db.exportCsvFile()
        if (file != null && file.exists()) {
            Toast.makeText(this, "📁 Exported CSV to:\n${file.absolutePath}", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "❌ Failed exporting CSV logs", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUiState() {
        val ip = SmsServerService.getLocalIpAddress()
        val running = SmsServerService.isRunning
        val hasPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

        if (!hasPerm) {
            cardPermissionWarning.visibility = View.VISIBLE
        } else {
            cardPermissionWarning.visibility = View.GONE
        }

        val stats = db.getTodayStats()
        tvCardTotalSent.text = "${stats.totalSent} Sent"
        tvCardFailed.text = "${stats.failedCount} Failed"

        val activeService = SmsServerService.serviceInstance
        val simName = activeService?.getSimOperatorName() ?: "Jazz"
        val batteryPct = activeService?.getBatteryLevel() ?: 100

        tvSimDetails.text = "Carrier: $simName | Slot: 1 | Battery: $batteryPct% | Signal: Excellent"

        if (running) {
            tvCardStatus.text = "🟢 RUNNING"
            tvCardStatus.setTextColor(Color.parseColor("#10B981"))
            tvServerUrl.text = "URL: http://$ip:${SmsServerService.activePort}"
            btnStart.isEnabled = false
            btnStop.isEnabled = true
        } else {
            tvCardStatus.text = "⚪ STOPPED"
            tvCardStatus.setTextColor(Color.parseColor("#EF4444"))
            tvServerUrl.text = "URL: http://$ip:${etPort.text}"
            btnStart.isEnabled = true
            btnStop.isEnabled = false
        }

        tvLogs.text = "Logs:\n${SmsServerService.lastLog}"
    }

    private fun checkAndRequestPermissions(): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.INTERNET,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.RECEIVE_BOOT_COMPLETED
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                cardPermissionWarning.visibility = View.GONE
                Toast.makeText(this, "Permissions Granted!", Toast.LENGTH_SHORT).show()
            } else {
                cardPermissionWarning.visibility = View.VISIBLE
                Toast.makeText(this, "SEND_SMS permission is required!", Toast.LENGTH_LONG).show()
            }
        }
    }
}
