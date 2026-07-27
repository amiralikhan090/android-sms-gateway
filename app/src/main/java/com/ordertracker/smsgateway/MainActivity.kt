package com.ordertracker.smsgateway

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvServerUrl: TextView
    private lateinit var etPort: EditText
    private lateinit var etApiKey: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvLogs: TextView

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

        tvStatus = findViewById(R.id.tvStatus)
        tvServerUrl = findViewById(R.id.tvServerUrl)
        etPort = findViewById(R.id.etPort)
        etApiKey = findViewById(R.id.etApiKey)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvLogs = findViewById(R.id.tvLogs)

        btnStart.setOnClickListener {
            if (checkAndRequestPermissions()) {
                startGatewayService()
            }
        }

        btnStop.setOnClickListener {
            stopGatewayService()
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
        Toast.makeText(this, "Starting SMS Gateway...", Toast.LENGTH_SHORT).show()
    }

    private fun stopGatewayService() {
        val intent = Intent(this, SmsServerService::class.java)
        stopService(intent)
        Toast.makeText(this, "Stopping SMS Gateway...", Toast.LENGTH_SHORT).show()
    }

    private fun updateUiState() {
        val ip = SmsServerService.getLocalIpAddress()
        val running = SmsServerService.isRunning

        if (running) {
            tvStatus.text = "🟢 RUNNING"
            tvStatus.setTextColor(Color.parseColor("#10B981"))
            tvServerUrl.text = "URL: http://$ip:${SmsServerService.activePort}"
            btnStart.isEnabled = false
            btnStop.isEnabled = true
        } else {
            tvStatus.text = "⚪ STOPPED"
            tvStatus.setTextColor(Color.parseColor("#EF4444"))
            tvServerUrl.text = "URL: http://$ip:${etPort.text}"
            btnStart.isEnabled = true
            btnStop.isEnabled = false
        }

        tvLogs.text = "Logs:\n${SmsServerService.lastLog}"
    }

    private fun checkAndRequestPermissions(): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.SEND_SMS
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
                Toast.makeText(this, "Permissions Granted!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "SEND_SMS permission is required!", Toast.LENGTH_LONG).show()
            }
        }
    }
}
