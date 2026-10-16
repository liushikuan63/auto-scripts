package com.farmbot.engine.states

import com.farmbot.engine.FarmState
import com.farmbot.engine.runtime.FarmStateContext
import com.farmbot.config.ConfigManager
import com.farmbot.policy.CropDatabase
import com.farmbot.policy.GameRules
import com.farmbot.util.RandomDelay
import kotlinx.coroutines.delay

internal class NavigateAndScanHandler(private val ctx: FarmStateContext) {

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

    internal suspend fun confirmFarmScene(): Boolean {
        val provider = rt.screenshotProvider ?: return false
        for (attempt in 0 until 3) {
            val bmp = runCatching { provider() }.getOrNull() ?: return false
            try {
                val latch = java.util.concurrent.CountDownLatch(1)
                var inFarm = false
                rt.ocr.recognizeText(bmp) { _, blocks ->
                    val full = blocks.joinToString("") { it.text.replace("\\s".toRegex(), "") }
                    val farmStrong = listOf(
                        "一键浇水", "一键收获", "农场等级",
                        "可开垦", "我的农场", "农场小摊", "浇水壶"
                    ).any { full.contains(it) }
                    // 引导态农场独有元素（大厅/对战页不存在）：
                    // "种植"右下角按钮、"仓库"右缘入口、"去告示牌"引导提示
                    val farmOnboard = listOf("种植", "仓库", "去告示牌").any { full.contains(it) }
                    inFarm = farmStrong || farmOnboard
                    latch.countDown()
                }
                latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                if (inFarm) return true
            } finally {
                bmp.recycle()
            }
            if (attempt < 2) delay(800)
        }
        return false
    }

    /**
     * v1.4.24: 用 OCR 自动检测农场等级并校正配置。
     * 配置默认 farmLevel=1，而账号实际农场等级可能更高（如 Lv38），导致策略/地块数/功能解锁判断错误。
     * 仅应在确认已身处农场页面后调用。识别优先级：
     *   1) 显式 "农场等级N" / "等级N" / "LvN"
     *   2) 兜底：农场名"XX的农场"同排顶部区域的 2 位纯数字（等级徽章）
     */

    internal suspend fun detectFarmLevel(): Int? {
        val provider = rt.screenshotProvider ?: return null
        val bmp = runCatching { provider() }.getOrNull() ?: return null
        return try {
            val out = java.util.concurrent.atomic.AtomicInteger(-1)
            val latch = java.util.concurrent.CountDownLatch(1)
            rt.ocr.recognizeText(bmp) { _, blocks ->
                var level = -1
                for (b in blocks) {
                    val clean = b.text.replace("\\s".toRegex(), "")
                    val mLevel = Regex("(?:农场)?等级[:：]?(\\d{1,3})").find(clean)
                    if (mLevel != null) {
                        level = mLevel.groupValues[1].toIntOrNull() ?: -1
                        break
                    }
                    val mLv = Regex("Lv[:：]?(\\d{1,3})", RegexOption.IGNORE_CASE).find(clean)
                    if (mLv != null) {
                        level = mLv.groupValues[1].toIntOrNull() ?: -1
                        break
                    }
                }
                // 兜底：顶部区域的纯数字(1~100位宽1-3)即等级徽章；货币形如"757.84万"不会被当纯数字
                if (level <= 0) {
                    val num = blocks.firstOrNull { b ->
                        b.y in 1..280 &&
                                b.text.matches(Regex("\\d{1,3}")) &&
                                (b.text.toIntOrNull() ?: -1) in 1..GameRules.FARM_MAX_LEVEL
                    }
                    if (num != null) level = num.text.toIntOrNull() ?: -1
                }
                out.set(level)
                latch.countDown()
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            val v = out.get()
            if (v in 1..GameRules.FARM_MAX_LEVEL) v else null
        } finally {
            bmp.recycle()
        }
    }


    internal suspend fun navigateToFarm() {
        rt.log("导航至农场...")
        val success = doNavigateToFarm()
        if (success) {
            rt.retryCount = 0
            transitionToInternal(FarmState.ScanCrops)
        } else {
            handleRecover(FarmState.NavigateToFarm)
        }
    }


    internal suspend fun scanCrops() {
        rt.log("扫描作物状态...")
        // 避免在大厅乱点并虚假报告
        if (!confirmFarmScene()) {
            rt.log("⚠当前未确认在农场页面，回到导航重试（避免在大厅误点）")
            transitionToInternal(FarmState.NavigateToFarm)
            return
        }
        // v1.4.24: 自动校正农场等级——配置默认1，账号实际可为高阶（如Lv38），影响策略/地块数判断
        var cfg = ConfigManager.getConfig()
        val detected = detectFarmLevel()
        if (detected != null && detected != cfg.farmLevel) {
            val previousLevel = cfg.farmLevel
            cfg = cfg.copy(farmLevel = detected)
            ConfigManager.save(cfg)
            rt.log("📈OCR检测农场等级=Lv$detected，已更新配置（原$previousLevel）")
        }
        if (hasReachedDailyLimit(rt.dailyOperationCount, cfg.maxDailyOperations)) {
            rt.log("已达每日操作上限 (${cfg.maxDailyOperations})，停止")
            transitionToInternal(FarmState.Idle)
            return
        }
        // v1.4.35: 流程控制——默认必须优先完成自家农场全部操作(种植/浇水/收获/出售)后才进入好友农场；
        // 仅当用户显式启用"优先偷菜"(prioritySteal) 时，才允许跳过自家农场、优先拜访好友农场偷菜。
        if (cfg.prioritySteal) {
            rt.log("⚡优先偷菜模式已启用：跳过自家农场操作（种植/浇水/收获），优先拜访好友农场偷菜")
            transitionToInternal(FarmState.VisitFriends)
            return
        }
        printCropStrategyAdvice(cfg)
        val hasRipe = checkRipeCrops()
        if (hasRipe) transitionToInternal(FarmState.HarvestCrops)
        else transitionToInternal(FarmState.WaterCrops)
    }

    /** 最新玩法：按农场等级+时段推荐种植策略（工作日经验/周末双倍金币） */

    internal fun printCropStrategyAdvice(cfg: com.farmbot.config.FarmConfig) {
        runCatching {
            val isDouble = GameRules.isWeekendDouble()
            val rec = CropDatabase.recommend(
                farmLevel = cfg.farmLevel,
                strategy = when (cfg.cropStrategy) {
                    com.farmbot.config.FarmConfig.CropStrategy.AUTO -> CropDatabase.Strategy.AUTO
                    com.farmbot.config.FarmConfig.CropStrategy.EXP_FIRST -> CropDatabase.Strategy.EXP_FIRST
                    com.farmbot.config.FarmConfig.CropStrategy.COIN_FIRST -> CropDatabase.Strategy.COIN_FIRST
                },
                isWeekendDouble = isDouble
            )
            val mode = if (isDouble) "周末双倍(产量x2)→金币优先" else "工作日→经验优先"
            rt.log("💡策略[$mode] 等级${cfg.farmLevel}(${GameRules.landCountForLevel(cfg.farmLevel)}块地)" +
                    (rec?.let { " → 推荐: ${it.name}" + (it.heroName?.let { h -> "(英雄变异:$h,不可被偷)" } ?: "") } ?: ""))
        }
    }


    internal suspend fun doNavigateToFarm(): Boolean {
        return withErrorLog("导航至农场") { navigateToFarmInternal() } ?: false
    }

    internal suspend fun checkRipeCrops(): Boolean =
        withErrorLog("检测成熟作物") { scanForRipeCrops() } ?: false

    internal suspend fun navigateToFarmInternal(): Boolean {
        val gamePkg = "com.tencent.tmgp.sgame"
        // v1.4.4: 游戏不在前台时经系统真实拉起游戏并等待启动（此前为纯日志模拟且恒返回成功）
        if (!com.farmbot.accessibility.ForegroundAppDetector.isTargetGameForeground.value) {
            val ctx = com.farmbot.accessibility.FarmAccessibilityService.getInstance()
            if (ctx == null) {
                rt.log("⚠无障碍服务未连接，无法拉起游戏，进入恢复流程")
                return false
            }
            val intent = ctx.packageManager.getLaunchIntentForPackage(gamePkg)
            if (intent == null) {
                rt.log("⚠未安装目标游戏($gamePkg)，无法导航")
                return false
            }
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            rt.log("🚀拉起王者荣耀...")
            val launched = runCatching { ctx.startActivity(intent) }.isSuccess
            if (!launched) {
                rt.log("⚠拉起游戏失败")
                return false
            }
            var waited = 0
            while (waited < 12000 &&
                !com.farmbot.accessibility.ForegroundAppDetector.isTargetGameForeground.value
            ) {
                delay(1000); waited += 1000
                if (!rt.isRunning.value) return false
            }
            if (!com.farmbot.accessibility.ForegroundAppDetector.isTargetGameForeground.value) {
                rt.log("⚠等待游戏进入前台超时(12s)")
                return false
            }
            rt.log("✓游戏已到前台(${waited / 1000}s)，等待大厅加载")
            delay(RandomDelay.uniform(6000, 9000))
        }
        // v1.4.22: 若已身处农场（含新手引导态），无需再找大厅入口横幅，直接进入扫描。
        // 此前无论游戏是否已在农场都先点"王者农场"横幅 → 已在农场时横幅不存在，
        // OCR 必然找不到 → 反复重试→升级恢复流程（用户反馈"已进农场仍狂刷重试"）。
        if (confirmFarmScene()) {
            rt.log("✅检测到已身处农场（含引导态），跳过入口点击")
            return true
        }
        // 游戏在前台：三级降级链点击农场入口
        rt.log("真实导航: 点击农场入口（节点精确→模糊→OCR坐标）")
        val entered = ocrClick.tryClickTexts("王者农场", "周末双倍", "进入农场", "去农场", "我的农场")
        if (entered) {
            // v1.4.14: 点击入口后轮询用 OCR 确认进入农场（农场加载/OCR 识别需要时间），
            // 最多约 12s；未确认则返回 false 进入恢复流程，绝不误入后续状态。
            // v1.4.18: 「王者农场 周末双倍」为轮播横幅，X 坐标随时间变化（日志 427→568），
            // 首击可能未落准或横幅已滚动 → 轮询期间未确认则重新点击入口再确认，而非干等。
            for (i in 0 until 7) {
                delay(RandomDelay.uniform(1500, 2500))
                if (!rt.isRunning.value) return false
                if (confirmFarmScene()) {
                    rt.log("✅已确认进入农场页面")
                    return true
                }
                rt.log("尚未确认进入农场，尝试重新点击入口(${i + 1})")
                ocrClick.tryClickTexts("王者农场", "周末双倍", "进入农场", "去农场", "我的农场")
            }
            rt.log("⚠点击了农场入口但未确认进入农场页面（OCR未见农场标志词，可能需手动进入）")
            return false
        }
        rt.log("⚠农场入口点击失败（OCR通道需录屏授权；可手动进入一次农场页面后重试）")
        return false
    }


    internal suspend fun scanForRipeCrops(): Boolean {
        // v1.4.4: 此前为 Random.nextDouble()<0.55 纯随机判定 → 改为真实OCR检测成熟标识
        val provider = rt.screenshotProvider
        if (!ocrClick.isRealMode() || provider == null) {
            rt.log("[模拟] 无截图/点击通道，随机判定成熟状态")
            delay(RandomDelay.uniform(400, 900))
            return kotlin.random.Random.nextDouble() < 0.55
        }
        val bmp = runCatching { provider() }.getOrNull()
        if (bmp == null) {
            rt.log("⚠截屏失败（录屏授权可能已失效），本轮按无成熟处理")
            return false
        }
        try {
            // v1.4.36: 农场主界面并无"一键收获/成熟作物"等文字（它们位于一键务农设置面板内），
            // OCR 恒检测不到 → 成熟检测恒 false，导致"成熟不触发收获"。改为：
            //   ① 优先 OCR 检测成熟字样（兼容少数界面确有该词）；
            //   ② 用图像识别 findMatureCrops 检测金色成熟作物；
            //   ③ 仍无则兜底返回 true——交给 oneKey.doOneKeyFarming(一键务农) 统一处理，
            //      未成熟会被游戏自动跳过，不会误操作。
            val found = java.util.concurrent.atomic.AtomicBoolean(false)
            val latch = java.util.concurrent.CountDownLatch(1)
            rt.ocr.recognizeText(bmp) { fullText, _ ->
                found.set(
                    listOf("一键收获", "全部收获", "成熟作物", "可收获").any { fullText.contains(it) }
                )
                latch.countDown()
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            if (found.get()) {
                rt.log("🔍OCR检测到成熟作物标识")
                return true
            }
            // OCR 无成熟字样 → 图像识别金色成熟作物
            val mature = rt.matcher.findMatureCrops(bmp)
            if (mature.isNotEmpty()) {
                rt.log("🔍图像识别到 ${mature.size} 处成熟作物，触发收获流程")
                return true
            }
            // v1.4.36: 成熟作物可能处于视觉遮挡/金色阈值边缘，图像识别漏检。
            // 兜底返回 true——交给 oneKey.doOneKeyFarming(一键务农) 统一处理，
            // 游戏对未成熟作物会自动跳过，不会误操作；若真无作物，一键务农也只会空跑一次。
            rt.log("🔍未检测到成熟标识，兜底交由一键务农处理")
            return true
        } finally {
            bmp.recycle()
        }
    }

}
