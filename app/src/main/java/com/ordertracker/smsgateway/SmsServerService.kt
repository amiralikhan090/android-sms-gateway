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
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Date

data class SmsRequest(
    val api_key: String? = null,
    val apiKey: String? = null,
    val phone: String? = null,
    val phoneNumber: String? = null,
    val message: String? = null
)

data class BulkSmsRequest(
    val api_key: String? = null,
    val apiKey: String? = null,
    val numbers: List<String>? = null,
    val phones: List<String>? = null,
    val message: String? = null
)

data class TestSmsRequest(
    val phone: String? = null
)


class SmsServerService : Service() {

    private var httpServer: NanoHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var db: SmsDatabase
    private lateinit var queueManager: SmsQueueManager
    private val startTimeMs = System.currentTimeMillis()

    companion object {
        const val CHANNEL_ID = "SmsServerChannel"
        const val NOTIFICATION_ID = 1001
        var isRunning = false
        var activePort = 8080
        var activeApiKey = "MY_SECRET_API_KEY_123"
        var allowedIpSubnet = "192.168."
        var lastLog = "Service Initialized"
        var serviceInstance: SmsServerService? = null

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
        serviceInstance = this
        db = SmsDatabase(applicationContext)
        queueManager = SmsQueueManager(applicationContext, db)

        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OrderTrackerSMS::WakeLock")
        wakeLock?.acquire(10 * 60 * 1000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        activePort = intent?.getIntExtra("PORT", 8080) ?: activePort
        activeApiKey = intent?.getStringExtra("API_KEY") ?: activeApiKey

        startForeground(NOTIFICATION_ID, buildNotification())
        startNanoServer()

        return START_STICKY
    }

    private fun startNanoServer() {
        if (isRunning) return

        try {
            httpServer = NanoHttpServer(activePort)
            httpServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            isRunning = true
            lastLog = "🟢 Server listening on port $activePort"
            updateNotification()
        } catch (e: Exception) {
            Log.e("SmsServer", "Failed starting server", e)
            isRunning = false
            lastLog = "❌ Failed to start: ${e.message}"
        }
    }

    inner class NanoHttpServer(port: Int) : NanoHTTPD(port) {
        private val gson = Gson()

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val method = session.method
            val clientIp = session.headers["http-client-ip"] ?: session.remoteIpAddress ?: "127.0.0.1"

            // IP Whitelist Check (Allow localhost 127.0.0.1 or allowedIpSubnet prefix)
            if (allowedIpSubnet.isNotEmpty() && !clientIp.startsWith("127.0.0.1") && !clientIp.startsWith("localhost") && !clientIp.startsWith(allowedIpSubnet)) {
                val errRes = mapOf("error" to "Forbidden - IP $clientIp not whitelisted")
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", gson.toJson(errRes))
            }

            return when {
                method == Method.GET && uri == "/" -> {
                    val html = "<html><body style='font-family:sans-serif;background:#0f172a;color:#38bdf8;padding:40px;'>" +
                            "<h1>📱 Order Tracker Production SMS Gateway</h1>" +
                            "<p>Status: Healthy | Port: $activePort</p>" +
                            "</body></html>"
                    newFixedLengthResponse(Response.Status.OK, "text/html", html)
                }

                method == Method.GET && (uri == "/health" || uri == "/ping") -> {
                    val batteryPct = getBatteryLevel()
                    val simOperator = getSimOperatorName()
                    val uptimeStr = getUptimeString()
                    val stats = db.getTodayStats()

                    val healthMap = mapOf(
                        "status" to "healthy",
                        "version" to "1.0.0",
                        "queue" to queueManager.pendingCount,
                        "running" to isRunning,
                        "battery" to batteryPct,
                        "network" to simOperator,
                        "signal" to "Excellent",
                        "uptime" to uptimeStr,
                        "sms_today" to stats.totalSent
                    )
                    newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(healthMap))
                }

                method == Method.GET && uri == "/stats" -> {
                    val stats = db.getTodayStats()
                    val resMap = mapOf(
                        "date" to stats.date,
                        "today_sms" to stats.totalSent,
                        "success" to stats.successCount,
                        "failed" to stats.failedCount,
                        "avg_time_sec" to String.format("%.2f", stats.avgDurationMs / 1000.0),
                        "sim_network" to getSimOperatorName(),
                        "signal" to "Excellent"
                    )
                    newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(resMap))
                }

                method == Method.GET && uri == "/logs" -> {
                    val logs = db.getRecentLogs(100)
                    newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(logs))
                }

                method == Method.POST && uri == "/send" -> {
                    try {
                        val files = HashMap<String, String>()
                        session.parseBody(files)
                        val postData = files["postData"] ?: ""
                        val req = gson.fromJson(postData, SmsRequest::class.java)

                        val authHeader = session.headers["authorization"] ?: ""
                        val headerToken = if (authHeader.startsWith("Bearer ", true)) authHeader.substring(7).trim() else ""
                        val key = req?.api_key ?: req?.apiKey ?: headerToken

                        if (key != activeApiKey) {
                            val errRes = mapOf("status" to "failed", "message" to "Invalid API Key")
                            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", gson.toJson(errRes))
                        }

                        val targetPhone = req?.phone ?: req?.phoneNumber ?: ""
                        val targetMsg = req?.message ?: ""

                        if (targetPhone.isEmpty() || targetMsg.isEmpty()) {
                            val errRes = mapOf("status" to "failed", "message" to "Phone and message required")
                            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", gson.toJson(errRes))
                        }

                        val logId = queueManager.enqueue(targetPhone, targetMsg) { success, statusStr, failureReason ->
                            lastLog = if (success) "✅ Sent SMS to $targetPhone" else "❌ Failed sending to $targetPhone ($failureReason)"
                            updateNotification()
                        }

                        val okRes = mapOf(
                            "status" to "success",
                            "message" to "SMS queued for dispatch",
                            "log_id" to logId,
                            "gateway_message_id" to "MSG-$logId"
                        )
                        newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(okRes))
                    } catch (e: Exception) {
                        Log.e("SmsServer", "Send endpoint error", e)
                        val errRes = mapOf("status" to "failed", "message" to "Server Error: ${e.message}")
                        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", gson.toJson(errRes))
                    }
                }

                method == Method.POST && uri == "/bulk" -> {
                    try {
                        val files = HashMap<String, String>()
                        session.parseBody(files)
                        val postData = files["postData"] ?: ""
                        val req = gson.fromJson(postData, BulkSmsRequest::class.java)

                        val authHeader = session.headers["authorization"] ?: ""
                        val headerToken = if (authHeader.startsWith("Bearer ", true)) authHeader.substring(7).trim() else ""
                        val key = req?.api_key ?: req?.apiKey ?: headerToken

                        if (key != activeApiKey) {
                            val errRes = mapOf("status" to "failed", "message" to "Invalid API Key")
                            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", gson.toJson(errRes))
                        }

                        val numbers = req?.numbers ?: req?.phones ?: emptyList()
                        val msg = req?.message ?: ""

                        if (numbers.isEmpty() || msg.isEmpty()) {
                            val errRes = mapOf("status" to "failed", "message" to "Numbers list and message required")
                            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", gson.toJson(errRes))
                        }

                        val logIds = mutableListOf<Long>()
                        for (phone in numbers) {
                            val id = queueManager.enqueue(phone, msg)
                            logIds.add(id)
                        }

                        lastLog = "📥 Enqueued ${numbers.size} bulk SMS messages"
                        updateNotification()

                        val okRes = mapOf(
                            "status" to "success",
                            "message" to "Enqueued ${numbers.size} SMS messages",
                            "total_enqueued" to numbers.size,
                            "log_ids" to logIds
                        )
                        newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(okRes))
                    } catch (e: Exception) {
                        Log.e("SmsServer", "Bulk endpoint error", e)
                        val errRes = mapOf("status" to "failed", "message" to "Server Error: ${e.message}")
                        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", gson.toJson(errRes))
                    }
                }

                method == Method.POST && uri == "/test" -> {
                    try {
                        val files = HashMap<String, String>()
                        session.parseBody(files)
                        val postData = files["postData"] ?: ""
                        val req = gson.fromJson(postData, TestSmsRequest::class.java)
                        val phone = req?.phone ?: "+923073725906"

                        val testMsg = "Order Tracker SMS Gateway Test\nEverything is working."
                        val logId = queueManager.enqueue(phone, testMsg)

                        val okRes = mapOf("status" to "success", "message" to "Test SMS queued to $phone", "log_id" to logId)
                        newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(okRes))
                    } catch (e: Exception) {
                        val errRes = mapOf("status" to "failed", "message" to "Error: ${e.message}")
                        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", gson.toJson(errRes))
                    }
                }

                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"Not Found\"}")
            }
        }
    }

    fun getBatteryLevel(): Int {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
            applicationContext.registerReceiver(null, filter)
        }
        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level != -1 && scale != -1) (level * 100 / scale.toFloat()).toInt() else 100
    }

    fun getSimOperatorName(): String {
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return telephonyManager.simOperatorName.ifEmpty { "Jazz" }
    }

    fun getUptimeString(): String {
        val diffSec = (System.currentTimeMillis() - startTimeMs) / 1000
        val days = diffSec / (24 * 3600)
        val hours = (diffSec % (24 * 3600)) / 3600
        val mins = (diffSec % 3600) / 60
        return if (days > 0) "${days}d ${hours}h" else if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
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

    fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stats = if (::db.isInitialized) db.getTodayStats() else DailyStats("", 0, 0, 0, 0.0)
        val sim = getSimOperatorName()

        val text = "Status: Running | SIM: $sim | Sent Today: ${stats.totalSent} | Listening on Port $activePort"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Order Tracker SMS Gateway")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        try {
            httpServer?.stop()
            wakeLock?.release()
        } catch (e: Exception) {
            Log.e("SmsServer", "Error stopping server", e)
        }
        isRunning = false
        lastLog = "⚪ Service Stopped"
        serviceInstance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
