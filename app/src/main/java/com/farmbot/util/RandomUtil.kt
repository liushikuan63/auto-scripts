/**
 * 随机时序与抖动工具
 */

package com.farmbot.util

import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 随机时序与抖动工具
 *
 * 提供泊松延迟、高斯延迟、点击坐标偏移、贝塞尔控制点偏移、偶发误触与随机空闲间隔，
 * 用于把自动化操作的时序打散成接近真人的分布。
 */
object RandomUtil {

    private val random = Random
    private val javaRandom = java.util.Random()

/**
 * 泊松分布等待时长（贴近真人操作间隔）
 */
    fun poissonDelay(minMs: Long, maxMs: Long): Long {
        val lambda = (minMs + maxMs) / 2.0
        val u = random.nextDouble()
        val delay = (-ln(1.0 - u) * lambda).toLong()
        return delay.coerceIn(minMs, maxMs)
    }

/**
 * 高斯分布等待时长
 */
    fun gaussianDelay(meanMs: Long, stdDevMs: Long = 200L): Long {
        val delay = (javaRandom.nextGaussian() * stdDevMs + meanMs).toLong()
        return delay.coerceIn(50L, meanMs * 3)
    }

/**
 * 点击坐标随机偏移量
 */
    fun clickOffset(maxOffsetPx: Int = 6): Int {
        return random.nextInt(-maxOffsetPx, maxOffsetPx + 1)
    }

/**
 * 点击坐标偏移区间（横纵各一个）
 */
    fun clickOffsetRange(maxOffsetPx: Int = 6): Pair<Int, Int> {
        return Pair(clickOffset(maxOffsetPx), clickOffset(maxOffsetPx))
    }

/**
 * 按概率插入一次空闲等待
 */
    fun maybeIdle(idleProbability: Double = 0.15, minMs: Long = 1500, maxMs: Long = 3000): Long? {
        return if (random.nextDouble() < idleProbability) {
            poissonDelay(minMs, maxMs)
        } else {
            null
        }
    }

/**
 * 闭区间内随机整数
 */
    fun randomInt(min: Int, max: Int): Int = random.nextInt(min, max + 1)

/**
 * 区间内随机浮点数
 */
    fun randomFloat(min: Float, max: Float): Float =
        random.nextFloat() * (max - min) + min

/**
 * 贝塞尔滑动的随机控制点
 */
    fun bezierControlOffset(rangePx: Int = 40): Pair<Int, Int> {
        return Pair(random.nextInt(-rangePx, rangePx + 1), random.nextInt(-rangePx, rangePx + 1))
    }

/**
 * 按概率判定是否模拟误触
 */
    fun shouldMistouch(mistouchProbability: Double = 0.02): Boolean =
        random.nextDouble() < mistouchProbability

/**
 * 从列表中随机取一个
 */
    fun <T> pickOne(list: List<T>): T = list[random.nextInt(list.size)]

/**
 * 按概率判定是否成立
 */
    fun chance(probability: Double): Boolean = random.nextDouble() < probability
}