package com.ordertracker.smsgateway

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class SmsQueueItem(
    val id: Long,
    val phone: String,
    val message: String,
    val onResult: ((Boolean, String, String) -> Unit)? = null
)

class SmsQueueManager(private val context: Context, private val db: SmsDatabase) {

    private val queue = Channel<SmsQueueItem>(Channel.UNLIMITED)
    private val scope = CoroutineScope(Dispatchers.IO)
    private var isProcessing = false

    var rateLimitMs: Long = 2000L // Default 1 SMS per 2 seconds
    var maxRetries: Int = 3
    var retryDelayMs: Long = 5000L

    val pendingCount: Int
        get() = if (isProcessing) 1 else 0

    init {
        startConsumer()
    }

    fun enqueue(phone: String, message: String, onResult: ((Boolean, String, String) -> Unit)? = null): Long {
        val logId = db.insertLog(
            LocalSmsLog(
                phone = phone,
                message = message,
                status = "PENDING"
            )
        )

        val item = SmsQueueItem(id = logId, phone = phone, message = message, onResult = onResult)
        scope.launch {
            queue.send(item)
        }
        return logId
    }

    private fun startConsumer() {
        scope.launch {
            for (item in queue) {
                isProcessing = true
                processItem(item)
                isProcessing = false
                delay(rateLimitMs) // Rate limiter pause between SMS dispatches
            }
        }
    }

    private suspend fun processItem(item: SmsQueueItem) {

        val startTime = System.currentTimeMillis()
        var attempt = 0
        var success = false
        var failureReason = "Unknown"

        while (attempt < maxRetries && !success) {
            attempt++
            val (resultSuccess, reason) = sendSmsDirect(item.phone, item.message)
            if (resultSuccess) {
                success = true
                failureReason = ""
            } else {
                failureReason = reason
                if (attempt < maxRetries) {
                    db.updateLogStatus(item.id, "RETRYING", failureReason = failureReason, retryCount = attempt)
                    delay(retryDelayMs)
                }
            }
        }

        val duration = System.currentTimeMillis() - startTime
        val statusStr = if (success) "SUCCESS" else "FAILED"

        db.updateLogStatus(
            id = item.id,
            status = statusStr,
            failureReason = failureReason,
            retryCount = attempt,
            durationMs = duration
        )

        item.onResult?.invoke(success, statusStr, failureReason)
    }

    private fun sendSmsDirect(phone: String, message: String): Pair<Boolean, String> {
        // Pre-flight Environment Checks
        if (isAirplaneModeOn(context)) {
            return Pair(false, "Airplane Mode")
        }

        if (!hasSmsPermission(context)) {
            return Pair(false, "Permission Denied")
        }

        return try {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
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
            Pair(true, "")
        } catch (e: SecurityException) {
            Log.e("SmsQueue", "SecurityException: ${e.message}")
            Pair(false, "Permission Denied")
        } catch (e: Exception) {
            Log.e("SmsQueue", "Dispatch exception: ${e.message}")
            val msg = e.message ?: "Unknown"
            val reason = when {
                msg.contains("MODEM", true) || msg.contains("BUSY", true) -> "SIM Busy"
                msg.contains("SIGNAL", true) || msg.contains("SERVICE", true) -> "No Signal"
                else -> "Unknown Error: $msg"
            }
            Pair(false, reason)
        }
    }

    companion object {
        fun isAirplaneModeOn(context: Context): Boolean {
            return try {
                Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
            } catch (e: Exception) {
                false
            }
        }

        fun hasSmsPermission(context: Context): Boolean {
            return try {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.SEND_SMS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } catch (e: Exception) {
                false
            }
        }
    }
}
