package com.farmbot.engine.states

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * engine 包的第一条 JVM 单测：钉住每日操作上限的边界语义。
 *
 * 该判定原先内联在 scanCrops 与 waterCropsInternal 两处，抽取为 hasReachedDailyLimit 后
 * 成为可离线测试的纯逻辑；真实点击链路（无障碍手势 + OCR 定位）仍无法在 JVM 侧覆盖，
 * 需要 Robolectric 之外的真机或仪器化测试。
 */
class DailyLimitTest {

    @Test
    fun 未达上限时不停止() {
        assertFalse("0/500 应继续", hasReachedDailyLimit(used = 0, max = 500))
        assertFalse("499/500 应继续", hasReachedDailyLimit(used = 499, max = 500))
    }

    @Test
    fun 恰好达到上限时停止() {
        assertTrue("500/500 必须停止", hasReachedDailyLimit(used = 500, max = 500))
    }

    @Test
    fun 超过上限时停止() {
        assertTrue("501/500 必须停止", hasReachedDailyLimit(used = 501, max = 500))
    }

    @Test
    fun 边界随配置变化且不受上限数值大小影响() {
        assertFalse(hasReachedDailyLimit(used = 29, max = 30))
        assertTrue(hasReachedDailyLimit(used = 30, max = 30))
        assertFalse(hasReachedDailyLimit(used = 0, max = 1))
        assertTrue(hasReachedDailyLimit(used = 1, max = 1))
    }

    @Test
    fun 上限为零时立即停止() {
        assertTrue("上限 0 表示禁止操作", hasReachedDailyLimit(used = 0, max = 0))
    }
}
