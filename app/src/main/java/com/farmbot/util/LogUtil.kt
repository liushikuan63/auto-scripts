package com.farmbot.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogUtil {

    private const val TAG = "FarmBot"
    private const val MAX_LOGS = 500
    private val logs = mutableListOf<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    fun d(msg: String) {
        Log.d(TAG, msg)
        append("[DEBUG] $msg")
    }

    fun i(msg: String) {
        Log.i(TAG, msg)
        append("[INFO] $msg")
    }

    fun w(msg: String) {
        Log.w(TAG, msg)
        append("[WARN] $msg")
    }

    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        append("[ERROR] $msg${t?.message?.let { ": $it" } ?: ""}")
    }

    fun getRecentLogs(n: Int = 50): List<String> = logs.takeLast(n)

    fun getAllLogs(): List<String> = logs.toList()

    fun clearLogs() {
        logs.clear()
    }

    private fun append(line: String) {
        val timestamp = dateFormat.format(Date())
        synchronized(logs) {
            logs.add("$timestamp $line")
            if (logs.size > MAX_LOGS) {
                logs.removeAt(0)
            }
        }
    }
}