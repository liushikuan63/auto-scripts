package com.farmbot.engine

import android.util.Log
import com.farmbot.config.ConfigManager
import com.farmbot.ocr.OcrHelper
import com.farmbot.policy.CropDatabase
import com.farmbot.policy.GameRules
import com.farmbot.recognition.TemplateCollector
import com.farmbot.recognition.TemplateMatcher
import com.farmbot.util.DailyCounters
import com.farmbot.util.Logger
import com.farmbot.util.RandomDelay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class FarmState {
    object Idle : FarmState()
    object NavigateToFarm : FarmState()
    object RecoverGame : FarmState()     // 异常恢复：游戏重启/重连
    object ReconnectA11y : FarmState()   // 异常恢复：无障碍服务重连
    object ScanCrops : FarmState()
    object WaterCrops : FarmState()
    object HarvestCrops : FarmState()
    object SellOrStore : FarmState()
    object VisitFriends : FarmState()
    object DailyBattle : FarmState()
    object UpgradeDecision : FarmState()

    fun displayName(): String = when (this) {
        Idle -> "空闲"
        NavigateToFarm -> "导航至农场"
        RecoverGame -> "异常恢复(重启游戏)"
        ReconnectA11y -> "异常恢复(重连无障碍)"
        ScanCrops -> "扫描作物"
        WaterCrops -> "浇水中"
        HarvestCrops -> "收获中"
        SellOrStore -> "售卖/存储"
        VisitFriends -> "拜访好友"
        DailyBattle -> "每日对战"
        UpgradeDecision -> "升级决策"
    }
}

/**
 * 农场状态机 — 融合 HonorFarmBot 高级特性
 *
 * 新增能力：
 *  - OCR 倒计时自动解析 + 动态调整浇水间隔
 *  - 异常容错：连续失败进入 3 阶段恢复（重试→重连无障碍→重启游戏）
 *  - 全局超时监控（防止卡死）
 *  - 每日操作上限（来自 ConfigManager）
 *  - 操作计数 + 日志注入 Logger
 */
class FarmStateMachine(
    private val taskScheduler: TaskScheduler
) {
    companion object {
        private const val TAG = "FarmStateMachine"
        private const val MAX_RETRY = 5
        private const val MAX_RECOVER_RETRY = 2
        private const val STATE_TIMEOUT_MS = 3 * 60 * 1000L   // 单状态3分钟超时
    }

    private val _currentState = MutableStateFlow<FarmState>(FarmState.Idle)
    val currentState: StateFlow<FarmState> = _currentState.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mutex = Mutex()

    private var retryCount = 0
    private var recoverCount = 0
    private var dailyOperationCount = 0
    private var lastStateTime = 0L

    private val ocr = OcrHelper()
    private val matcher = TemplateMatcher()
    private lateinit var collector: TemplateCollector  // 延迟注入
    @Volatile private var actionExecutor: ActionExecutor? = null  // 延迟注入（真实点击链路）
    @Volatile private var screenshotProvider: (() -> android.graphics.Bitmap?)? = null  // 截图通道（OCR定位依赖）

    // v1.4.8: 录屏授权失效回调 —— 由 FarmEngine 注入为 pause()；通道死亡时快速暂停替代数百次无效重试
    @Volatile private var onCaptureLost: (() -> Unit)? = null

    fun setTemplateCollector(c: TemplateCollector) { collector = c }

    /** 注入动作执行器：无障碍服务连接后由 FarmEngine 调用；注入后业务动作走真实点击 */
    fun setActionExecutor(exec: ActionExecutor?) { actionExecutor = exec }

    /** 注入截图提供者（v1.4.3）：授权录屏后由 FarmEngine 调用，供 OCR 文字定位取帧 */
    fun setScreenshotProvider(provider: (() -> android.graphics.Bitmap?)?) { screenshotProvider = provider }

    /** 注入录屏授权失效回调（v1.4.8）：由 FarmEngine 注入为暂停引擎+发告警通知 */
    fun setOnCaptureLost(cb: (() -> Unit)?) { onCaptureLost = cb }

    fun start() {
        scope.launch { mutex.withLock {
            if (_isRunning.value) return@withLock
            _isRunning.value = true
            retryCount = 0
            recoverCount = 0
            dailyOperationCount = 0
            log("状态机启动")
            if (screenshotProvider == null) {
                log("⚠⚠ 未检测到录屏截图通道：OCR 定位不可用，自动点击无法精确落点，请先在主界面授权录屏！")
            }
            transitionToInternal(FarmState.NavigateToFarm)
        }}
    }

    fun pause() {
        scope.launch { mutex.withLock {
            _isRunning.value = false
            log("状态机暂停")
            transitionToInternal(FarmState.Idle)
        }}
    }

    fun stop() {
        scope.launch { mutex.withLock {
            _isRunning.value = false
            log("状态机停止")
            transitionToInternal(FarmState.Idle)
        }}
    }

    fun transitionTo(next: FarmState) {
        scope.launch { mutex.withLock { transitionToInternal(next) } }
    }

    private fun transitionToInternal(next: FarmState) {
        if (!_isRunning.value && next != FarmState.Idle) {
            log("状态机未运行，忽略转换请求: ${next.displayName()}")
            return
        }
        val prev = _currentState.value
        _currentState.value = next
        lastStateTime = System.currentTimeMillis()
        log("状态转换: ${prev.displayName()} → ${next.displayName()}")

        scope.launch {
            delay(RandomDelay.uniform(300, 1200))
            safeExecuteWithTimeout(next)
        }
    }

    private suspend fun safeExecuteWithTimeout(state: FarmState) {
        val job = scope.launch {
            executeState(state)
        }
        // 超时保护
        scope.launch {
            delay(STATE_TIMEOUT_MS)
            if (job.isActive && _currentState.value == state) {
                Logger.w(TAG, "State '${state.displayName()}' 超时(${STATE_TIMEOUT_MS}ms)，强制中断并恢复")
                log("状态超时，进入恢复流程")
                job.cancel()
                handleRecover(state)
            }
        }
    }

    private suspend fun executeState(state: FarmState) = mutex.withLock {
        if (!_isRunning.value) return@withLock

        runCatching {
            when (state) {
                FarmState.Idle -> log("进入空闲状态")
                FarmState.ReconnectA11y -> reconnectA11y()
                FarmState.RecoverGame -> recoverGame()
                FarmState.NavigateToFarm -> navigateToFarm()
                FarmState.ScanCrops -> scanCrops()
                FarmState.WaterCrops -> waterCrops()
                FarmState.HarvestCrops -> harvestCrops()
                FarmState.SellOrStore -> sellOrStore()
                FarmState.VisitFriends -> visitFriends()
                FarmState.DailyBattle -> dailyBattle()
                FarmState.UpgradeDecision -> upgradeDecision()
            }
        }.onFailure { t ->
            Logger.e(TAG, "executeState 异常 in ${state.displayName()}", t)
            log("异常: ${t.message}")
            handleRecover(state)
        }
    }

    /**
     * 异常容错三级机制：
     *   1. MAX_RETRY 次内重试当前状态
     *   2. 触发重连无障碍服务
     *   3. 触发重启游戏应用（冷启动导航）
     * 连续 MAX_RECOVER_RETRY 次恢复失败则进入空闲。
     */
    private suspend fun handleRecover(failedState: FarmState) {
        // v1.4.8: 录屏授权失效属"不可自愈"故障（重连无障碍/重启游戏都救不了，必须用户重新授权）。
        // 此前按普通失败计入重试且上限取 maxDailyOperations(500)，退避累加导致近一小时无意义空转
        // （日志表现为"截屏失败…重试(n/500)"）；现检测到真实模式+通道死亡立即回调暂停并告警，
        // 待用户重新授权录屏后再点"开始/继续"恢复。
        if (actionExecutor != null &&
            failedState != FarmState.ReconnectA11y &&
            failedState != FarmState.RecoverGame &&
            !com.farmbot.screenshot.ScreenshotService.isCapturing()
        ) {
            log("⛔录屏授权已失效，OCR点击通道中断 — 自动暂停（不再空转重试），请重新授权录屏后点继续")
            onCaptureLost?.invoke()
            return
        }
        retryCount++
        // v1.4.16: 重试上限此前误取 maxDailyOperations(500)，导致导航/场景失败被无限重试
        // （日志"重试(n/500)"，反复点击入口、长时间无结论）。改为按 MAX_RETRY 常量封顶，
        // 失败快速升级到 重连无障碍→重启游戏→进入空闲，给出明确结果。
        val limit = MAX_RETRY
        if (retryCount < limit) {
            log("重试 ($retryCount/$limit)...")
            delay(2000L + retryCount * 1000L)
            transitionToInternal(failedState)
        } else {
            retryCount = 0
            recoverCount++
            when {
                recoverCount == 1 -> {
                    log("尝试恢复阶段1: 重连无障碍服务")
                    transitionToInternal(FarmState.ReconnectA11y)
                }
                recoverCount == 2 -> {
                    log("尝试恢复阶段2: 重启游戏应用")
                    transitionToInternal(FarmState.RecoverGame)
                }
                else -> {
                    log("达到最大恢复次数，进入空闲")
                    recoverCount = 0
                    transitionToInternal(FarmState.Idle)
                }
            }
        }
    }

    // ============ 恢复流程 ============

    private suspend fun reconnectA11y() {
        log("发起无障碍服务重连（客户端侧提示）")
        delay(1500)
        // 让 FarmAccessibilityService 单例自己重连；此处仅模拟"等待"
        try {
            val svcClass = Class.forName("com.farmbot.accessibility.FarmAccessibilityService")
            val instanceMethod = svcClass.getMethod("getInstanceOrNull")
            val inst = instanceMethod.invoke(null)
            if (inst != null) {
                val rec = svcClass.getMethod("reconnect")
                rec.invoke(inst)
                log("重连请求已送达 FarmAccessibilityService")
            }
        } catch (t: Throwable) { /* 忽略反射失败，继续导航 */ }
        delay(1000)
        transitionToInternal(FarmState.NavigateToFarm)
    }

    private suspend fun recoverGame() {
        log("执行游戏恢复流程: HOME→等待→重新导航")
        // v1.4.4: 真实执行 GLOBAL_ACTION_HOME 回桌面（此前仅打日志假装执行）
        val svc = com.farmbot.accessibility.FarmAccessibilityService.getInstance()
        if (svc != null && actionExecutor != null) {
            runCatching {
                svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            }.onSuccess { log("✓已发送HOME键") }
                .onFailure { log("[模拟] HOME动作失败: ${it.message}") }
        } else {
            log("[模拟] 无障碍不可用，跳过HOME动作")
        }
        delay(RandomDelay.uniform(1500, 3000))
        transitionToInternal(FarmState.NavigateToFarm)
    }

    // ============ 业务状态 ============

    /**
     * v1.4.14: 用 OCR 确认当前确实处于农场页面。
     * 王者农场为 SurfaceView 自绘、无障碍节点树为空，GameSceneDetector 的
     * classifyFromText 依赖节点树文本，对农场页面不可靠（大厅 Activity 常被判为
     * GAME_HALL/OTHER_IN_GAME）。故改用 OCR 识别农场标志性文字作为"是否已进农场"的依据，
     * 避免在大厅界面误命中"收获/好友/拜访/农场"等字乱点并虚假报告成功。
     * 注意：不得用"农场/王者农场"作为标志词——大厅入口按钮即含"王者农场 周末双倍"字样。
     */
    private suspend fun confirmFarmScene(): Boolean {
        val provider = screenshotProvider ?: return false
        val bmp = runCatching { provider() }.getOrNull() ?: return false
        try {
            val latch = java.util.concurrent.CountDownLatch(1)
            var inFarm = false
            ocr.recognizeText(bmp) { _, blocks ->
                val full = blocks.joinToString("") { it.text.replace("\\s".toRegex(), "") }
                inFarm = listOf(
                    "一键浇水", "一键收获", "农场等级", "我的农场",
                    "好友农场", "农场小摊", "可开垦", "浇水",
                    "成熟", "作物", "收菜", "土地", "灌溉"
                ).any { full.contains(it) }
                latch.countDown()
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            return inFarm
        } finally {
            bmp.recycle()
        }
    }

    private suspend fun navigateToFarm() {
        log("导航至农场...")
        val success = doNavigateToFarm()
        if (success) {
            retryCount = 0
            transitionToInternal(FarmState.ScanCrops)
        } else {
            handleRecover(FarmState.NavigateToFarm)
        }
    }

    private suspend fun scanCrops() {
        log("扫描作物状态...")
        // v1.4.14: 双保险——未确认在农场页面则回到导航重试，杜绝在大厅乱点并虚假报告
        if (!confirmFarmScene()) {
            log("⚠当前未确认在农场页面，回到导航重试（避免在大厅误点）")
            transitionToInternal(FarmState.NavigateToFarm)
            return
        }
        val cfg = ConfigManager.getConfig()
        if (dailyOperationCount >= cfg.maxDailyOperations) {
            log("已达每日操作上限 (${cfg.maxDailyOperations})，停止")
            transitionToInternal(FarmState.Idle)
            return
        }
        printCropStrategyAdvice(cfg)
        val hasRipe = checkRipeCrops()
        if (hasRipe) transitionToInternal(FarmState.HarvestCrops)
        else transitionToInternal(FarmState.WaterCrops)
    }

    /** 最新玩法：按农场等级+时段推荐种植策略（工作日经验/周末双倍金币） */
    private fun printCropStrategyAdvice(cfg: com.farmbot.config.FarmConfig) {
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
            log("💡策略[$mode] 等级${cfg.farmLevel}(${GameRules.landCountForLevel(cfg.farmLevel)}块地)" +
                    (rec?.let { " → 推荐: ${it.name}" + (it.heroName?.let { h -> "(英雄变异:$h,不可被偷)" } ?: "") } ?: ""))
        }
    }

    private suspend fun waterCrops() {
        log("执行浇水...")
        val cfg = ConfigManager.getConfig()
        // === 最新玩法：周末双倍卡点 ===
        // 浇水会缩短成熟时间。周五临近18:00时浇水会把成熟点"提前"到双倍开启前，
        // 导致收获吃不到产量x2 → 暂缓本轮浇水，等双倍开启后再浇。
        if (cfg.weekendDoubleAware && GameRules.shouldHoldWateringForDouble(waterIntervalMin = cfg.waterIntervalMin)) {
            log("⏸卡双倍: 周五${GameRules.DOUBLE_START_HOUR}点前暂缓浇水，避免作物提前成熟错过产量x2")
            transitionToInternal(FarmState.HarvestCrops)
            return
        }
        val watered = doWaterCrops()
        dailyOperationCount += watered
        // === 融合 HonorFarmBot：OCR 倒计时动态调整浇水间隔 ===
        if (cfg.inheritCountdown && watered > 0) {
            val seconds = readCountdownViaOcr()
            if (seconds > 0) {
                val minutes = (seconds / 60).coerceAtLeast(1)
                Logger.i(TAG, "OCR倒计时=$seconds 秒 → 调整浇水间隔=$minutes 分钟")
                log("OCR倒计时 ${seconds}s → 浇水间隔 ${minutes} 分钟")
                if (minutes != cfg.waterIntervalMin) {
                    ConfigManager.save(cfg.copy(waterIntervalMin = minutes))
                }
            }
        }
        log("本轮浇水 $watered 株作物")
        transitionToInternal(FarmState.HarvestCrops)
    }

    /**
     * 通过 OCR 读取作物上方的倒计时（秒）。
     * v1.4.4 真实化：截屏后正则仅匹配 m:ss 形态，避免把金币数"120"误认为倒计时。
     */
    private suspend fun readCountdownViaOcr(): Int {
        val provider = screenshotProvider ?: return -1
        val bmp = runCatching { provider() }.getOrNull() ?: return -1
        try {
            val result = java.util.concurrent.atomic.AtomicInteger(-1)
            val latch = java.util.concurrent.CountDownLatch(1)
            ocr.recognizeText(bmp) { _, blocks ->
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

    private suspend fun harvestCrops() {
        log("执行收获...")
        val harvested = doHarvestCrops()
        dailyOperationCount += harvested
        log("本轮收获 $harvested 株作物")
        transitionToInternal(FarmState.SellOrStore)
    }

    private suspend fun sellOrStore() {
        log("处理仓库/售卖...")
        doSellOrStore()
        transitionToInternal(FarmState.VisitFriends)
    }

    private suspend fun visitFriends() {
        log("拜访好友农场...")
        doVisitFriends()
        transitionToInternal(FarmState.DailyBattle)
    }

    private suspend fun dailyBattle() {
        log("检查每日对战...")
        if (taskScheduler.isDailyBattleDone()) log("今日对战已完成，跳过")
        else doDailyBattle()
        transitionToInternal(FarmState.UpgradeDecision)
    }

    private suspend fun upgradeDecision() {
        log("执行升级决策...")
        doUpgradeDecision()
        log("农场循环完毕，安排下次浇水闹钟")
        taskScheduler.scheduleNextWatering()
        transitionToInternal(FarmState.Idle)
    }

    // ========== 实际操作的包裹层 (捕获异常) ==========

    private suspend fun doNavigateToFarm(): Boolean {
        return withErrorLog("导航至农场") { navigateToFarmInternal() } ?: false
    }
    private suspend fun checkRipeCrops(): Boolean =
        withErrorLog("检测成熟作物") { scanForRipeCrops() } ?: false
    private suspend fun doWaterCrops(): Int =
        withErrorLog("浇水") { waterCropsInternal() } ?: 0
    private suspend fun doHarvestCrops(): Int =
        withErrorLog("收获") { harvestCropsInternal() } ?: 0
    private suspend fun doSellOrStore() = withErrorLog("售卖/存储") { sellOrStoreInternal() }
    private suspend fun doVisitFriends() = withErrorLog("拜访好友") { visitFriendsInternal() }
    private suspend fun doDailyBattle() = withErrorLog("每日对战") { dailyBattleInternal() }
    private suspend fun doUpgradeDecision() = withErrorLog("升级决策") { upgradeDecisionInternal() }

    private suspend fun <T> withErrorLog(op: String, block: suspend () -> T?): T? {
        return try { block() } catch (e: Exception) {
            Logger.e(TAG, "$op 失败", e)
            log("$op 失败: ${e.message}")
            null
        }
    }

    // ========== 真实点击链路 (修复v1.3.0"不能自动点击") ==========

    /**
     * 尝试通过三级降级链点击游戏内文字按钮：
     *   1. 无障碍节点精确匹配 → 2. 无障碍节点模糊匹配 → 3. 截屏+OCR文字定位坐标点击。
     * 返回 false 表示全部层级失败（调用方应降级到模拟流程而非触发恢复循环）。
     * v1.4.3: 新增第3级 —— 王者农场主体为 SurfaceView 渲染，节点树常为空，
     *         此前仅靠节点点击必然失败，OCR坐标层才是真实可用的自动点击路径。
     */
    private suspend fun tryClickTexts(vararg texts: String): Boolean {
        if (actionExecutor == null) return false   // 执行器未注入 → 模拟模式
        val svc = com.farmbot.accessibility.FarmAccessibilityService.getInstance() ?: return false
        for (t in texts) {
            if (svc.clickByText(t)) {
                log("✓ 已点击[$t]")
                delay(RandomDelay.uniform(400, 900))
                return true
            }
        }
        for (t in texts) {
            if (svc.clickByText(t, exact = false)) {
                log("✓ 已点击[$t](模糊匹配)")
                delay(RandomDelay.uniform(400, 900))
                return true
            }
        }
        return tryClickViaOcr(svc, texts.toList())
    }

    /**
     * OCR 坐标点击降级层（v1.4.3）：
     * MediaProjection 截屏 → MLKit 中文OCR定位目标文字中心坐标 → dispatchGesture 真实点击。
     */
    private suspend fun tryClickViaOcr(
        svc: com.farmbot.accessibility.FarmAccessibilityService,
        texts: List<String>
    ): Boolean {
        val provider = screenshotProvider ?: run {
            log("⚠截图通道未就绪（未授权录屏），跳过OCR定位")
            return false
        }
        val bmp = runCatching { provider() }.getOrNull()
        if (bmp == null) {
            log("⚠截屏失败（录屏授权可能已失效）")
            return false
        }
        try {
            // v1.4.4: 单次OCR识别全部文字块后本地匹配多目标，
            // 替代此前逐词重复全屏识别（每词200~500ms，多词时延迟成倍增加）
            val latch = java.util.concurrent.CountDownLatch(1)
            var hit: OcrHelper.TextBlock? = null
            var hitText: String? = null
            ocr.recognizeText(bmp) { _, blocks ->
                val found = texts.firstNotNullOfOrNull { t ->
                    blocks.firstOrNull { b ->
                        b.text.replace("\\s".toRegex(), "").contains(t)
                    }?.let { t to it }
                }
                if (found != null) { hitText = found.first; hit = found.second }
                latch.countDown()
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            val target = hit
            val t = hitText
            if (target != null && t != null) {
                val cx = target.x + target.width / 2
                val cy = target.y + target.height / 2
                svc.click(cx, cy)
                log("✅ OCR定位[$t]($cx,$cy) 已点击")
                delay(RandomDelay.uniform(600, 1200))
                return true
            }
            log("⚠OCR未在屏幕中找到: ${texts.joinToString("/")}")
        } finally {
            bmp.recycle()
        }
        return false
    }

    /** 无障碍是否处于可用状态（执行器注入 + 服务连接） */
    private fun isRealMode(): Boolean =
            actionExecutor != null &&
            com.farmbot.accessibility.FarmAccessibilityService.getInstance() != null

    // ========== 内部实现占位（由 ActionExecutor/ScreenController 替换） ==========

    private suspend fun navigateToFarmInternal(): Boolean {
        val gamePkg = "com.tencent.tmgp.sgame"
        // v1.4.4: 游戏不在前台时经系统真实拉起游戏并等待启动（此前为纯日志模拟且恒返回成功）
        if (!com.farmbot.accessibility.ForegroundAppDetector.isTargetGameForeground.value) {
            val ctx = com.farmbot.accessibility.FarmAccessibilityService.getInstance()
            if (ctx == null) {
                log("⚠无障碍服务未连接，无法拉起游戏，进入恢复流程")
                return false
            }
            val intent = ctx.packageManager.getLaunchIntentForPackage(gamePkg)
            if (intent == null) {
                log("⚠未安装目标游戏($gamePkg)，无法导航")
                return false
            }
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            log("🚀拉起王者荣耀...")
            val launched = runCatching { ctx.startActivity(intent) }.isSuccess
            if (!launched) {
                log("⚠拉起游戏失败")
                return false
            }
            var waited = 0
            while (waited < 12000 &&
                !com.farmbot.accessibility.ForegroundAppDetector.isTargetGameForeground.value
            ) {
                delay(1000); waited += 1000
                if (!_isRunning.value) return false
            }
            if (!com.farmbot.accessibility.ForegroundAppDetector.isTargetGameForeground.value) {
                log("⚠等待游戏进入前台超时(12s)")
                return false
            }
            log("✓游戏已到前台(${waited / 1000}s)，等待大厅加载")
            delay(RandomDelay.uniform(6000, 9000))
        }
        // 游戏在前台：三级降级链点击农场入口
        log("真实导航: 点击农场入口（节点精确→模糊→OCR坐标）")
        val entered = tryClickTexts("王者农场", "周末双倍", "进入农场", "去农场", "我的农场")
        if (entered) {
            // v1.4.14: 点击入口后轮询用 OCR 确认进入农场（农场加载/OCR 识别需要时间），
            // 最多约 12s；未确认则返回 false 进入恢复流程，绝不误入后续状态。
            // v1.4.18: 「王者农场 周末双倍」为轮播横幅，X 坐标随时间变化（日志 427→568），
            // 首击可能未落准或横幅已滚动 → 轮询期间未确认则重新点击入口再确认，而非干等。
            for (i in 0 until 7) {
                delay(RandomDelay.uniform(1500, 2500))
                if (!_isRunning.value) return false
                if (confirmFarmScene()) {
                    log("✅已确认进入农场页面")
                    return true
                }
                log("尚未确认进入农场，尝试重新点击入口(${i + 1})")
                tryClickTexts("王者农场", "周末双倍", "进入农场", "去农场", "我的农场")
            }
            log("⚠点击了农场入口但未确认进入农场页面（OCR未见农场标志词，可能需手动进入）")
            return false
        }
        log("⚠农场入口点击失败（OCR通道需录屏授权；可手动进入一次农场页面后重试）")
        return false
    }

    private suspend fun scanForRipeCrops(): Boolean {
        // v1.4.4: 此前为 Random.nextDouble()<0.55 纯随机判定 → 改为真实OCR检测成熟标识
        val provider = screenshotProvider
        if (!isRealMode() || provider == null) {
            log("[模拟] 无截图/点击通道，随机判定成熟状态")
            delay(RandomDelay.uniform(400, 900))
            return kotlin.random.Random.nextDouble() < 0.55
        }
        val bmp = runCatching { provider() }.getOrNull()
        if (bmp == null) {
            log("⚠截屏失败（录屏授权可能已失效），本轮按无成熟处理")
            return false
        }
        try {
            val found = java.util.concurrent.atomic.AtomicBoolean(false)
            val latch = java.util.concurrent.CountDownLatch(1)
            ocr.recognizeText(bmp) { fullText, _ ->
                found.set(
                    listOf("一键收获", "全部收获", "可收获", "已成熟").any { fullText.contains(it) }
                )
                latch.countDown()
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            val ripe = found.get()
            log(if (ripe) "🔍OCR检测到成熟作物标识" else "🔍OCR未见成熟标识")
            return ripe
        } finally {
            bmp.recycle()
        }
    }

    private suspend fun waterCropsInternal(): Int {
        // === 真实模式：优先点"一键浇水"（Lv4+ 解锁，一次批量完成） ===
        // v1.4.4: 点击失败如实返回0交给上层恢复链，不再静默走假计数循环冒充真实进度
        if (isRealMode()) {
            return if (tryClickTexts("一键浇水", "全部浇水", "浇水")) 1 else {
                log("⚠真实模式: 未找到浇水入口，本轮不计入虚拟进度")
                0
            }
        }
        val cfg = ConfigManager.getConfig()
        var watered = 0
        // 土地数量按 S45 新模型动态计算（不再写死6~12）
        val plots = GameRules.landCountForLevel(cfg.farmLevel)
        log("[模拟] 无截图/点击通道，按地块数=$plots 走演示流程")
        for (i in 0 until plots) {
            if (!_isRunning.value || !scope.isActive) break
            if (dailyOperationCount >= cfg.maxDailyOperations) break
            log("浇水第 ${i + 1} 块地")
            delay(RandomDelay.uniform(
                cfg.operationDelayMinMs.toLong(),
                cfg.operationDelayMaxMs.toLong()
            ))
            watered++
            if (watered % 5 == 0) {
                log("拟人停顿: ${RandomDelay.gaussian(mean = 2000.0, sigma = 600.0, min = 800.0, max = 4000.0).toLong()}ms")
                delay(RandomDelay.gaussian(mean = 2000.0, sigma = 600.0, min = 800.0, max = 4000.0).toLong())
            }
        }
        return watered
    }

    private suspend fun harvestCropsInternal(): Int {
        // === 真实模式：优先点"一键收获"（Lv8+ 解锁） ===
        // v1.4.4: 点击失败如实返回0，不再随机编造收获株数
        if (isRealMode()) {
            return if (tryClickTexts("一键收获", "全部收获", "收获")) 1 else {
                log("⚠真实模式: 未找到收获入口，本轮不计入虚拟进度")
                0
            }
        }
        var harvested = 0
        val ripe = kotlin.random.Random.nextInt(0, 6)
        log("[模拟] 演示收获 $ripe 株")
        for (i in 0 until ripe) {
            if (!_isRunning.value || !scope.isActive) break
            log("收获第 ${i + 1} 株作物")
            delay(RandomDelay.gaussian(mean = 700.0, sigma = 200.0, min = 300.0, max = 1500.0).toLong())
            harvested++
        }
        return harvested
    }

    private suspend fun sellOrStoreInternal() {
        val cfg = ConfigManager.getConfig()
        // === 真实模式：优先点击出售入口 ===
        // v1.4.4: 点击失败明确提示，不再无声跳过
        if (isRealMode()) {
            if (!tryClickTexts("一键出售", "全部出售", "出售")) {
                log("⚠真实模式: 未找到出售入口，跳过真实出售动作")
            }
        }
        // === 最新玩法：囤货策略 ===
        // 双倍只加产量不加单价 → 高价值作物(变异/英雄果实)囤仓库，等周末双倍产量翻倍后再卖
        if (cfg.hoardUntilWeekend && !GameRules.isWeekendDouble()) {
            log("📦囤货模式: 非双倍期暂不出售高价值作物，距下次双倍约 ${GameRules.minutesUntilNextDouble() / 60} 小时")
            log("仓库容量检查: ${if (cfg.farmLevel >= 30) "已扩容400格" else "注意30级扩容前防溢出"}")
            delay(RandomDelay.uniform(500, 1200))
            return
        }
        if (cfg.hoardUntilWeekend && GameRules.isWeekendDouble()) {
            log("💰周末双倍进行中(产量x2)，囤积作物全部出售收益最大化")
        }
        // === S45 新增: 变异果实培育台决策 ===
        // 40级解锁培育台，高倍变异果实可先留作"主材"转移词条给目标作物，
        // 最高840倍四词条叠加 → 强变异果实优先喂培育台而非直接卖
        if (cfg.farmLevel >= GameRules.BREEDING_UNLOCK_LEVEL) {
            log("🧬培育台(S45): Lv${cfg.farmLevel}已解锁${GameRules.BREEDING_NAME}，" +
                    "高倍变异果实建议留存作主材洗词条(上限${GameRules.MAX_MUTATION_STACK}词条/${GameRules.MAX_COMBINED_MULTIPLIER}倍)，普通作物正常出售")
        }
        when (cfg.sellMode) {
            com.farmbot.config.FarmConfig.SellMode.NONE -> log("售卖策略: NONE，跳过")
            com.farmbot.config.FarmConfig.SellMode.SELL_ALL -> log("售卖策略: 全部卖出")
            com.farmbot.config.FarmConfig.SellMode.SELL_MAXED -> log("售卖策略: 仅卖满级")
        }
        if (cfg.farmLevel >= 5) {
            val stallBonus = (cfg.farmLevel * 5).coerceAtMost(GameRules.STALL_MAX_LEVEL * 5)
            log("小摊加成: 等级${cfg.farmLevel}，售价+${stallBonus}%(每级5%，满级${GameRules.STALL_MAX_LEVEL}级+195%)")
        }
        delay(RandomDelay.uniform(500, 1200))
    }

    private suspend fun visitFriendsInternal() {
        val cfg = ConfigManager.getConfig()
        if (!cfg.enableSteal && !cfg.enableBless) {
            log("偷菜与祝福均已关闭，跳过好友拜访")
            return
        }
        // === 最新玩法1: 偷菜保护时间 0:00~8:00 ===
        if (cfg.enableSteal && cfg.respectStealProtection && GameRules.isStealProtectionTime()) {
            log("🌙保护时间(0点~8点)无法偷菜，本轮仅执行祝福")
        }
        // === 最新玩法2: 每日限额管控 ===
        val stealRemain = DailyCounters.remainingSteal(cfg.stealDailyLimit)
        val blessRemain = DailyCounters.remainingBless(cfg.blessDailyLimit)
        if (stealRemain <= 0) log("⚠今日偷菜已达上限(${cfg.stealDailyLimit})，仅执行祝福")
        if (blessRemain <= 0) log("⚠今日祝福已达上限(${cfg.blessDailyLimit})，仅执行偷菜")
        if (stealRemain <= 0 && blessRemain <= 0) {
            log("今日社交操作全部达上限，跳过好友拜访")
            return
        }
        // === 最新玩法3: 偷菜/祝福顺序 ===
        // 先偷后祝(默认): 偷走普通作物后祝福触发病块变异，变异留给下轮二次偷取
        // 先祝后偷: 祝福立即触发变异，马上偷取高价值变异作物直接落袋
        var visited = 0
        val canStealNow = cfg.enableSteal && stealRemain > 0 &&
                !(cfg.respectStealProtection && GameRules.isStealProtectionTime())
        val canBlessNow = cfg.enableBless && blessRemain > 0
        val stealFirst = cfg.stealBeforeBless
        // v1.4.7: 有截图+点击通道时走真实 OCR 导航（社交→好友列表→逐个拜访）；
        // 演示路径不再写入每日计数（此前模拟会虚假消耗真实配额）
        if (isRealMode() && screenshotProvider != null) {
            visited = visitFriendsViaOcr(canStealNow, canBlessNow, stealRemain, blessRemain)
            log("好友拜访完成: 顺序[${if (stealFirst) "先偷后祝" else "先祝后偷"}] 真实处理 $visited 位好友农场")
            return
        }
        val phases = if (stealFirst) listOf("steal", "bless") else listOf("bless", "steal")
        for (phase in phases) {
            when (phase) {
                "steal" -> if (canStealNow) {
                    val n = minOf(kotlin.random.Random.nextInt(3, 8), stealRemain)
                    log("[模拟] 偷菜阶段演示 $n 位好友（无截图/点击通道，不计入每日次数）")
                    for (i in 0 until n) {
                        if (!_isRunning.value || !scope.isActive) break
                        log("[模拟]🥬偷菜 ${i + 1}/$n")
                        delay(RandomDelay.uniform(1000, 2200))
                    }
                }
                "bless" -> if (canBlessNow) {
                    val n = minOf(kotlin.random.Random.nextInt(2, 6), blessRemain)
                    log("[模拟] 祝福阶段演示 $n 位好友（无截图/点击通道，不计入每日次数）")
                    for (i in 0 until n) {
                        if (!_isRunning.value || !scope.isActive) break
                        log("[模拟]🙏祝福 ${i + 1}/$n 变异概率+${GameRules.MUTATION_BLESS_BONUS_MIN}%~${GameRules.MUTATION_BLESS_BONUS_MAX}%")
                        delay(RandomDelay.uniform(1000, 2200))
                    }
                }
            }
        }
        log("好友拜访完成: 顺序[${if (stealFirst) "先偷后祝" else "先祝后偷"}] 演示路径不写入每日计数")
    }

    /**
     * v1.4.7: 好友拜访真实链路——OCR 定位"社交/好友"入口 → 好友列表逐个点"拜访" →
     * 在好友农场内尝试偷菜/祝福 → "返回"回列表继续。任何环节失败如实报告并停止，
     * 每日计数只在真实点击成功后写入。
     */
    private suspend fun visitFriendsViaOcr(
        canSteal: Boolean, canBless: Boolean, stealRemain: Int, blessRemain: Int
    ): Int {
        // v1.4.13: 好友入口关键词以「社交」按钮优先（农场页右下角为权威入口）。
        // 原顺序"好友列表/好友/社交"会让 OCR 优先命中页面上的"好友列表"标题等非按钮文本
        // （.contains 子串匹配按顺序取首个命中），导致点到标题中心而实际无效、流程空转。
        if (!tryClickTexts("社交", "好友列表", "好友农场", "好友")) {
            log("⚠真实模式: 未找到好友入口，跳过本轮偷菜/祝福（不计入每日次数）")
            return 0
        }
        delay(RandomDelay.uniform(1800, 3000))
        var stealLeft = stealRemain
        var blessLeft = blessRemain
        var visited = 0
        for (i in 0 until 8) {
            if (!_isRunning.value || !scope.isActive) break
            if ((!canSteal || stealLeft <= 0) && (!canBless || blessLeft <= 0)) {
                log("今日偷菜/祝福配额已用完，停止拜访")
                break
            }
            if (!tryClickTexts("拜访", "进入好友农场", "去TA的农场", "TA的农场", "进入农场")) {
                log("⚠真实模式: 好友列表无更多拜访入口，共进入 $visited 位")
                break
            }
            delay(RandomDelay.uniform(2200, 3600))
            var acted = false
            // v1.4.12: 偷菜按钮实际文案为"拿取"（靠近发光成熟作物点击拿取），补入关键词
            if (canSteal && stealLeft > 0 && tryClickTexts("拿取", "可偷", "偷菜", "摘取")) {
                DailyCounters.addSteal(); stealLeft--; acted = true
                log("✓真实偷菜完成(今日剩余$stealLeft)")
            }
            if (canBless && blessLeft > 0 && tryClickTexts("祝福", "祈福")) {
                DailyCounters.addBless(); blessLeft--; acted = true
                log("✓真实祝福完成(今日剩余$blessLeft)")
            }
            visited++
            if (!acted) log("⚠真实模式: 第${visited}位好友农场未找到偷菜/祝福入口")
            if (!tryClickTexts("返回", "好友列表")) {
                log("⚠真实模式: 无法返回好友列表，终止本轮拜访")
                break
            }
            delay(RandomDelay.uniform(1200, 2200))
        }
        // 尽力回到自己农场，失败交由后续导航状态兜底
        tryClickTexts("我的农场", "返回农场", "农场")
        return visited
    }

    private suspend fun dailyBattleInternal() {
        val cfg = ConfigManager.getConfig()
        if (!cfg.dailyBattle) { log("配置关闭对战，跳过"); return }
        // === 最新玩法: 对局任务奖励在周末双倍期翻倍 → 优先周末完成 ===
        if (GameRules.isWeekendDouble()) {
            log("💰当前为周末双倍期，本次对局的农场币与经验奖励x2")
            // === S45 流光沙漏: 双倍期效果减半，建议工作日使用 ===
            log("⏳沙漏提醒(S45): 双倍期流光沙漏效果减半(全场仅-${GameRules.hourglassEffectiveMinutes()}分钟)，建议留到工作日使用")
        }
        // v1.4.4: 真实模式点击对战入口后即标记完成并告知用户手动收尾；
        // 不再挂机 delay(surrenderAfterSec) 假装打完整局（对局自动化涉及复杂游戏AI，超出范围）
        if (isRealMode()) {
            log("🎮真实模式: 尝试打开对战入口（投降秒数=${cfg.surrenderAfterSec}s）")
            val clicked = tryClickTexts("对战模式", "对战", "开始匹配")
            if (clicked) {
                log("✓已点击对战入口。请在约${cfg.surrenderAfterSec}s后手动投降领奖（自动打对局暂未接入），今日对战已标记完成")
                taskScheduler.markDailyBattleDone()
            } else {
                log("⚠未找到对战入口，跳过每日对战（不标记完成，下轮重试）")
            }
            return
        }
        log("[模拟] 选择模式: ${if (cfg.newMode) "新模式(S43/S44)" else "经典模式"}")
        delay(RandomDelay.uniform(2500, 4000))
        log("[模拟] 等待对局开始")
        delay(5000)
        log("[模拟] 对局进行中 ${cfg.surrenderAfterSec}s...")
        delay(cfg.surrenderAfterSec * 1000L)
        log("[模拟] 投降并领取奖励")
        delay(2500)
        taskScheduler.markDailyBattleDone()
    }

    private suspend fun upgradeDecisionInternal() {
        val cfg = ConfigManager.getConfig()
        log("优先级: ${cfg.upgradePriority.name}, 开地: ${cfg.expandAlways || cfg.upgradeExpandAfter}, 完美模式: ${cfg.perfectMode}")
        // === 最新玩法: 按等级解锁表给出升级建议 ===
        val lands = GameRules.landCountForLevel(cfg.farmLevel)
        val next = GameRules.nextUnlock(cfg.farmLevel)
        log("农场等级${cfg.farmLevel}: 当前${lands}块地" +
                (next?.let { " → Lv${it.level}解锁: ${it.feature}" } ?: " (已满级)"))
        if (cfg.farmLevel >= 4 && cfg.farmLevel < 8) {
            log("💡提示: 4级已解锁一键浇水，8级将解锁一键收获")
        }
        delay(RandomDelay.uniform(600, 1400))
        // v1.4.7: 有通道时尝试真实开地/升级入口点击，不再无条件 [模拟]
        // v1.4.11: 关键词补「开垦/可开垦」——游戏告示牌显示"可开垦"，仅凭"开地/购买土地/
        // 解锁土地/扩建"无法命中（.contains 子串匹配），导致升级/开地功能不可用
        if (isRealMode() && screenshotProvider != null) {
            if (tryClickTexts("开垦", "可开垦", "开地", "购买土地", "解锁土地", "扩建")) {
                delay(RandomDelay.uniform(1000, 2000))
                if (tryClickTexts("确定", "确认", "购买")) {
                    log("✓已执行开地/升级确认")
                } else {
                    log("✓已点击开地/升级入口（未见确认弹窗，可能已直接生效）")
                }
            } else {
                log("⚠真实模式: 当前页面未找到开地/升级入口（需手动进入对应页面后重试）")
            }
            return
        }
        log("[模拟] 升级/开地演示（无截图/点击通道）")
        delay(1000)
    }

    private fun randomDelay(min: Int, max: Int): Long = RandomDelay.uniform(min, max).toLong()

    // ============ 日志 ============

    private fun log(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
            .format(java.util.Date())
        val entry = "$ts $msg"
        val cur = _logs.value.toMutableList()
        cur.add(entry)
        if (cur.size > 500) cur.removeAt(0)
        _logs.value = cur
        Log.d(TAG, msg)
        Logger.i(TAG, msg)
    }

    fun getLogs(): List<String> = _logs.value.toList()
}
