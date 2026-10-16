package com.farmbot.engine.states

import com.farmbot.engine.FarmState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 处理器级单测：用假上下文驱动 RecoveryHandler，不需要真机、不需要 Robolectric。
 *
 * 之所以能成立：FarmRuntime 的 ocr / matcher 已改懒初始化，FarmStateContext 也不再要求
 * TaskScheduler（只有 BattleUpgradeHandler 需要）。本测试覆盖恢复流程的收尾跳转与
 * 「无障碍不可用时不发 HOME 动作」这条分支。
 */
class RecoveryHandlerTest {

    @Test
    fun 无障碍不可用时恢复流程收尾回到导航且不触碰截图通道() = runTest {
        val fake = FakeFarmStateContext()
        var shots = 0
        fake.rt.screenshotProvider = { shots++; null }

        RecoveryHandler(fake).recoverGame()

        assertEquals("收尾必须回到导航状态", listOf(FarmState.NavigateToFarm), fake.transitions)
        assertTrue(
            "应记录模拟分支日志，实际日志=" + fake.logs(),
            fake.logs().any { it.contains("[模拟] 无障碍不可用，跳过HOME动作") }
        )
        assertEquals("恢复流程不得读取截图通道", 0, shots)
    }

    @Test
    fun 重连无障碍后收尾回到导航() = runTest {
        val fake = FakeFarmStateContext()

        RecoveryHandler(fake).reconnectA11y()

        assertEquals(listOf(FarmState.NavigateToFarm), fake.transitions)
        assertTrue(
            "应记录重连日志，实际日志=" + fake.logs(),
            fake.logs().any { it.contains("发起无障碍服务重连") }
        )
    }

    @Test
    fun 恢复流程不会停在空闲态() = runTest {
        val fake = FakeFarmStateContext()

        RecoveryHandler(fake).recoverGame()
        RecoveryHandler(fake).reconnectA11y()

        assertTrue("恢复后停在 Idle 会导致引擎实际停摆", fake.transitions.none { it == FarmState.Idle })
        assertEquals(2, fake.transitions.size)
    }
}
