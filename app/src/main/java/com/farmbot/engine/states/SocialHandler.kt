package com.farmbot.engine.states

import com.farmbot.engine.FarmState
import com.farmbot.engine.runtime.FarmStateContext
import com.farmbot.config.ConfigManager
import com.farmbot.policy.GameRules
import com.farmbot.util.DailyCounters
import com.farmbot.util.RandomDelay
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal class SocialHandler(private val ctx: FarmStateContext) {

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

    internal suspend fun visitFriends() {
        rt.log("拜访好友农场...")
        doVisitFriends()
        transitionToInternal(FarmState.DailyBattle)
    }


    internal suspend fun doVisitFriends() = withErrorLog("拜访好友") { visitFriendsInternal() }

    internal suspend fun visitFriendsInternal() {
        val cfg = ConfigManager.getConfig()
        if (!cfg.enableSteal && !cfg.enableBless) {
            rt.log("偷菜与祝福均已关闭，跳过好友拜访")
            return
        }
        // === 最新玩法1: 偷菜保护时间 0:00~8:00 ===
        if (cfg.enableSteal && cfg.respectStealProtection && GameRules.isStealProtectionTime()) {
            rt.log("🌙保护时间(0点~8点)无法偷菜，本轮仅执行祝福")
        }
        // === 最新玩法2: 每日限额管控 ===
        val stealRemain = DailyCounters.remainingSteal(cfg.stealDailyLimit)
        val blessRemain = DailyCounters.remainingBless(cfg.blessDailyLimit)
        if (stealRemain <= 0) rt.log("⚠今日偷菜已达上限(${cfg.stealDailyLimit})，仅执行祝福")
        if (blessRemain <= 0) rt.log("⚠今日祝福已达上限(${cfg.blessDailyLimit})，仅执行偷菜")
        if (stealRemain <= 0 && blessRemain <= 0) {
            rt.log("今日社交操作全部达上限，跳过好友拜访")
            return
        }
        // === 最新玩法3: 偷菜/祝福顺序 ===
        // 先偷后祝(默认): 偷走普通作物后祝福触发病块变异，变异留给下轮二次偷取
        // 先祝后偷: 祝福立即触发变异，马上偷取高价值变异作物直接落袋
        val canStealNow = cfg.enableSteal && stealRemain > 0 &&
                !(cfg.respectStealProtection && GameRules.isStealProtectionTime())
        val canBlessNow = cfg.enableBless && blessRemain > 0
        val stealFirst = cfg.stealBeforeBless
        // v1.4.7: 有截图+点击通道时走真实 OCR 导航（社交→好友列表→逐个拜访）；
        // 演示路径不再写入每日计数（此前模拟会虚假消耗真实配额）
        if (ocrClick.isRealMode() && rt.screenshotProvider != null) {
            val visited = visitFriendsViaOcr(canStealNow, canBlessNow, stealRemain, blessRemain)
            rt.log("好友拜访完成: 顺序[${if (stealFirst) "先偷后祝" else "先祝后偷"}] 真实处理 $visited 位好友农场")
            return
        }
        val phases = if (stealFirst) listOf("steal", "bless") else listOf("bless", "steal")
        for (phase in phases) {
            when (phase) {
                "steal" -> if (canStealNow) {
                    val n = minOf(kotlin.random.Random.nextInt(3, 8), stealRemain)
                    rt.log("[模拟] 偷菜阶段演示 $n 位好友（无截图/点击通道，不计入每日次数）")
                    for (i in 0 until n) {
                        if (!rt.isRunning.value || !rt.scope.isActive) break
                        rt.log("[模拟]🥬偷菜 ${i + 1}/$n")
                        delay(RandomDelay.uniform(1000, 2200))
                    }
                }
                "bless" -> if (canBlessNow) {
                    val n = minOf(kotlin.random.Random.nextInt(2, 6), blessRemain)
                    rt.log("[模拟] 祝福阶段演示 $n 位好友（无截图/点击通道，不计入每日次数）")
                    for (i in 0 until n) {
                        if (!rt.isRunning.value || !rt.scope.isActive) break
                        rt.log("[模拟]🙏祝福 ${i + 1}/$n 变异概率+${GameRules.MUTATION_BLESS_BONUS_MIN}%~${GameRules.MUTATION_BLESS_BONUS_MAX}%")
                        delay(RandomDelay.uniform(1000, 2200))
                    }
                }
            }
        }
        rt.log("好友拜访完成: 顺序[${if (stealFirst) "先偷后祝" else "先祝后偷"}] 演示路径不写入每日计数")
    }

    /**
     * v1.4.7: 好友拜访真实链路——OCR 定位"社交/好友"入口 → 好友列表逐个点"拜访" →
     * 在好友农场内尝试偷菜/祝福 → "返回"回列表继续。任何环节失败如实报告并停止，
     * 每日计数只在真实点击成功后写入。
     */

    internal suspend fun visitFriendsViaOcr(
        canSteal: Boolean, canBless: Boolean, stealRemain: Int, blessRemain: Int
    ): Int {
        // v1.4.13: 好友入口关键词以「社交」按钮优先（农场页右下角为权威入口）。
        // 原顺序"好友列表/好友/社交"会让 OCR 优先命中页面上的"好友列表"标题等非按钮文本
        // （.contains 子串匹配按顺序取首个命中），导致点到标题中心而实际无效、流程空转。
        if (!ocrClick.tryClickTexts("社交", "好友列表", "好友农场", "好友")) {
            rt.log("⚠真实模式: 未找到好友入口，跳过本轮偷菜/祝福（不计入每日次数）")
            return 0
        }
        delay(RandomDelay.uniform(1800, 3000))
        var stealLeft = stealRemain
        var blessLeft = blessRemain
        var visited = 0
        for (i in 0 until 8) {
            if (!rt.isRunning.value || !rt.scope.isActive) break
            if ((!canSteal || stealLeft <= 0) && (!canBless || blessLeft <= 0)) {
                rt.log("今日偷菜/祝福配额已用完，停止拜访")
                break
            }
            if (!ocrClick.tryClickTexts("拜访", "进入好友农场", "去TA的农场", "TA的农场", "进入农场")) {
                rt.log("⚠真实模式: 好友列表无更多拜访入口，共进入 $visited 位")
                break
            }
            delay(RandomDelay.uniform(2200, 3600))
            var acted = false
            // v1.4.12: 偷菜按钮实际文案为"拿取"（靠近发光成熟作物点击拿取），补入关键词
            // v1.4.37: 偷菜/祝福需走到对应地块才可操作——先朝目标走位再点击（目标不在画面则跳过走位）
            if (canSteal && stealLeft > 0 && ocrClick.ocrContainsAny("拿取", "可偷")) {
                movement.walkNearText(listOf("拿取", "可偷"), maxSteps = 4)
            }
            if (canSteal && stealLeft > 0 && ocrClick.tryClickTexts("拿取", "可偷", "偷菜", "摘取")) {
                DailyCounters.addSteal(); stealLeft--; acted = true
                rt.log("✓真实偷菜完成(今日剩余$stealLeft)")
            }
            if (canBless && blessLeft > 0 && ocrClick.ocrContainsAny("祝福", "祈福")) {
                movement.walkNearText(listOf("祝福", "祈福"), maxSteps = 4)
            }
            if (canBless && blessLeft > 0 && ocrClick.tryClickTexts("祝福", "祈福")) {
                DailyCounters.addBless(); blessLeft--; acted = true
                rt.log("✓真实祝福完成(今日剩余$blessLeft)")
            }
            visited++
            if (!acted) rt.log("⚠真实模式: 第${visited}位好友农场未找到偷菜/祝福入口")
            if (!ocrClick.tryClickTexts("返回", "好友列表")) {
                rt.log("⚠真实模式: 无法返回好友列表，终止本轮拜访")
                break
            }
            delay(RandomDelay.uniform(1200, 2200))
        }
        // 尽力回到自己农场，失败交由后续导航状态兜底。
        // v1.4.23: 末尾不再用裸词"农场"——它会被大厅聊天面板的"农场"标签/任意"农场"字样命中，
        // 导致误点进大厅聊天页（实测日志 "OCR定位[农场](275,49)"）。仅保留精确词。
        ocrClick.tryClickTexts("返回农场", "我的农场", "离开好友农场")
        return visited
    }

}
