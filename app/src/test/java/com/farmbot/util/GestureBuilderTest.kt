package com.farmbot.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureBuilderTest {

    private val D = 10_000L

    @Test fun `LINEAR 均匀分布`() {
        val times = GestureBuilder.generateTimingCurve(D, GestureBuilder.TimingType.LINEAR)
        assertEquals(10, times.size)
        assertEquals(0L, times.first())
        assertEquals(D, times.last())
        for (i in 0 until times.size - 1) {
            val step = times[i + 1] - times[i]
            assertTrue("step=$step at $i", step in 1100..1130)
        }
    }

    @Test fun `DECELERATE 先快后慢`() {
        val times = GestureBuilder.generateTimingCurve(D, GestureBuilder.TimingType.DECELERATE)
        assertEquals(0L, times.first())
        assertEquals(D, times.last())
        assertTrue("必须单调递增", (1 until times.size).all { times[it] > times[it - 1] })
        val steps = LongArray(times.size - 1) { times[it + 1] - times[it] }
        assertTrue(
            "前段步长应大于后段",
            steps.take(3).average() > steps.takeLast(3).average()
        )
    }

    @Test fun `ACCELERATE 先慢后快`() {
        val times = GestureBuilder.generateTimingCurve(D, GestureBuilder.TimingType.ACCELERATE)
        assertEquals(0L, times.first())
        assertEquals(D, times.last())
        val steps = LongArray(times.size - 1) { times[it + 1] - times[it] }
        assertTrue(
            "前段步长应小于后段",
            steps.take(3).average() < steps.takeLast(3).average()
        )
    }

    @Test fun `S_CURVE 平滑对称`() {
        val times = GestureBuilder.generateTimingCurve(D, GestureBuilder.TimingType.S_CURVE)
        assertEquals(0L, times.first())
        assertEquals(D, times.last())
        assertTrue("必须单调递增", (1 until times.size).all { times[it] > times[it - 1] })
        // smoothstep 关于中点对称: times[i] + times[9-i] == D (允许±1截断误差)
        for (i in times.indices) {
            val sum = times[i] + times[times.size - 1 - i]
            assertTrue("sum=$sum at $i", kotlin.math.abs(sum - D) <= 1L)
        }
        // 中段速度最快
        val steps = LongArray(times.size - 1) { times[it + 1] - times[it] }
        assertTrue(steps.first() < steps[4])
        assertTrue(steps.last() < steps[4])
    }

    @Test fun `所有类型都落在 0到duration 内`() {
        for (type in GestureBuilder.TimingType.values()) {
            val times = GestureBuilder.generateTimingCurve(D, type)
            assertTrue("$type min=${times.min()}", times.min() ?: -1 >= 0L)
            assertTrue("$type max=${times.max()}", times.max() ?: 0 <= D)
        }
    }
}
