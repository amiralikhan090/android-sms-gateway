package com.ordertracker.smsgateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

data class SmsRequest(
    val api_key: String? = null,
    val apiKey: String? = null,
    val phone: String? = null,
    val phoneNumber: String? = null,
    val message: String? = null
)

data class SmsResponse(
    val success: Boolean,
    val message: String,
    val gateway_message_id: String? = null
)

data class HealthResponse(
    val status: String,
    val battery_level: Int,
    val is_charging: Boolean,
    val sim_operator: String,
    val ip_address: String
)

class SmsServerService : Service() {

    private var serverEngine: ApplicationEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val gson = Gson()

    companion object {
        const val CHANNEL_ID = "SmsServerChannel"
        const val NOTIFICATION_ID = 1001
        var isRunning = false
        var activePort = 8080
        var activeApiKey = "MY_SECRET_API_KEY_123"

        var lastLog = "Service Initialized"

        fun getLocalIpAddress(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val intf = interfaces.nextElement()
                    val addrs = intf.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            return addr.hostAddress ?: "127.0.0.1"
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("SmsServer", "Error getting IP: ${e.message}")
            }
            return "127.0.0.1"
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OrderTrackerSMS::WakeLock")
        wakeLock?.acquire(10 * 60 * 1000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        activePort = intent?.getIntExtra("PORT", 8080) ?: 8080
        activeApiKey = intent?.getStringExtra("API_KEY") ?: activeApiKey

        startForeground(NOTIFICATION_ID, buildNotification())
        startKtorServer()

        return START_STICKY
    }

    private fun startKtorServer() {
        if (isRunning) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                serverEngine = embeddedServer(CIO, port = activePort) {
                    install(ContentNegotiation) {
                        gson()
                    }
                    routing {
                        get("/") {
                            call.respondText(
                                "<html><body style='font-family:sans-serif;background:#0f172a;color:#38bdf8;padding:40px;'>" +
                                        "<h1>📱 Order Tracker SMS Gateway</h1>" +
                                        "<p>Server is running cleanly on Android.</p>" +
                                        "</body></html>",
                                io.ktor.http.ContentType.Text.Html
                            )
                        }

                        get("/ping") {
                            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
                                applicationContext.registerReceiver(null, filter)
                            }
                            val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                            val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                            val batteryPct = if (level != -1 && scale != -1) (level * 100 / scale.toFloat()).toInt() else 100
                            val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

                            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                            val simOperator = telephonyManager.simOperatorName.ifEmpty { "Jazz / SIM" }

                            call.respond(
                                HealthResponse(
                                    status = "online",
                                    battery_level = batteryPct,
                                    is_charging = isCharging,
                                    sim_operator = simOperator,
                                    ip_address = getLocalIpAddress()
                                )
                            )
                        }

                        post("/send") {
                            try {
                                val bodyText = call.receiveText()
                                val req = gson.fromJson(bodyText, SmsRequest::class.java)

                                val key = req.api_key ?: req.apiKey ?: ""
                                val targetPhone = req.phone ?: req.phoneNumber ?: ""
                                val targetMsg = req.message ?: ""

                                if (key != activeApiKey) {
                                    call.respond(HttpStatusCode.Unauthorized, SmsResponse(false, "Invalid API Key"))
                                    return@post
                                }

                                if (targetPhone.isEmpty() || targetMsg.isEmpty()) {
                                    call.respond(HttpStatusCode.BadRequest, SmsResponse(false, "Phone and message required"))
                                    return@post
                                }

                                val result = dispatchSms(targetPhone, targetMsg)
                                if (result) {
                                    lastLog = "✅ Sent SMS to $targetPhone"
                                    call.respond(HttpStatusCode.OK, SmsResponse(true, "SMS dispatched successfully", "MSG-${System.currentTimeMillis()}"))
                                } else {
                                    lastLog = "❌ Failed sending SMS to $targetPhone"
                                    call.respond(HttpStatusCode.InternalServerError, SmsResponse(false, "Failed dispatching SMS via SmsManager"))
                                }
                            } catch (e: Exception) {
                                Log.e("SmsServer", "Send endpoint error", e)
                                call.respond(HttpStatusCode.InternalServerError, SmsResponse(false, "Server Error: ${e.message}"))
                            }
                        }
                    }
                }
                serverEngine?.start(wait = false)
                isRunning = true
                lastLog = "🟢 Ktor server listening on port $activePort"
            } catch (e: Exception) {
                Log.e("SmsServer", "Failed starting server", e)
                isRunning = false
                lastLog = "❌ Failed to start: ${e.message}"
            }
        }
    }

    private fun dispatchSms(phone: String, message: String): Boolean {
        return try {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                applicationContext.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(phone, null, message, null, null)
            }
            true
        } catch (e: Exception) {
            Log.e("SmsServer", "SmsManager exception", e)
            false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Order Tracker SMS Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Order Tracker SMS Server")
            .setContentText("Listening for HTTP SMS requests on port $activePort")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        try {
            serverEngine?.stop(1000, 2000)
            wakeLock?.release()
        } catch (e: Exception) {
            Log.e("SmsServer", "Error stopping server", e)
        }
        isRunning = false
        lastLog = "⚪ Service Stopped"
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
