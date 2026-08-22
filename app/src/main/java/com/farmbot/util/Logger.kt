package com.farmbot.util

import android.content.Context
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 日志系统 — 融合 HonorFarmBot 版本
 *
 * 分级(DEBUG/INFO/WARN/ERROR) + 文件持久化 + 环形缓冲区 + Timber Tree 桥接
 */
object Logger {

    private const val MAX_BUFFER = 500
    private val buffer = ConcurrentLinkedQueue<LogEntry>()
    private var logFile: File? = null
    private var appContext: Context? = null
    private val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)

    fun init(context: Context) {
        appContext = context.applicationContext
        val dir = File(context.getExternalFilesDir(null), "FarmBot/logs")
        dir.mkdirs()
        val today = dayFmt.format(Date())
        logFile = File(dir, "farmbot_$today.log")
    }

    private fun log(level: Level, tag: String, msg: String, t: Throwable? = null) {
        val full = if (t == null) msg else "$msg\n${t.stackTraceToString()}"
        val entry = LogEntry(level, tag, full)
        buffer.add(entry)
        while (buffer.size > MAX_BUFFER) buffer.poll()
        runCatching {
            val f = logFile ?: return@runCatching
            FileWriter(f, true).use { w ->
                w.write("${df.format(Date(entry.timestamp))} [${level.name}] $tag: ${entry.message}\n")
            }
        }
    }

    fun d(tag: String, msg: String) = log(Level.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(Level.INFO, tag, msg)
    fun w(tag: String, msg: String) = log(Level.WARN, tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) = log(Level.ERROR, tag, msg, t)

    fun getRecentLogs(count: Int = 50): List<LogEntry> = buffer.toList().takeLast(count)

    fun getRecentStrings(count: Int = 50): List<String> = getRecentLogs(count).map {
        "${df.format(Date(it.timestamp))} [${it.level.name[0]}] ${it.tag}: ${it.message}"
    }

    fun getAllLogs(): List<LogEntry> = buffer.toList()

    fun clearLogs() = buffer.clear()

    fun exportLogs(): File? = logFile

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class LogEntry(
        val level: Level,
        val tag: String,
        val message: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    /** 与 Timber 桥接：自动把 Timber 日志写入文件和环形缓冲区 */
    class FileTree(private val ctx: Context) : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            val tg = tag ?: "FarmBot"
            when (priority) {
                android.util.Log.VERBOSE, android.util.Log.DEBUG -> d(tg, message)
                android.util.Log.INFO -> i(tg, message)
                android.util.Log.WARN -> w(tg, message)
                android.util.Log.ERROR, android.util.Log.ASSERT -> e(tg, message, t)
            }
        }
    }
}
