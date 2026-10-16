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
    private val taskScheduler get() = ctx.taskScheduler

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

    /**
     * v1.4.14: 用 OCR 确认当前确实处于农场页面。
     * 王者农场为 SurfaceView 自绘、无障碍节点树为空，GameSceneDetector 的
     * classifyFromText 依赖节点树文本，对农场页面不可靠（大厅 Activity 常被判为
     * GAME_HALL/OTHER_IN_GAME）。故改用 OCR 识别农场标志性文字作为"是否已进农场"的依据。
     * 注意：不得用"农场/王者农场/浇水/成熟/作物"等宽泛词——大厅入口横幅即含"王者农场
     * 周末双倍"，且大厅可能混排含"浇水/成熟/作物/土地"的宣传文案，会误判进入农场
     * （v1.4.15 曾因加入宽泛词导致"大厅误判已进入农场→乱点灌溉/收获并假成功"）。
     * v1.4.21: 新手引导态农场页无"一键浇水/可开垦"等强词，仅有右下角"种植"按钮、
     * 右缘"仓库"入口等农场独有元素（大厅没有这些）。补入"种植/仓库"作为引导态标志。
     */
    /**
     * v1.4.28: 农场可能短暂出现在加载帧(如10%/30%)或 OCR 抖动，导致"未确认在农场"误判并阻断浇水/收获。
     * 增加重试：单次未确认则短暂等待后重试（最多3次），容忍瞬态加载/抖动；确认后提前返回。
     */
}
