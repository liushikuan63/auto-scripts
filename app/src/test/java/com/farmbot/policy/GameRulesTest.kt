package com.farmbot.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class GameRulesTest {

    private fun cal(dayOfWeek: Int, hour: Int, minute: Int = 0): Calendar =
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, dayOfWeek)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

    // ===== 周末双倍 =====

    @Test
    fun `周五17点59分未进入双倍`() {
        assertFalse(GameRules.isWeekendDouble(cal(Calendar.FRIDAY, 17, 59)))
    }

    @Test
    fun `周五18点整进入双倍`() {
        assertTrue(GameRules.isWeekendDouble(cal(Calendar.FRIDAY, 18, 0)))
    }

    @Test
    fun `周六全天双倍`() {
        assertTrue(GameRules.isWeekendDouble(cal(Calendar.SATURDAY, 0)))
        assertTrue(GameRules.isWeekendDouble(cal(Calendar.SATURDAY, 12)))
        assertTrue(GameRules.isWeekendDouble(cal(Calendar.SATURDAY, 23, 59)))
    }

    @Test
    fun `周日全天双倍`() {
        assertTrue(GameRules.isWeekendDouble(cal(Calendar.SUNDAY, 0)))
        assertTrue(GameRules.isWeekendDouble(cal(Calendar.SUNDAY, 23)))
    }

    @Test
    fun `周一至周四非双倍`() {
        assertFalse(GameRules.isWeekendDouble(cal(Calendar.MONDAY, 12)))
        assertFalse(GameRules.isWeekendDouble(cal(Calendar.TUESDAY, 20)))
        assertFalse(GameRules.isWeekendDouble(cal(Calendar.WEDNESDAY, 8)))
        assertFalse(GameRules.isWeekendDouble(cal(Calendar.THURSDAY, 23, 59)))
    }

    @Test
    fun `距下一次双倍按分钟精确计算`() {
        assertEquals(10L, GameRules.minutesUntilNextDouble(cal(Calendar.FRIDAY, 17, 50)))
        assertEquals(60L, GameRules.minutesUntilNextDouble(cal(Calendar.FRIDAY, 17, 0)))
        assertEquals(-1L, GameRules.minutesUntilNextDouble(cal(Calendar.SATURDAY, 12, 0)))
    }

    // ===== 偷菜保护时间 =====

    @Test
    fun `凌晨0点到7点59分为保护时间`() {
        assertTrue(GameRules.isStealProtectionTime(cal(Calendar.SATURDAY, 0)))
        assertTrue(GameRules.isStealProtectionTime(cal(Calendar.SATURDAY, 7, 59)))
    }

    @Test
    fun `早上8点起可正常偷菜`() {
        assertFalse(GameRules.isStealProtectionTime(cal(Calendar.SATURDAY, 8, 0)))
        assertFalse(GameRules.isStealProtectionTime(cal(Calendar.SATURDAY, 23)))
    }

    // ===== 卡双倍浇水窗口 =====

    @Test
    fun `周五临近18点且在浇水周期内应暂缓`() {
        // 16:30，距18点90分钟，浇水周期120分钟 → 浇水会导致提前成熟，暂缓
        assertTrue(GameRules.shouldHoldWateringForDouble(cal(Calendar.FRIDAY, 16, 30), 120))
    }

    @Test
    fun `周五早间距双倍远不暂缓`() {
        // 10:00 距18点480分钟 > 周期120分钟 → 正常浇
        assertFalse(GameRules.shouldHoldWateringForDouble(cal(Calendar.FRIDAY, 10, 0), 120))
    }

    @Test
    fun `非周五从不暂缓`() {
        assertFalse(GameRules.shouldHoldWateringForDouble(cal(Calendar.THURSDAY, 17, 0), 120))
        assertFalse(GameRules.shouldHoldWateringForDouble(cal(Calendar.SATURDAY, 17, 0), 120))
    }

    @Test
    fun `已过18点不暂缓`() {
        assertFalse(GameRules.shouldHoldWateringForDouble(cal(Calendar.FRIDAY, 18, 30), 120))
    }

    // ===== 等级解锁表 =====

    @Test
    fun `土地数量随等级递增`() {
        // S45 新土地模型: 普通田 2+(min(lv,40)+1)/2，二级田 44 级起开放
        assertEquals(3, GameRules.landCountForLevel(1))
        assertEquals(3, GameRules.landCountForLevel(3))
        assertEquals(4, GameRules.landCountForLevel(4))
        assertEquals(5, GameRules.landCountForLevel(5))
        assertEquals(7, GameRules.landCountForLevel(10))
        assertEquals(8, GameRules.landCountForLevel(12))
        assertEquals(22, GameRules.landCountForLevel(40))   // 普通田满22块
        assertEquals(23, GameRules.landCountForLevel(44))   // 二级田开放+1
        assertEquals(35, GameRules.landCountForLevel(69))
        assertEquals(39, GameRules.landCountForLevel(79))   // 二级田17块
    }

    @Test
    fun `普通田与二级田拆分正确`() {
        assertEquals(3, GameRules.normalLandCountForLevel(1))
        assertEquals(22, GameRules.normalLandCountForLevel(40))
        assertEquals(22, GameRules.normalLandCountForLevel(99))
        assertEquals(0, GameRules.extraLandCountForLevel(43))
        assertEquals(1, GameRules.extraLandCountForLevel(44))
        assertEquals(17, GameRules.extraLandCountForLevel(79))
    }

    @Test
    fun `下一解锁内容正确`() {
        assertEquals(4, GameRules.nextUnlock(3)?.level)
        assertEquals(40, GameRules.nextUnlock(30)?.level)  // S45: 30级之后是40级培育台
        assertEquals(44, GameRules.nextUnlock(40)?.level)
        assertNull(GameRules.nextUnlock(100))
        val next = GameRules.nextUnlock(1)
        assertEquals(4, next?.level) // Lv1 → 下一个是Lv4一键浇水
    }

    // ===== S45 昼夜系统 =====

    @Test
    fun `昼夜判断正确`() {
        assertTrue(GameRules.isNightTime(cal(Calendar.MONDAY, 18)))
        assertTrue(GameRules.isNightTime(cal(Calendar.MONDAY, 23)))
        assertTrue(GameRules.isNightTime(cal(Calendar.TUESDAY, 5, 59)))
        assertFalse(GameRules.isNightTime(cal(Calendar.MONDAY, 6)))
        assertFalse(GameRules.isNightTime(cal(Calendar.MONDAY, 12)))
        assertFalse(GameRules.isNightTime(cal(Calendar.MONDAY, 17, 59)))
    }

    @Test
    fun `昼夜名称包含状态标识`() {
        assertTrue(GameRules.dayPhaseName(cal(Calendar.MONDAY, 12)).contains("白天"))
        assertTrue(GameRules.dayPhaseName(cal(Calendar.MONDAY, 20)).contains("夜晚"))
    }

    // ===== S45 流光沙漏 =====

    @Test
    fun `双倍期沙漏效果减半`() {
        assertTrue(GameRules.isHourglassHalfEffect(cal(Calendar.SATURDAY, 12)))
        assertFalse(GameRules.isHourglassHalfEffect(cal(Calendar.WEDNESDAY, 12)))
        assertEquals(15, GameRules.hourglassEffectiveMinutes(cal(Calendar.SATURDAY, 12)))
        assertEquals(30, GameRules.hourglassEffectiveMinutes(cal(Calendar.WEDNESDAY, 12)))
    }

    // ===== S45 浇水临界 =====

    @Test
    fun `浇水临界阈值正确`() {
        assertEquals(100, GameRules.wateringInvalidThresholdMinutes(3000))  // 总时长1/30
        assertTrue(GameRules.isWateringInvalid(totalGrowMinutes = 3000, remainingMinutes = 99))
        assertFalse(GameRules.isWateringInvalid(totalGrowMinutes = 3000, remainingMinutes = 100))
    }

    // ===== S45 关键常量 =====

    @Test
    fun `S45关键常量正确`() {
        assertEquals(100, GameRules.FARM_MAX_LEVEL)
        assertEquals(40, GameRules.BREEDING_UNLOCK_LEVEL)
        assertEquals(4, GameRules.MAX_MUTATION_STACK)
        assertEquals(840, GameRules.MAX_COMBINED_MULTIPLIER)
        assertEquals(40, GameRules.STALL_MAX_LEVEL)
        assertEquals(3, GameRules.MAX_BLESS_PER_CROP)
        assertEquals(4, GameRules.MAX_STEAL_PER_CROP_DAILY)
        assertEquals(0.4f, GameRules.STEAL_QUOTA_RATIO)
        assertEquals(30, GameRules.HOURGLASS_REDUCE_MINUTES)
    }
}
