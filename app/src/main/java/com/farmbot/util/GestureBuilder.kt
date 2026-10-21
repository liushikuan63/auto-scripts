/**
 * 手势构建工具 — 融合 HonorFarmBot 版本
 */

package com.farmbot.util

import android.graphics.Path
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 手势构建工具 — 融合 HonorFarmBot 版本
 *
 * 功能：
 * 1. 二次/三次贝塞尔曲线滑动路径
 * 2. 随机微抖动（模拟手指不稳定性）
 * 3. 自然加速/减速曲线（用于手势时长插值）
 * 4. 圆弧路径（用于特殊操作：划动转盘等）
 */
object GestureBuilder {

    /**
     * 构建二次贝塞尔曲线路径（带曲率和法向偏移）
     */
    fun buildBezierPath(
        fromX: Int, fromY: Int,
        toX: Int, toY: Int,
        curvature: Float = 0.3f
    ): Path {
        val path = Path()
        path.moveTo(fromX.toFloat(), fromY.toFloat())

        val midX = (fromX + toX) / 2f
        val midY = (fromY + toY) / 2f

        val dx = toX - fromX
        val dy = toY - fromY
        val length = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()

        val direction = if (Random.nextBoolean()) 1 else -1
        val offset = length * curvature * direction

        val nx = -dy / (length.coerceAtLeast(1f)) * offset
        val ny = dx / (length.coerceAtLeast(1f)) * offset

        val ctrlX = midX + nx
        val ctrlY = midY + ny
        path.quadTo(ctrlX, ctrlY, toX.toFloat(), toY.toFloat())
        return path
    }

    /**
     * 构建三次贝塞尔曲线路径（更自然的 S 形）
     */
    fun buildCubicBezierPath(
        fromX: Int, fromY: Int,
        toX: Int, toY: Int
    ): Path {
        val path = Path()
        path.moveTo(fromX.toFloat(), fromY.toFloat())
        val dx = (toX - fromX).toFloat()
        val dy = (toY - fromY).toFloat()
        val ctrl1X = fromX + dx * 0.3f + Random.nextFloat() * 50 - 25
        val ctrl1Y = fromY + dy * 0.1f + Random.nextFloat() * 30 - 15
        val ctrl2X = fromX + dx * 0.7f + Random.nextFloat() * 50 - 25
        val ctrl2Y = fromY + dy * 0.9f + Random.nextFloat() * 30 - 15
        path.cubicTo(ctrl1X, ctrl1Y, ctrl2X, ctrl2Y, toX.toFloat(), toY.toFloat())
        return path
    }

    /**
     * 带微抖动的点击路径（模拟手指颤抖）
     */
    fun buildClickPath(x: Int, y: Int): Path {
        val path = Path()
        val jitter = 1.5f
        path.moveTo(x.toFloat(), y.toFloat())
        for (i in 1..3) {
            val jx = (Random.nextFloat() - 0.5f) * jitter
            val jy = (Random.nextFloat() - 0.5f) * jitter
            path.lineTo(x + jx, y + jy)
        }
        return path
    }

    /**
     * 构建自然滑动路径（多段折线 + 抖动）
     */
    fun buildNaturalSwipePath(
        fromX: Int, fromY: Int,
        toX: Int, toY: Int,
        segments: Int = 5
    ): Path {
        val path = Path()
        path.moveTo(fromX.toFloat(), fromY.toFloat())
        val dx = (toX - fromX).toFloat() / segments
        val dy = (toY - fromY).toFloat() / segments
        for (i in 1..segments) {
            val px = fromX + dx * i + (Random.nextFloat() - 0.5f) * 20
            val py = fromY + dy * i + (Random.nextFloat() - 0.5f) * 20
            path.lineTo(px, py)
        }
        return path
    }

    enum class TimingType {
        LINEAR, ACCELERATE, DECELERATE, S_CURVE
    }

    /**
     * 生成自然的时间插值（先快后慢 / 先慢后快 / S 形）
     */
    fun generateTimingCurve(durationMs: Long, type: TimingType = TimingType.DECELERATE): LongArray {
        val steps = 10
        val times = LongArray(steps)
        for (i in 0 until steps) {
            val t = i.toFloat() / (steps - 1)
            val progress = when (type) {
                TimingType.LINEAR -> t
                TimingType.ACCELERATE -> t * t
                TimingType.DECELERATE -> 1f - (1f - t) * (1f - t)
                TimingType.S_CURVE -> t * t * (3f - 2f * t)
            }
            times[i] = (progress * durationMs).toLong()
        }
        return times
    }

    /**
     * 圆弧路径（用于特殊手势）
     */
    fun buildArcPath(
        centerX: Int, centerY: Int,
        radius: Float,
        startAngle: Float,
        endAngle: Float,
        steps: Int = 20
    ): Path {
        val path = Path()
        val startRad = Math.toRadians(startAngle.toDouble())
        val endRad = Math.toRadians(endAngle.toDouble())
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val angle = startRad + (endRad - startRad) * t
            val x = centerX + (radius * cos(angle)).toFloat()
            val y = centerY + (radius * sin(angle)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        return path
    }
}