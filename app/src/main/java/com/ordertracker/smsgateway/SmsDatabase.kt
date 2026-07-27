package com.ordertracker.smsgateway

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LocalSmsLog(
    val id: Long = 0,
    val phone: String,
    val message: String,
    val status: String, // SUCCESS, FAILED, PENDING
    val failureReason: String = "",
    val retryCount: Int = 0,
    val date: String = "",
    val durationMs: Long = 0
)

data class DailyStats(
    val date: String,
    val totalSent: Int,
    val successCount: Int,
    val failedCount: Int,
    val avgDurationMs: Double
)

class SmsDatabase(private val context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "sms_gateway.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_SMS_LOGS = "sms_logs"

        fun getTodayDateString(): String {
            return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        }

        fun getNowTimestamp(): String {
            return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_SMS_LOGS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                phone TEXT NOT NULL,
                message TEXT NOT NULL,
                status TEXT NOT NULL,
                failure_reason TEXT,
                retry_count INTEGER DEFAULT 0,
                created_at TEXT NOT NULL,
                duration_ms INTEGER DEFAULT 0
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SMS_LOGS")
        onCreate(db)
    }

    fun insertLog(log: LocalSmsLog): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("phone", log.phone)
            put("message", log.message)
            put("status", log.status)
            put("failure_reason", log.failureReason)
            put("retry_count", log.retryCount)
            put("created_at", if (log.date.isEmpty()) getNowTimestamp() else log.date)
            put("duration_ms", log.durationMs)
        }
        val id = db.insert(TABLE_SMS_LOGS, null, values)
        cleanupOldLogs()
        return id
    }

    fun updateLogStatus(id: Long, status: String, failureReason: String = "", retryCount: Int = 0, durationMs: Long = 0) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("status", status)
            if (failureReason.isNotEmpty()) put("failure_reason", failureReason)
            put("retry_count", retryCount)
            if (durationMs > 0) put("duration_ms", durationMs)
        }
        db.update(TABLE_SMS_LOGS, values, "id = ?", arrayOf(id.toString()))
    }

    fun getRecentLogs(limit: Int = 100): List<LocalSmsLog> {
        val list = mutableListOf<LocalSmsLog>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_SMS_LOGS ORDER BY id DESC LIMIT ?", arrayOf(limit.toString()))
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    LocalSmsLog(
                        id = it.getLong(it.getColumnIndexOrThrow("id")),
                        phone = it.getString(it.getColumnIndexOrThrow("phone")),
                        message = it.getString(it.getColumnIndexOrThrow("message")),
                        status = it.getString(it.getColumnIndexOrThrow("status")),
                        failureReason = it.getString(it.getColumnIndexOrThrow("failure_reason")) ?: "",
                        retryCount = it.getInt(it.getColumnIndexOrThrow("retry_count")),
                        date = it.getString(it.getColumnIndexOrThrow("created_at")),
                        durationMs = it.getLong(it.getColumnIndexOrThrow("duration_ms"))
                    )
                )
            }
        }
        return list
    }

    fun getTodayStats(): DailyStats {
        val today = getTodayDateString()
        val db = readableDatabase
        var total = 0
        var success = 0
        var failed = 0
        var totalDuration = 0L

        val cursor = db.rawQuery("SELECT status, duration_ms FROM $TABLE_SMS_LOGS WHERE created_at LIKE ?", arrayOf("$today%"))
        cursor.use {
            while (it.moveToNext()) {
                total++
                val st = it.getString(0)
                val dur = it.getLong(1)
                totalDuration += dur

                if (st == "SUCCESS" || st == "SENT") {
                    success++
                } else if (st == "FAILED") {
                    failed++
                }
            }
        }

        val avgMs = if (success > 0) totalDuration.toDouble() / success else 0.0
        return DailyStats(
            date = today,
            totalSent = total,
            successCount = success,
            failedCount = failed,
            avgDurationMs = avgMs
        )
    }

    fun cleanupOldLogs(daysToKeep: Int = 30) {
        try {
            val cutoff = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(
                Date(System.currentTimeMillis() - daysToKeep * 24 * 60 * 60 * 1000L)
            )
            writableDatabase.delete(TABLE_SMS_LOGS, "created_at < ?", arrayOf(cutoff))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun exportCsvFile(): File? {
        return try {
            val file = File(context.getExternalFilesDir(null), "sms_gateway_logs.csv")
            val writer = FileWriter(file)
            writer.append("ID,Date,Phone,Status,FailureReason,Retries,DurationMs,Message\n")

            val logs = getRecentLogs(1000)
            for (log in logs) {
                val cleanMsg = log.message.replace("\"", "\"\"").replace("\n", " ")
                writer.append("${log.id},\"${log.date}\",\"${log.phone}\",\"${log.status}\",\"${log.failureReason}\",${log.retryCount},${log.durationMs},\"$cleanMsg\"\n")
            }
            writer.flush()
            writer.close()
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
