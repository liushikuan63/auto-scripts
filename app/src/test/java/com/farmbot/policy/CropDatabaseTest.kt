package com.farmbot.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CropDatabaseTest {

    // ===== 可用作物 =====

    @Test
    fun `低等级只见低级作物`() {
        val lv1 = CropDatabase.availableCrops(1)
        assertEquals(1, lv1.size)
        assertEquals("小麦", lv1.first().name)
    }

    @Test
    fun `高等级解锁全部作物`() {
        assertEquals(CropDatabase.CROPS.size, CropDatabase.availableCrops(99).size)
    }

    // ===== 推荐策略 =====

    @Test
    fun `工作日智能模式推荐经验最高`() {
        val rec = CropDatabase.recommend(farmLevel = 30, strategy = CropDatabase.Strategy.AUTO, isWeekendDouble = false)
        assertEquals(rec, CropDatabase.availableCrops(30).maxByOrNull { it.expPerHour })
        assertTrue(rec!!.expPerHour > 0)
    }

    @Test
    fun `周末双倍智能模式推荐金币最高`() {
        val rec = CropDatabase.recommend(farmLevel = 30, strategy = CropDatabase.Strategy.AUTO, isWeekendDouble = true)
        assertEquals(rec, CropDatabase.availableCrops(30).maxByOrNull { it.coinPerHour })
    }

    @Test
    fun `经验优先策略忽略双倍状态`() {
        val rec = CropDatabase.recommend(farmLevel = 30, strategy = CropDatabase.Strategy.EXP_FIRST, isWeekendDouble = true)
        assertEquals(rec, CropDatabase.availableCrops(30).maxByOrNull { it.expPerHour })
    }

    @Test
    fun `金币优先策略推荐纯金币作物`() {
        val rec = CropDatabase.recommend(farmLevel = 69, strategy = CropDatabase.Strategy.COIN_FIRST)
        assertTrue(rec!!.isCoinFocus)  // 69级应推荐枇杷（金币效率之王）
        assertEquals("枇杷", rec.name)
    }

    @Test
    fun `生长时长过滤生效`() {
        // 只想种8小时以内的 → 排除32h长周期作物
        val rec = CropDatabase.recommend(
            farmLevel = 40,
            strategy = CropDatabase.Strategy.EXP_FIRST,
            maxGrowHours = 8.0
        )
        assertTrue(rec!!.growHours <= 8.0)
    }

    @Test
    fun `等级过低无匹配时返回null或最低级`() {
        val none = CropDatabase.recommend(farmLevel = 0)
        assertNull(none)
    }

    // ===== 数据完整性 =====

    @Test
    fun `英雄变异作物可查询`() {
        val heroes = CropDatabase.heroCrops(80)
        assertTrue(heroes.any { it.heroName == "马可菠萝" })   // 菠萝 Lv55
        assertTrue(heroes.none { it.isCoinFocus })              // 英雄作物不是经验黑洞型
    }

    // ===== S44 新增作物 =====

    @Test
    fun `S44新作物已入库`() {
        val lv99 = CropDatabase.availableCrops(99)
        assertTrue(lv99.any { it.name == "牛油果" && it.isCoinFocus && it.coinPerHour == 4551 })
        assertTrue(lv99.any { it.name == "山楂" && it.isCoinFocus && it.coinPerHour == 3882 })
        assertTrue(lv99.any { it.name == "灯笼果" && it.heroName == "大乔灯" })
        assertTrue(lv99.any { it.name == "莲花" && it.heroName == "哪吒藕" })
        assertTrue(lv99.none { it.unlockLevel > 79 })   // 龙胆花(Lv80)无数据不收录
    }

    @Test
    fun `金币优先99级推荐牛油果`() {
        val rec = CropDatabase.recommend(farmLevel = 99, strategy = CropDatabase.Strategy.COIN_FIRST)
        assertEquals("牛油果", rec?.name)
    }

    @Test
    fun `低等级看不到S44作物`() {
        assertTrue(CropDatabase.availableCrops(69).none { it.unlockLevel >= 70 })
    }

    @Test
    fun `按名称查找`() {
        assertEquals("香蕉", CropDatabase.find("香蕉")?.name)
        assertNull(CropDatabase.find("不存在的作物"))
    }

    // ===== v1.4.37 卡双倍选种 =====

    @Test
    fun `卡双倍选生长时长最接近目标`() {
        // 距周五18:00约8小时 → 应选生长时长8h左右的作物（误差≤1h）
        val pick8 = CropDatabase.closestToGrowHours(farmLevel = 99, hours = 8.0)
        assertTrue(pick8 != null && kotlin.math.abs(pick8.growHours - 8.0) <= 1.0)
        // 距双倍约30小时 → 应选32h长周期作物（误差≤2h）
        val pick30 = CropDatabase.closestToGrowHours(farmLevel = 99, hours = 30.0)
        assertTrue(pick30 != null && kotlin.math.abs(pick30.growHours - 30.0) <= 2.0)
    }

    @Test
    fun `卡双倍受等级约束`() {
        // Lv1 只有小麦(0.01h)，任何目标时长都只能返回小麦
        val pick = CropDatabase.closestToGrowHours(farmLevel = 1, hours = 16.0)
        assertEquals("小麦", pick?.name)
        // Lv0 无可用作物 → null
        assertNull(CropDatabase.closestToGrowHours(farmLevel = 0, hours = 16.0))
    }

    @Test
    fun `卡双倍拒绝无效目标时长`() {
        assertNull(CropDatabase.closestToGrowHours(farmLevel = 99, hours = 0.0))
        assertNull(CropDatabase.closestToGrowHours(farmLevel = 99, hours = -1.0))
        assertNull(CropDatabase.closestToGrowHours(farmLevel = 99, hours = Double.NaN))
        assertNull(CropDatabase.closestToGrowHours(farmLevel = 99, hours = Double.POSITIVE_INFINITY))
    }
}
