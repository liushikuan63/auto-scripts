package com.farmbot.util

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 拟人化随机延迟生成器 — 融合 HonorFarmBot 版本
 *
 * 分布：泊松分布 / 高斯 / 均匀 / 节奏模式
 */
object RandomDelay {

    // ==================== 基础随机 ====================

    fun randomInt(min: Int, max: Int): Int =
        if (max >= min) Random.nextInt(min, max + 1) else min

/**
 * 区间内随机长整数
 */
    fun randomLong(min: Long, max: Long): Long =
        if (max >= min) Random.nextLong(min, max + 1) else min

/**
 * 区间内随机浮点数
 */
    fun randomFloat(min: Float, max: Float): Float =
        Random.nextFloat() * (max - min) + min

/**
 * 按概率判定是否成立
 */
    fun chance(probability: Float): Boolean = Random.nextFloat() < probability

/**
 * 从列表中随机取一个
 */
    fun <T> pickOne(list: List<T>): T = list[Random.nextInt(list.size)]

    // ==================== 分布延迟 ====================

    /**
     * 均匀分布（Int 版本，返回 Long，可直接用于 delay）
     */
    fun uniform(minMs: Int, maxMs: Int): Long =
        Random.nextLong(minMs.toLong(), (maxMs + 1).toLong())

    /**
     * 均匀分布（Long 版本，返回 Long，直接用于 delay()）
     */
    fun uniform(minMs: Long, maxMs: Long): Long =
        Random.nextLong(minMs, (maxMs + 1).coerceAtLeast(minMs))

    /**
     * 截断高斯分布（Double 版本，单位 ms）
     *
     * @param mean  平均值
     * @param sigma 标准差
     * @param min   截断下限
     * @param max   截断上限
     */
    fun gaussian(mean: Double, sigma: Double, min: Double, max: Double): Double {
        val u1 = Random.nextDouble().coerceAtLeast(1e-9)
        val u2 = Random.nextDouble()
        val z = sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)
        val value = mean + z * sigma
        return value.coerceIn(min, max)
    }

    /**
     * 截断高斯分布（返回 Long，直接用于 delay()）
     */
    fun gaussian(mean: Long, sigma: Long, min: Long, max: Long): Long {
        val u1 = Random.nextDouble().coerceAtLeast(1e-9)
        val u2 = Random.nextDouble()
        val z = sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)
        val value = mean.toDouble() + z * sigma.toDouble()
        return value.toLong().coerceIn(min, max)
    }

    /**
     * 泊松分布延迟：大多数值在 mean 附近，指数尾（模拟事件间隔）
     */
    fun poissonDelay(meanMs: Long): Long {
        val L = exp(-meanMs.toDouble())
        var k = 0
        var p = 1.0
        do {
            k++
            p *= Random.nextDouble()
        } while (p > L)
        return (k - 1).toLong().coerceAtLeast(50)
    }

    /**
     * 截断高斯分布延迟（Box-Muller 变换）
     */
    fun gaussianDelay(meanMs: Long, stdDevMs: Long): Long {
        val u1 = Random.nextDouble().coerceAtLeast(1e-9)
        val u2 = Random.nextDouble()
        val z = sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)
        val value = meanMs + z * stdDevMs
        return value.toLong().coerceAtLeast(0)
    }

    // ==================== 场景化延迟 ====================

    fun clickInterval(): Long =
        if (Random.nextFloat() < 0.5f) randomLong(100, 400)
        else if (Random.nextFloat() < 0.6f) randomLong(400, 1200)
        else randomLong(1200, 3000)

/**
 * 页面加载等待时长（泊松分布并限幅）
 */
    fun pageLoadWait(): Long = poissonDelay(1500).coerceIn(800, 5000)

/**
 * 犹豫时长：点击前的短暂停顿
 */
    fun hesitationDelay(): Long =
        if (Random.nextFloat() < 0.15f) randomLong(500, 2000) else 0

/**
 * 按文本长度估算的阅读时长
 */
    fun readingDelay(textLength: Int): Long {
        val cps = randomFloat(5f, 8f)
        val base = (textLength / cps * 1000).toLong()
        return base.coerceIn(300, 5000) + hesitationDelay()
    }

/**
 * 拟人化等待：按基础时长加抖动
 */
    fun humanizedDelay(
        minMs: Long = 200,
        maxMs: Long = 2000,
        meanMs: Long = 600
    ): Long {
        val stdDev = (maxMs - minMs) / 6.0
        var value: Double
        do {
            val u1 = Random.nextDouble().coerceAtLeast(1e-9)
            val u2 = Random.nextDouble()
            val z = sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)
            value = meanMs + z * stdDev
        } while (value < minMs || value > maxMs)
        return value.toLong()
    }

    /** 批量操作间隔：越靠后越长（疲劳效应） */
    fun batchInterval(position: Int, totalCount: Int): Long {
        val fatigue = 1.0 + (position.toDouble() / (totalCount.coerceAtLeast(1))) * 0.5
        val base = humanizedDelay(150, 800, 350)
        return (base * fatigue).toLong()
    }

    /** 时长抖动（用于手势时长、滑动时长等） */
    fun durationJitter(baseDuration: Long, jitterPercent: Float = 0.2f): Long {
        val jitter = baseDuration * jitterPercent * (Random.nextFloat() - 0.5f) * 2
        return (baseDuration + jitter).toLong().coerceAtLeast(50)
    }

    // ==================== 睡眠 ====================

    fun sleep(ms: Long) {
        if (ms <= 0) return
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

/**
 * 带抖动的阻塞等待
 */
    fun sleepWithJitter(baseMs: Long, jitterPercent: Float = 0.3f) =
        sleep(durationJitter(baseMs, jitterPercent))

/**
 * 按概率插入一次空闲等待
 */
    fun maybeIdle(idleProbability: Float, minMs: Long, maxMs: Long): Long =
        if (chance(idleProbability)) randomLong(minMs, maxMs) else 0L

    // ==================== 节奏 & 休息 ====================

    fun rhythmicDelay(): Long {
        val pattern = listOf(200L, 300L, 800L, 1500L, 400L, 600L, 2000L, 350L)
        val idx = Random.nextInt(pattern.size)
        return pattern[idx] + randomLong(-100, 200)
    }

/**
 * 按累计操作次数判断是否该休息
 */
    fun shouldTakeBreak(operationCount: Int): Boolean {
        val step = randomInt(50, 80)
        return operationCount > 0 && operationCount % step == 0 && chance(0.7f)
    }

/**
 * 休息时长
 */
    fun breakDuration(): Long = randomLong(3000, 15000)

    // ==================== 坐标 & 偏移 ====================

    fun clickOffset(maxPx: Int = 8): Pair<Int, Int> =
        Pair(randomInt(-maxPx, maxPx), randomInt(-maxPx, maxPx))

/**
 * 贝塞尔滑动的两个随机控制点
 */
    fun bezierControlOffset(rangePx: Int): Pair<Pair<Int, Int>, Pair<Int, Int>> =
        Pair(
            Pair(randomInt(-rangePx, rangePx), randomInt(-rangePx / 2, rangePx / 2)),
            Pair(randomInt(-rangePx, rangePx), randomInt(-rangePx / 2, rangePx / 2))
        )

/**
 * 按概率判定是否模拟误触
 */
    fun shouldMistouch(probability: Float = 0.05f): Boolean = chance(probability)

/**
 * 误触发生的随机坐标
 */
    fun falseTriggerLocation(
        screenW: Int, screenH: Int,
        baseX: Int, baseY: Int,
        minOffsetPx: Int = 10, maxOffsetPx: Int = 40
    ): Pair<Int, Int> {
        // 极坐标偏移
        val r = randomInt(minOffsetPx, maxOffsetPx)
        val theta = Random.nextFloat() * 2f * Math.PI.toFloat()
        val x = baseX + (r * cos(theta)).toInt()
        val y = baseY + (r * sin(theta)).toInt()
        return Pair(x.coerceIn(0, screenW - 1), y.coerceIn(0, screenH - 1))
    }
}
