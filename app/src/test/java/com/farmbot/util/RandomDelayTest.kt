package com.farmbot.util

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class RandomDelayTest {

    @Test fun `uniform 返回区间内值`() {
        repeat(1000) {
            val v = RandomDelay.uniform(100, 200)
            assertTrue("v=$v out of range", v in 100L..200L)
        }
    }

    @Test fun `gaussian 保持在 min-max 内`() {
        repeat(1000) {
            val v = RandomDelay.gaussian(mean = 500.0, sigma = 120.0, min = 200.0, max = 900.0)
            assertTrue("v=$v out of range", v in 200.0..900.0)
        }
    }

    @Test fun `poisson lambda`() {
        val samples = List(2000) { RandomDelay.poissonDelay(500L) }
        val avg = samples.average()
        // 泊松分布均值≈lambda（poissonDelay 内部 coerceAtLeast(50)，对均值影响可忽略）
        assertTrue("avg=$avg", avg in 400.0..600.0)
    }

    @Test fun `human delay 波动`() {
        val base = 1000L
        repeat(100) {
            val d = RandomDelay.durationJitter(base, jitterPercent = 0.2f)
            assertTrue("d=$d out of range", d in 800L..1200L)
        }
    }

    @Test fun `random offset 对称`() {
        var neg = 0; var pos = 0
        repeat(2000) {
            val (x, _) = RandomDelay.clickOffset(10)
            assertTrue("x=$x out of range", x in -10..10)
            if (x < 0) neg++ else if (x > 0) pos++
        }
        assertTrue("neg=$neg pos=$pos", neg > 0 && pos > 0)
    }
}
