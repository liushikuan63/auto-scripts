package com.farmbot.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FarmConfigDefaultsTest {

    @Test fun `defaults are sensible`() {
        val c = FarmConfig.DEFAULT
        assertEquals(120, c.waterIntervalMin)
        assertEquals(true, c.autoAdjustByPlant)
        assertEquals(true, c.dailyBattle)
        assertEquals(180, c.surrenderAfterSec)
        assertEquals(FarmConfig.SellMode.NONE, c.sellMode)
        assertEquals(FarmConfig.UpgradePriority.LAND_FIRST, c.upgradePriority)
        assertEquals(true, c.enableSteal)
        assertEquals(true, c.enableBless)
        assertEquals(0.85, c.templateMatchThreshold, 0.001)
        assertEquals(true, c.enableLog)
    }

    @Test fun `copy creates distinct`() {
        val a = FarmConfig.DEFAULT
        val b = a.copy(waterIntervalMin = 30, sellMode = FarmConfig.SellMode.SELL_ALL)
        assertNotEquals(a, b)
        assertEquals(30, b.waterIntervalMin)
        assertEquals(FarmConfig.SellMode.SELL_ALL, b.sellMode)
    }
}
