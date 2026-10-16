package com.farmbot.engine.states

import com.farmbot.engine.FarmState
import com.farmbot.engine.TaskScheduler
import com.farmbot.engine.runtime.FarmStateContext
import com.farmbot.config.ConfigManager
import com.farmbot.policy.GameRules
import com.farmbot.util.RandomDelay
import kotlinx.coroutines.delay

internal class BattleUpgradeHandler(private val ctx: FarmStateContext, private val navigator: NavigateAndScanHandler, private val taskScheduler: TaskScheduler) {

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

    internal suspend fun dailyBattle() {
        rt.log("检查每日对战...")
        if (taskScheduler.isDailyBattleDone()) rt.log("今日对战已完成，跳过")
        else doDailyBattle()
        transitionToInternal(FarmState.UpgradeDecision)
    }


    internal suspend fun upgradeDecision() {
        rt.log("执行升级决策...")
        doUpgradeDecision()
        rt.log("农场循环完毕，安排下次浇水闹钟")
        taskScheduler.scheduleNextWatering()
        transitionToInternal(FarmState.Idle)
    }

    // ========== 实际操作的包裹层 (捕获异常) ==========


    internal suspend fun doDailyBattle() = withErrorLog("每日对战") { dailyBattleInternal() }

    internal suspend fun doUpgradeDecision() = withErrorLog("升级决策") { upgradeDecisionInternal() }


    internal suspend fun dailyBattleInternal() {
        val cfg = ConfigManager.getConfig()
        if (!cfg.dailyBattle) { rt.log("配置关闭对战，跳过"); return }
        // === 最新玩法: 对局任务奖励在周末双倍期翻倍 → 优先周末完成 ===
        if (GameRules.isWeekendDouble()) {
            rt.log("💰当前为周末双倍期，本次对局的农场币与经验奖励x2")
            // === S45 流光沙漏: 双倍期效果减半，建议工作日使用 ===
            rt.log("⏳沙漏提醒(S45): 双倍期流光沙漏效果减半(全场仅-${GameRules.hourglassEffectiveMinutes()}分钟)，建议留到工作日使用")
        }
        // v1.4.4: 真实模式点击对战入口后即标记完成并告知用户手动收尾；
        // 不再挂机 delay(surrenderAfterSec) 假装打完整局（对局自动化涉及复杂游戏AI，超出范围）
        if (ocrClick.isRealMode()) {
            rt.log("🎮真实模式: 尝试打开对战入口（投降秒数=${cfg.surrenderAfterSec}s）")
            val clicked = ocrClick.tryClickTexts("对战模式", "对战", "开始匹配")
            if (clicked) {
                rt.log("✓已点击对战入口。请在约${cfg.surrenderAfterSec}s后手动投降领奖（自动打对局暂未接入），今日对战已标记完成")
                taskScheduler.markDailyBattleDone()
            } else {
                rt.log("⚠未找到对战入口，跳过每日对战（不标记完成，下轮重试）")
            }
            return
        }
        rt.log("[模拟] 选择模式: ${if (cfg.newMode) "新模式(S43/S44)" else "经典模式"}")
        delay(RandomDelay.uniform(2500, 4000))
        rt.log("[模拟] 等待对局开始")
        delay(5000)
        rt.log("[模拟] 对局进行中 ${cfg.surrenderAfterSec}s...")
        delay(cfg.surrenderAfterSec * 1000L)
        rt.log("[模拟] 投降并领取奖励")
        delay(2500)
        taskScheduler.markDailyBattleDone()
    }


    internal suspend fun upgradeDecisionInternal() {
        val cfg = ConfigManager.getConfig()
        rt.log("优先级: ${cfg.upgradePriority.name}, 开地: ${cfg.expandAlways || cfg.upgradeExpandAfter}, 完美模式: ${cfg.perfectMode}")
        // === 最新玩法: 按等级解锁表给出升级建议 ===
        val lands = GameRules.landCountForLevel(cfg.farmLevel)
        val next = GameRules.nextUnlock(cfg.farmLevel)
        rt.log("农场等级${cfg.farmLevel}: 当前${lands}块地" +
                (next?.let { " → Lv${it.level}解锁: ${it.feature}" } ?: " (已满级)"))
        if (cfg.farmLevel >= 4 && cfg.farmLevel < 8) {
            rt.log("💡提示: 4级已解锁一键浇水，8级将解锁一键收获")
        }
        delay(RandomDelay.uniform(600, 1400))
        // v1.4.7: 有通道时尝试真实开地/升级入口点击，不再无条件 [模拟]
        if (!ocrClick.isRealMode() || rt.screenshotProvider == null) {
            rt.log("[模拟] 升级/开地演示（无截图/点击通道）")
            delay(1000)
            return
        }
        // v1.4.36: 开垦"主动化"。此前仅在当前画面被动找"可开垦/开地"文字，画面没有就跳过，
        // 且 expandAlways/upgradeExpandAfter 两个开关形同虚设（每次循环都尝试）。现改为：
        //   1) 先按配置门控（expandAlways 或 upgradeExpandAfter 才执行），配置全关则不开垦；
        //   2) 需要开垦时主动先确认在自家农场（不在则先导航进入）；
        //   3) 再主动找开垦入口点击→确认。游戏界面只在"真正有地可开"时才显示"可开垦/开垦"，
        //      找不到即如实记录，不误报成功。
        val shouldExpand = cfg.expandAlways || cfg.upgradeExpandAfter
        if (!shouldExpand) {
            rt.log("开垦未开启（expandAlways=false 且 upgradeExpandAfter=false），本轮跳过开垦")
            return
        }
        rt.log("开垦门控开启：主动寻找开垦入口并执行")
        if (!navigator.confirmFarmScene()) {
            rt.log("开垦前未确认在自家农场，尝试导航进入...")
            if (!navigator.doNavigateToFarm()) {
                rt.log("⚠开垦前置导航失败，本轮跳过")
                return
            }
        }
        // v1.4.11: 关键词补「开垦/可开垦」——游戏告示牌显示"可开垦"，仅凭"开地/购买土地/
        // 解锁土地/扩建"无法命中（.contains 子串匹配），导致升级/开地功能不可用
        // v1.4.37: 开垦需走到对应地块才可操作——先走位接近"可开垦"地块再点击，
        // 否则远距离点击无效（相机跟随角色，入口文字可能位于角色远处）。
        val expandKw = arrayOf("开垦", "可开垦", "开地", "购买土地", "解锁土地", "扩建", "扩充土地")
        if (ocrClick.ocrContainsAny(*expandKw)) {
            movement.walkNearText(expandKw.toList(), maxSteps = 5)
        }
        if (ocrClick.tryClickTexts(*expandKw)) {
            delay(RandomDelay.uniform(1000, 2000))
            if (ocrClick.tryClickTexts("确定", "确认", "购买")) {
                rt.log("✓已执行开地/升级确认")
            } else {
                rt.log("✓已点击开地/升级入口（未见确认弹窗，可能已直接生效）")
            }
        } else {
            rt.log("⚠主动开垦: 农场页面未找到开垦/开地入口（当前可能无地可开，或需真机校准按钮位置）")
        }
    }

}
