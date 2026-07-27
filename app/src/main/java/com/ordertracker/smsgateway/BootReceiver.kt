package com.ordertracker.smsgateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.i("BootReceiver", "📱 Device reboot complete. Checking Auto-Start setting...")

            val prefs = context.getSharedPreferences("SmsGatewayPrefs", Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("auto_start", true)
            val serverEnabled = prefs.getBoolean("server_enabled", true)

            if (autoStart && serverEnabled) {
                Log.i("BootReceiver", "🚀 Auto-Starting Order Tracker SMS Gateway Service...")
                val port = prefs.getInt("port", 8080)
                val apiKey = prefs.getString("api_key", "MY_SECRET_API_KEY_123") ?: "MY_SECRET_API_KEY_123"

                val serviceIntent = Intent(context, SmsServerService::class.java).apply {
                    putExtra("PORT", port)
                    putExtra("API_KEY", apiKey)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } else {
                Log.i("BootReceiver", "⏸️ Auto-Start disabled in preferences. Skipping launch.")
            }
        }
    }
}
