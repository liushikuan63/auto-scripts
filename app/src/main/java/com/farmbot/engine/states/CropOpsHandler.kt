package com.farmbot.engine.states

import com.farmbot.engine.FarmState
import com.farmbot.engine.runtime.FarmStateContext
import com.farmbot.config.ConfigManager
import com.farmbot.policy.GameRules
import com.farmbot.util.Logger
import com.farmbot.util.RandomDelay
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 自家农场操作：浇水、收获、售卖 / 存储
 *
 * 三者共用「一键务农」链路（见 OneKeyFarmingSupport）。每日操作次数达到配置上限后
 * 立即停止本轮操作并回到空闲，避免超出账号的每日额度。
 */
internal class CropOpsHandler(private val ctx: FarmStateContext, private val navigator: NavigateAndScanHandler) {

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

    internal suspend fun waterCrops() {
        rt.log("执行浇水...")
        val cfg = ConfigManager.getConfig()
        // === 最新玩法：周末双倍卡点 ===
        // 浇水会缩短成熟时间。周五临近18:00时浇水会把成熟点"提前"到双倍开启前，
        // 导致收获吃不到产量x2 → 暂缓本轮浇水，等双倍开启后再浇。
        if (cfg.weekendDoubleAware && GameRules.shouldHoldWateringForDouble(waterIntervalMin = cfg.waterIntervalMin)) {
            rt.log("⏸卡双倍: 周五${GameRules.DOUBLE_START_HOUR}点前暂缓浇水，避免作物提前成熟错过产量x2")
            transitionToInternal(FarmState.HarvestCrops)
            return
        }
        val watered = doWaterCrops()
        rt.dailyOperationCount += watered
        // === 融合 HonorFarmBot：OCR 倒计时动态调整浇水间隔 ===
        if (cfg.inheritCountdown && watered > 0) {
            val seconds = readCountdownViaOcr()
            if (seconds > 0) {
                val minutes = (seconds / 60).coerceAtLeast(1)
                Logger.i(TAG, "OCR倒计时=$seconds 秒 → 调整浇水间隔=$minutes 分钟")
                rt.log("OCR倒计时 ${seconds}s → 浇水间隔 ${minutes} 分钟")
                if (minutes != cfg.waterIntervalMin) {
                    ConfigManager.save(cfg.copy(waterIntervalMin = minutes))
                }
            }
        }
        rt.log("本轮浇水 $watered 株作物")
        transitionToInternal(FarmState.HarvestCrops)
    }

    /**
     * 通过 OCR 读取作物上方的倒计时（秒）。
     * v1.4.4 真实化：截屏后正则仅匹配 m:ss 形态，避免把金币数"120"误认为倒计时。
     */

    internal suspend fun readCountdownViaOcr(): Int {
        val provider = rt.screenshotProvider ?: return -1
        val bmp = runCatching { provider() }.getOrNull() ?: return -1
        try {
            val result = java.util.concurrent.atomic.AtomicInteger(-1)
            val latch = java.util.concurrent.CountDownLatch(1)
            rt.ocr.recognizeText(bmp) { _, blocks ->
                val regex = Regex("(\\d{1,3}):(\\d{2})")
                for (b in blocks) {
                    val m = regex.find(b.text)
                    if (m != null) {
                        val mm = m.groupValues[1].toIntOrNull()
                        val ss = m.groupValues[2].toIntOrNull()
                        if (mm != null && ss != null && ss < 60) {
                            result.set(mm * 60 + ss)
                            break
                        }
                    }
                }
                latch.countDown()
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            return result.get()
        } catch (t: Throwable) {
            return -1
        } finally {
            bmp.recycle()
        }
    }


    internal suspend fun harvestCrops() {
        rt.log("执行收获...")
        val harvested = doHarvestCrops()
        rt.dailyOperationCount += harvested
        rt.log("本轮收获 $harvested 株作物")
        transitionToInternal(FarmState.SellOrStore)
    }


    internal suspend fun sellOrStore() {
        rt.log("处理仓库/售卖...")
        doSellOrStore()
        transitionToInternal(FarmState.VisitFriends)
    }


    internal suspend fun doWaterCrops(): Int =
        withErrorLog("浇水") { waterCropsInternal() } ?: 0

    internal suspend fun doHarvestCrops(): Int =
        withErrorLog("收获") { harvestCropsInternal() } ?: 0

    internal suspend fun doSellOrStore() = withErrorLog("售卖/存储") { sellOrStoreInternal() }

    internal suspend fun waterCropsInternal(): Int {
        // === 真实模式：优先点"一键浇水"（Lv4+ 解锁，一次批量完成） ===
        // v1.4.4: 点击失败如实返回0交给上层恢复链，不再静默走假计数循环冒充真实进度
        // v1.4.19: 先确认在农场才执行，杜绝在大厅误命"浇水/收获"等宣传文字假成功
        if (ocrClick.isRealMode()) {
            if (!navigator.confirmFarmScene()) {
                rt.log("⚠未确认在农场页面，本次浇水不计入（避免大厅误点）")
                return 0
            }
            // v1.4.26: 浇水/收获/种植统一走"一键务农"（需靠近雕像才显示）：点"一键务农"→设置面板勾选→确定即自动执行。
            // 此前找"一键浇水/浇水壶"文字（农场主界面并不存在，仅在一键务农面板内）与逐株OCR均不可用。
            return oneKey.doOneKeyFarming()
        }
        val cfg = ConfigManager.getConfig()
        var watered = 0
        // 土地数量按 S45 新模型动态计算（不再写死6~12）
        val plots = GameRules.landCountForLevel(cfg.farmLevel)
        rt.log("[模拟] 无截图/点击通道，按地块数=$plots 走演示流程")
        for (i in 0 until plots) {
            if (!rt.isRunning.value || !rt.scope.isActive) break
            if (hasReachedDailyLimit(rt.dailyOperationCount, cfg.maxDailyOperations)) break
            rt.log("浇水第 ${i + 1} 块地")
            delay(RandomDelay.uniform(
                cfg.operationDelayMinMs.toLong(),
                cfg.operationDelayMaxMs.toLong()
            ))
            watered++
            if (watered % 5 == 0) {
                rt.log("拟人停顿: ${RandomDelay.gaussian(mean = 2000.0, sigma = 600.0, min = 800.0, max = 4000.0).toLong()}ms")
                delay(RandomDelay.gaussian(mean = 2000.0, sigma = 600.0, min = 800.0, max = 4000.0).toLong())
            }
        }
        return watered
    }


    internal suspend fun harvestCropsInternal(): Int {
        // === 真实模式：优先点"一键收获"（Lv8+ 解锁） ===
        // v1.4.4: 点击失败如实返回0，不再随机编造收获株数
        // v1.4.19: 先确认在农场才执行，防止大厅误命"收获"文字假成功
        if (ocrClick.isRealMode()) {
            if (!navigator.confirmFarmScene()) {
                rt.log("⚠未确认在农场页面，本次收获不计入（避免大厅误点）")
                return 0
            }
            // v1.4.26: 收获与浇水同源，统一走"一键务农"（一次即可覆盖浇水/收获/种植）
            return oneKey.doOneKeyFarming()
        }
        var harvested = 0
        val ripe = kotlin.random.Random.nextInt(0, 6)
        rt.log("[模拟] 演示收获 $ripe 株")
        for (i in 0 until ripe) {
            if (!rt.isRunning.value || !rt.scope.isActive) break
            rt.log("收获第 ${i + 1} 株作物")
            delay(RandomDelay.gaussian(mean = 700.0, sigma = 200.0, min = 300.0, max = 1500.0).toLong())
            harvested++
        }
        return harvested
    }

    /**
     * v1.4.26: 浇水/收获/种植统一走"一键务农"。王者农场主界面并无独立的"一键浇水/一键收获"文字按钮
     * （它们位于"一键务农"设置面板内）；作物为纯画面、无 OCR 文本，逐株定位不可用。
     * v1.4.36: 真机实测纠正——打开设置面板的是**一键务农按钮右上方的齿轮(设置)图标**，点按钮本体不弹出面板。
     * 因此正确路径为：靠近雕像→点齿轮→面板（一键浇水/一键收获/一键种植三项默认勾选）→点确定。
     * 未靠近雕像(找不到按钮)、面板未打开或未确认时如实返回 0，不假成功。
     */

    internal suspend fun sellOrStoreInternal() {
        val cfg = ConfigManager.getConfig()
        // === 真实模式：优先点击出售入口 ===
        // v1.4.4: 点击失败明确提示，不再无声跳过
        if (ocrClick.isRealMode()) {
            if (!ocrClick.tryClickTexts("一键出售", "全部出售", "出售")) {
                rt.log("⚠真实模式: 未找到出售入口，跳过真实出售动作")
            }
        }
        // === 最新玩法：囤货策略 ===
        // 双倍只加产量不加单价 → 高价值作物(变异/英雄果实)囤仓库，等周末双倍产量翻倍后再卖
        if (cfg.hoardUntilWeekend && !GameRules.isWeekendDouble()) {
            rt.log("📦囤货模式: 非双倍期暂不出售高价值作物，距下次双倍约 ${GameRules.minutesUntilNextDouble() / 60} 小时")
            rt.log("仓库容量检查: ${if (cfg.farmLevel >= 30) "已扩容400格" else "注意30级扩容前防溢出"}")
            delay(RandomDelay.uniform(500, 1200))
            return
        }
        if (cfg.hoardUntilWeekend && GameRules.isWeekendDouble()) {
            rt.log("💰周末双倍进行中(产量x2)，囤积作物全部出售收益最大化")
        }
        // === S45 新增: 变异果实培育台决策 ===
        // 40级解锁培育台，高倍变异果实可先留作"主材"转移词条给目标作物，
        // 最高840倍四词条叠加 → 强变异果实优先喂培育台而非直接卖
        if (cfg.farmLevel >= GameRules.BREEDING_UNLOCK_LEVEL) {
            rt.log("🧬培育台(S45): Lv${cfg.farmLevel}已解锁${GameRules.BREEDING_NAME}，" +
                    "高倍变异果实建议留存作主材洗词条(上限${GameRules.MAX_MUTATION_STACK}词条/${GameRules.MAX_COMBINED_MULTIPLIER}倍)，普通作物正常出售")
        }
        when (cfg.sellMode) {
            com.farmbot.config.FarmConfig.SellMode.NONE -> rt.log("售卖策略: NONE，跳过")
            com.farmbot.config.FarmConfig.SellMode.SELL_ALL -> rt.log("售卖策略: 全部卖出")
            com.farmbot.config.FarmConfig.SellMode.SELL_MAXED -> rt.log("售卖策略: 仅卖满级")
        }
        if (cfg.farmLevel >= 5) {
            val stallBonus = (cfg.farmLevel * 5).coerceAtMost(GameRules.STALL_MAX_LEVEL * 5)
            rt.log("小摊加成: 等级${cfg.farmLevel}，售价+${stallBonus}%(每级5%，满级${GameRules.STALL_MAX_LEVEL}级+195%)")
        }
        delay(RandomDelay.uniform(500, 1200))
    }

}


/**
 * 每日操作上限判定
 *
 * 原实现为两处内联的 `dailyOperationCount >= cfg.maxDailyOperations`；
 * 抽成单一真源后可直接用 JVM 单测钉住边界语义（达到上限即停止，不再发起本轮操作）。
 */
internal fun hasReachedDailyLimit(used: Int, max: Int): Boolean = used >= max
