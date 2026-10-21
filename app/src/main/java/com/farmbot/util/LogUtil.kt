package com.farmbot.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志缓冲
 *
 * 同时输出到 Logcat 与内存环形缓冲（上限 500 条），供悬浮日志窗与调试工具回看。
 */
object LogUtil {

    private const val TAG = "FarmBot"
    private const val MAX_LOGS = 500
    private val logs = mutableListOf<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

/**
 * 写 DEBUG 级日志（内存缓冲 + Logcat）
 */
    fun d(msg: String) {
        Log.d(TAG, msg)
        append("[DEBUG] $msg")
    }

/**
 * 写 INFO 级日志
 */
    fun i(msg: String) {
        Log.i(TAG, msg)
        append("[INFO] $msg")
    }

/**
 * 写 WARN 级日志
 */
    fun w(msg: String) {
        Log.w(TAG, msg)
        append("[WARN] $msg")
    }

/**
 * 写 ERROR 级日志（可带异常）
 */
    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        append("[ERROR] $msg${t?.message?.let { ": $it" } ?: ""}")
    }

/**
 * 最近若干条日志文本
 */
    fun getRecentLogs(n: Int = 50): List<String> = logs.takeLast(n)

/**
 * 全部日志文本
 */
    fun getAllLogs(): List<String> = logs.toList()

/**
 * 清空日志缓冲
 */
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
