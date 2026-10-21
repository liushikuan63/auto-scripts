package com.farmbot.engine.states

import com.farmbot.engine.FarmState
import com.farmbot.engine.runtime.FarmStateContext
import com.farmbot.util.RandomDelay
import kotlinx.coroutines.delay

internal class RecoveryHandler(private val ctx: FarmStateContext) {

    /** 与原 FarmStateMachine 保持同一日志 TAG，避免日志筛选行为变化 */
    private val TAG = "FarmStateMachine"

    // ---- 上下文转发：被搬迁的函数体保持逐字节不变 ----
    private val rt get() = ctx.rt
    private val ocrClick get() = ctx.ocrClick
    private val movement get() = ctx.movement
    private val oneKey get() = ctx.oneKey

    private fun transitionToInternal(next: FarmState) = ctx.gotoState(next)

    private suspend fun handleRecover(state: FarmState) = ctx.handleRecover(state)

    private suspend fun <T> withErrorLog(op: String, block: suspend () -> T?): T? = ctx.withErrorLog(op, block)

    internal suspend fun reconnectA11y() {
        rt.log("发起无障碍服务重连（客户端侧提示）")
        delay(1500)
        // 让 FarmAccessibilityService 单例自己重连；此处仅模拟"等待"
        try {
            val svcClass = Class.forName("com.farmbot.accessibility.FarmAccessibilityService")
            val instanceMethod = svcClass.getMethod("getInstanceOrNull")
            val inst = instanceMethod.invoke(null)
            if (inst != null) {
                val rec = svcClass.getMethod("reconnect")
                rec.invoke(inst)
                rt.log("重连请求已送达 FarmAccessibilityService")
            }
        } catch (t: Throwable) { /* 忽略反射失败，继续导航 */ }
        delay(1000)
        transitionToInternal(FarmState.NavigateToFarm)
    }


    internal suspend fun recoverGame() {
        rt.log("执行游戏恢复流程: HOME→等待→重新导航")
        // v1.4.4: 真实执行 GLOBAL_ACTION_HOME 回桌面（此前仅打日志假装执行）
        val svc = com.farmbot.accessibility.FarmAccessibilityService.getInstance()
        if (svc != null && rt.actionExecutor != null) {
            runCatching {
                svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            }.onSuccess { rt.log("✓已发送HOME键") }
                .onFailure { rt.log("[模拟] HOME动作失败: ${it.message}") }
        } else {
            rt.log("[模拟] 无障碍不可用，跳过HOME动作")
        }
        delay(RandomDelay.uniform(1500, 3000))
        transitionToInternal(FarmState.NavigateToFarm)
    }

    // ============ 业务状态 ============

}
