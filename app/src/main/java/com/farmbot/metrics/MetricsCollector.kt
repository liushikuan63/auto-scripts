/**
 * 本地埋点收集器 — 识别成功率统计 + 阈值自动优化
 */

package com.farmbot.metrics

import android.content.Context
import com.farmbot.config.ConfigManager
import com.farmbot.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * 本地埋点收集器 — 识别成功率统计 + 阈值自动优化
 *
 * 设计原则：
 *  - 所有数据只保留在本地（`FarmBot/metrics/`），绝不上传
 *  - 轻量：内存维护计数器，周期性 flush 到磁盘
 *  - 自适应：基于近 N 次识别成功率自动调整 [FarmConfig.templateMatchThreshold]
 *
 * 使用方式（在 ActionExecutor / TemplateMatcher 等处埋点）：
 *   MetricsCollector.record("water_btn", found = true, confidence = 0.92, latencyMs = 120)
 *   MetricsCollector.record("water_btn", found = false, confidence = 0.41, latencyMs = 90)
 */
class MetricsCollector private constructor(private val context: Context) {

    companion object {
        private const val DIR_NAME = "FarmBot/metrics"
        private const val MAX_PER_KEY = 200          // 每个 key 最多保留 200 条样本
        private const val FLUSH_THRESHOLD = 50       // 累计 50 条 flush 一次
        private const val AUTO_TUNE_INTERVAL = 20    // 每 20 次记录触发一次阈值自适应
        private const val MIN_SAMPLES_FOR_TUNING = 30 // 至少 30 个样本才自适应
        private const val MIN_THRESHOLD = 0.55
        private const val MAX_THRESHOLD = 0.95

        @Volatile private var instance: MetricsCollector? = null
        fun getInstance(context: Context): MetricsCollector =
            instance ?: synchronized(this) {
                instance ?: MetricsCollector(context.applicationContext).also { instance = it }
            }
    }

    data class Sample(
        val key: String,
        val found: Boolean,
        val confidence: Double,
        val latencyMs: Long,
        val ts: Long = System.currentTimeMillis()
    )

    private val dir = File(context.getExternalFilesDir(null), DIR_NAME).apply { mkdirs() }
    private val todayFile: File get() =
        File(dir, "metrics_${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.csv")

    // 内存环形缓冲（按 key 分桶）
    private val buffer = mutableMapOf<String, ArrayDeque<Sample>>()
    private val pendingFlush = AtomicLong(0)

    private val _stats = MutableStateFlow<Map<String, Stats>>(emptyMap())
/**
 * 各识别项的统计流
 */
    val stats: StateFlow<Map<String, Stats>> = _stats.asStateFlow()

    @Synchronized
/**
 * 记录一次识别结果（是否命中、置信度、耗时）
 */
    fun record(key: String, found: Boolean, confidence: Double, latencyMs: Long) {
        val sample = Sample(key, found, confidence, latencyMs)
        val q = buffer.getOrPut(key) { ArrayDeque(MAX_PER_KEY) }
        if (q.size >= MAX_PER_KEY) q.removeFirst()
        q.addLast(sample)

        recomputeStats(key)
        if (pendingFlush.incrementAndGet() >= FLUSH_THRESHOLD) flush()
        if (q.size % AUTO_TUNE_INTERVAL == 0) autoTuneThreshold(key)
    }

    data class Stats(
        val key: String,
        val total: Int,
        val successCount: Int,
        val successRate: Double,
        val avgConfidence: Double,
        val avgLatencyMs: Double,
        val falseNegativeRate: Double   // 把高置信度的误判为未找到的比例
    )

    @Synchronized
/**
 * 取某一项的统计
 */
    fun getStats(key: String): Stats? = _stats.value[key]

    @Synchronized
/**
 * 取全部统计
 */
    fun getAllStats(): Map<String, Stats> = _stats.value

    private fun recomputeStats(key: String) {
        val q = buffer[key] ?: return
        if (q.isEmpty()) return
        val total = q.size
        val succ = q.count { it.found }
        val avgConf = q.map { it.confidence }.average()
        val avgLat = q.map { it.latencyMs.toDouble() }.average()
        // 假阴性：置信度 ≥ 当前阈值但 found=false
        val curThreshold = ConfigManager.getConfig().templateMatchThreshold
        val falseNeg = q.count { !it.found && it.confidence >= curThreshold }
        val s = Stats(
            key = key,
            total = total,
            successCount = succ,
            successRate = if (total > 0) succ.toDouble() / total else 0.0,
            avgConfidence = avgConf,
            avgLatencyMs = avgLat,
            falseNegativeRate = if (total > 0) falseNeg.toDouble() / total else 0.0
        )
        val m = _stats.value.toMutableMap()
        m[key] = s
        _stats.value = m
    }

    /**
     * 阈值自适应：
     *  - 若 falseNegativeRate > 0.15（漏检多）→ 降低阈值（最多降到 MIN_THRESHOLD）
     *  - 若 successRate < 0.4 且 avgConfidence 较高 → 降低阈值
     *  - 若 successRate > 0.9 且 avgConfidence 很高（>0.92）→ 提高阈值（减少误触）
     */
    @Synchronized
    private fun autoTuneThreshold(key: String) {
        val s = _stats.value[key] ?: return
        if (s.total < MIN_SAMPLES_FOR_TUNING) return
        val cur = ConfigManager.getConfig().templateMatchThreshold
        var newT = cur
        when {
            s.falseNegativeRate > 0.15 ->
                newT = (cur - 0.03).coerceAtLeast(MIN_THRESHOLD)
            s.successRate < 0.4 && s.avgConfidence > cur - 0.05 ->
                newT = (cur - 0.02).coerceAtLeast(MIN_THRESHOLD)
            s.successRate > 0.9 && s.avgConfidence > 0.92 ->
                newT = (cur + 0.02).coerceAtMost(MAX_THRESHOLD)
        }
        if (newT != cur) {
            val cfg = ConfigManager.getConfig()
            ConfigManager.save(cfg.copy(templateMatchThreshold = newT))
            Logger.i("MetricsCollector", "阈值自适应[$key]: %.3f → %.3f (succ=%.2f, fn=%.2f)"
                .format(cur, newT, s.successRate, s.falseNegativeRate))
        }
    }

    @Synchronized
/**
 * 把统计落盘
 */
    fun flush() {
        val all = buffer.values.flatten()
        if (all.isEmpty()) { pendingFlush.set(0); return }
        try {
            val header = !todayFile.exists()
            todayFile.appendText(buildString {
                if (header) append("ts,key,found,confidence,latency_ms\n")
                all.forEach { s ->
                    append(s.ts).append(',')
                    append(s.key).append(',')
                    append(if (s.found) 1 else 0).append(',')
                    append("%.4f".format(s.confidence)).append(',')
                    append(s.latencyMs).append('\n')
                }
            })
            pendingFlush.set(0)
            Timber.d("MetricsCollector flushed ${all.size} samples → ${todayFile.absolutePath}")
        } catch (t: Throwable) {
            Logger.e("MetricsCollector", "flush failed", t)
        }
    }

    /** 清空内存统计（不影响磁盘日志） */
    @Synchronized
/**
 * 清空内存统计（保留已落盘数据）
 */
    fun resetMemory() {
        buffer.clear()
        _stats.value = emptyMap()
        pendingFlush.set(0)
    }

    /** 导出最近 N 天的 metrics CSV 合并文件 */
    fun exportRecent(days: Int, target: File): Boolean = runCatching {
        val now = System.currentTimeMillis()
        val ms = days * 24L * 3600 * 1000
        val files = dir.listFiles { f -> f.name.endsWith(".csv") }
            ?.filter { f -> (now - f.lastModified()) <= ms }
            ?.sortedBy { it.name } ?: emptyList()
        target.bufferedWriter().use { w ->
            w.write("ts,key,found,confidence,latency_ms\n")
            files.forEach { f -> f.forEachLine { line -> if (!line.startsWith("ts,")) w.write(line + "\n") } }
        }
        true
    }.getOrDefault(false)

/**
 * 统计文件所在目录
 */
    fun getDir(): File = dir
}