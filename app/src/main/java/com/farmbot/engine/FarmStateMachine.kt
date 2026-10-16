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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong

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
    @Volatile private var activeStateJob: Job? = null
    private val transitionGeneration = AtomicLong()

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
        _isRunning.value = false
        transitionGeneration.incrementAndGet()
        activeStateJob?.cancel()
        scope.launch { mutex.withLock {
            log("状态机暂停")
            transitionToInternal(FarmState.Idle)
        }}
    }

    fun stop() {
        _isRunning.value = false
        transitionGeneration.incrementAndGet()
        activeStateJob?.cancel()
        scope.launch { mutex.withLock {
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

        val generation = transitionGeneration.incrementAndGet()
        scope.launch {
            delay(RandomDelay.uniform(300, 1200))
            if (generation != transitionGeneration.get()) return@launch
            safeExecuteWithTimeout(next)
        }
    }

    private suspend fun safeExecuteWithTimeout(state: FarmState) {
        val job = currentCoroutineContext()[Job]
        activeStateJob = job
        try {
            withTimeout(STATE_TIMEOUT_MS) {
                executeState(state)
            }
        } catch (t: TimeoutCancellationException) {
            if (_isRunning.value && _currentState.value == state) {
                Logger.w(TAG, "State '${state.displayName()}' 超时(${STATE_TIMEOUT_MS}ms)，强制中断并恢复")
                log("状态超时，进入恢复流程")
                handleRecover(state)
            }
        } catch (t: CancellationException) {
            throw t
        } finally {
            if (activeStateJob === job) activeStateJob = null
        }
    }

    private suspend fun executeState(state: FarmState) = mutex.withLock {
        if (!_isRunning.value) return@withLock

        try {
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
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Logger.e(TAG, "executeState 异常 in ${state.displayName()}", t)
            log("异常: ${t.message}")
            handleRecover(state)
        }
    }

    fun destroy() {
        _isRunning.value = false
        transitionGeneration.incrementAndGet()
        activeStateJob?.cancel()
        runCatching { ocr.shutdown() }
        scope.cancel()
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
    private suspend fun confirmFarmScene(): Boolean {
        val provider = screenshotProvider ?: return false
        for (attempt in 0 until 3) {
            val bmp = runCatching { provider() }.getOrNull() ?: return false
            try {
                val latch = java.util.concurrent.CountDownLatch(1)
                var inFarm = false
                ocr.recognizeText(bmp) { _, blocks ->
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
    private suspend fun detectFarmLevel(): Int? {
        val provider = screenshotProvider ?: return null
        val bmp = runCatching { provider() }.getOrNull() ?: return null
        return try {
            val out = java.util.concurrent.atomic.AtomicInteger(-1)
            val latch = java.util.concurrent.CountDownLatch(1)
            ocr.recognizeText(bmp) { _, blocks ->
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
        // 避免在大厅乱点并虚假报告
        if (!confirmFarmScene()) {
            log("⚠当前未确认在农场页面，回到导航重试（避免在大厅误点）")
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
            log("📈OCR检测农场等级=Lv$detected，已更新配置（原$previousLevel）")
        }
        if (dailyOperationCount >= cfg.maxDailyOperations) {
            log("已达每日操作上限 (${cfg.maxDailyOperations})，停止")
            transitionToInternal(FarmState.Idle)
            return
        }
        // v1.4.35: 流程控制——默认必须优先完成自家农场全部操作(种植/浇水/收获/出售)后才进入好友农场；
        // 仅当用户显式启用"优先偷菜"(prioritySteal) 时，才允许跳过自家农场、优先拜访好友农场偷菜。
        if (cfg.prioritySteal) {
            log("⚡优先偷菜模式已启用：跳过自家农场操作（种植/浇水/收获），优先拜访好友农场偷菜")
            transitionToInternal(FarmState.VisitFriends)
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
     * v1.4.32: OCR 中文识别常丢失首字/某些字（实测"一键务农"被识别为"键务农"）。
     * 当目标是"一键务农"时，折叠为"键务农"变体再匹配，避免 OCR 漏字导致找不到按钮。
     */
    private fun ocrTextVariant(clean: String, target: String): Boolean {
        if (target == "一键务农") return clean.contains("键务农")
        return false
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
                        val clean = b.text.replace("\\s".toRegex(), "")
                        clean.contains(t) || ocrTextVariant(clean, t)
                    }?.let { t to it }
                }
                if (found != null) { hitText = found.first; hit = found.second }
                latch.countDown()
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            val target = hit
            val t = hitText
            if (target != null && t != null) {
                // OcrHelper.TextBlock 的 x/y 已经是边界框中心，不可再次叠加半宽/半高。
                var cx = target.x
                var cy = target.y
                val scaleX = bmp.width / 1920f
                val scaleY = bmp.height / 1080f
                // v1.4.20: 「王者农场 周末双倍」为横幅卡片，OCR 只命中"王者农场"文字块，
                // 其中心偏右（实测文字中心约 (572,842)，而横幅可点区中心约 (486,810)）。
                // 若按文字中心点击会落到"对战"区域误入模式选择页。对入口横幅做向左校正，
                // 命中横幅主体可点区。偏移量基于真机/模拟器大厅实测的横幅卡片几何关系。
                // v1.4.25: 此前仅 cx-=150（过度修正，且未调 y），实测点 (422,842) 偏离卡片中心
                // (486,810)。按注释实测值改为 (cx-86, cy-32)，命中横幅主体。
                if (t.contains("王者农场") || t.contains("周末双倍")) {
                    if (cx > 300 * scaleX) {
                        cx -= (86 * scaleX).toInt()
                        cy -= (32 * scaleY).toInt()
                    }
                }
                // v1.4.28: 「一键务农」为蓝色圆形图标+下方文字标签，OCR 命中标签文字，可点的圆图标在其上方。
                // 点标签中心会落在图标下方导致面板不开（实测日志：点(1330,688)后仍停留在农场主界面）。
                // 向上偏移命中圆形图标。
                // v1.4.33: 实测文字中心(1330,688)、图标中心约(1290,630)，按 `cx-40, cy-58` 命中圆形按钮主体。
                if (t.contains("一键务农")) {
                    if (cy > 200 * scaleY) {
                        cx -= (40 * scaleX).toInt()
                        cy -= (58 * scaleY).toInt()
                    }
                }
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
        // v1.4.22: 若已身处农场（含新手引导态），无需再找大厅入口横幅，直接进入扫描。
        // 此前无论游戏是否已在农场都先点"王者农场"横幅 → 已在农场时横幅不存在，
        // OCR 必然找不到 → 反复重试→升级恢复流程（用户反馈"已进农场仍狂刷重试"）。
        if (confirmFarmScene()) {
            log("✅检测到已身处农场（含引导态），跳过入口点击")
            return true
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
            // v1.4.36: 农场主界面并无"一键收获/成熟作物"等文字（它们位于一键务农设置面板内），
            // OCR 恒检测不到 → 成熟检测恒 false，导致"成熟不触发收获"。改为：
            //   ① 优先 OCR 检测成熟字样（兼容少数界面确有该词）；
            //   ② 用图像识别 findMatureCrops 检测金色成熟作物；
            //   ③ 仍无则兜底返回 true——交给 doOneKeyFarming(一键务农) 统一处理，
            //      未成熟会被游戏自动跳过，不会误操作。
            val found = java.util.concurrent.atomic.AtomicBoolean(false)
            val latch = java.util.concurrent.CountDownLatch(1)
            ocr.recognizeText(bmp) { fullText, _ ->
                found.set(
                    listOf("一键收获", "全部收获", "成熟作物", "可收获").any { fullText.contains(it) }
                )
                latch.countDown()
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            if (found.get()) {
                log("🔍OCR检测到成熟作物标识")
                return true
            }
            // OCR 无成熟字样 → 图像识别金色成熟作物
            val mature = matcher.findMatureCrops(bmp)
            if (mature.isNotEmpty()) {
                log("🔍图像识别到 ${mature.size} 处成熟作物，触发收获流程")
                return true
            }
            // v1.4.36: 成熟作物可能处于视觉遮挡/金色阈值边缘，图像识别漏检。
            // 兜底返回 true——交给 doOneKeyFarming(一键务农) 统一处理，
            // 游戏对未成熟作物会自动跳过，不会误操作；若真无作物，一键务农也只会空跑一次。
            log("🔍未检测到成熟标识，兜底交由一键务农处理")
            return true
        } finally {
            bmp.recycle()
        }
    }

    private suspend fun waterCropsInternal(): Int {
        // === 真实模式：优先点"一键浇水"（Lv4+ 解锁，一次批量完成） ===
        // v1.4.4: 点击失败如实返回0交给上层恢复链，不再静默走假计数循环冒充真实进度
        // v1.4.19: 先确认在农场才执行，杜绝在大厅误命"浇水/收获"等宣传文字假成功
        if (isRealMode()) {
            if (!confirmFarmScene()) {
                log("⚠未确认在农场页面，本次浇水不计入（避免大厅误点）")
                return 0
            }
            // v1.4.26: 浇水/收获/种植统一走"一键务农"（需靠近雕像才显示）：点"一键务农"→设置面板勾选→确定即自动执行。
            // 此前找"一键浇水/浇水壶"文字（农场主界面并不存在，仅在一键务农面板内）与逐株OCR均不可用。
            return doOneKeyFarming()
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
        // v1.4.19: 先确认在农场才执行，防止大厅误命"收获"文字假成功
        if (isRealMode()) {
            if (!confirmFarmScene()) {
                log("⚠未确认在农场页面，本次收获不计入（避免大厅误点）")
                return 0
            }
            // v1.4.26: 收获与浇水同源，统一走"一键务农"（一次即可覆盖浇水/收获/种植）
            return doOneKeyFarming()
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

    /**
     * v1.4.26: 浇水/收获/种植统一走"一键务农"。王者农场主界面并无独立的"一键浇水/一键收获"文字按钮
     * （它们位于"一键务农"设置面板内）；作物为纯画面、无 OCR 文本，逐株定位不可用。
     * v1.4.36: 真机实测纠正——打开设置面板的是**一键务农按钮右上方的齿轮(设置)图标**，点按钮本体不弹出面板。
     * 因此正确路径为：靠近雕像→点齿轮→面板（一键浇水/一键收获/一键种植三项默认勾选）→点确定。
     * 未靠近雕像(找不到按钮)、面板未打开或未确认时如实返回 0，不假成功。
     */
    private suspend fun doOneKeyFarming(): Int {
        // 确保"一键务农"可见（需靠近雕像才显示），否则先走位逼近
        if (!ocrContainsAny("一键务农", "键务农") && !moveTowardsStatue()) {
            log("⚠未找到[一键务农]（需靠近雕像才显示），本轮跳过")
            return 0
        }
        delay(RandomDelay.uniform(700, 1300))
        // v1.4.37: 一键种植使用当前已选种子 → 确认前先按种植配置选种（限频60s，失败不阻断）
        ensureSeedSelected()
        // 点击齿轮(设置)打开"一键务农设置"面板
        if (!openOneKeySettingsPanel()) {
            log("⚠未能打开一键务农设置面板")
            return 0
        }
        delay(RandomDelay.uniform(800, 1500))
        // 面板默认勾选"一键浇水/一键收获/一键种植"，点确定即统一执行
        if (tryClickTexts("确定", "确认")) {
            log("✓已确认一键务农：一键收获 → 一键种植 → 一键浇水")
            delay(RandomDelay.uniform(2500, 4000))
            return 1
        }
        log("⚠一键务农设置面板未找到确定按钮")
        return 0
    }

    /**
     * 打开"一键务农设置"面板：
     * 一键务农按钮右上方有一个齿轮(设置)图标，点击才弹出面板（点按钮本体不弹出）。
     * 齿轮无文字，需基于一键务农文字相对偏移（实测偏移 +90,-125 @1920x1080），OCR 定位失败则兜底绝对坐标。
     * 点击后轮询 OCR 确认标题"一键务农设置/请选择工作内容"出现。
     */
    private suspend fun openOneKeySettingsPanel(): Boolean {
        val svc = com.farmbot.accessibility.FarmAccessibilityService.getInstance() ?: return false
        val provider = screenshotProvider ?: return false
        val bmp = runCatching { provider() }.getOrNull() ?: return false
        val scaleX = bmp.width / 1920f
        val scaleY = bmp.height / 1080f
        var gearX = (1350 * scaleX).toInt()
        var gearY = (550 * scaleY).toInt()
        var byOcr = false
        try {
            val latch = java.util.concurrent.CountDownLatch(1)
            ocr.recognizeText(bmp) { _, blocks ->
                val t = blocks.firstOrNull {
                    val clean = it.text.replace("\\s".toRegex(), "")
                    clean.contains("一键务农") || clean.contains("键务农")
                }
                if (t != null) {
                    gearX = t.x + (90 * scaleX).toInt()
                    gearY = t.y - (125 * scaleY).toInt()
                    byOcr = true
                }
                latch.countDown()
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            bmp.recycle()
        }
        val cx = gearX + kotlin.random.Random.nextInt(-8, 9)
        val cy = gearY + kotlin.random.Random.nextInt(-8, 9)
        svc.click(cx, cy)
        log(if (byOcr) "✅OCR定位一键务农→点齿轮($cx,$cy)" else "⚠OCR未定位一键务农，兜底点齿轮($cx,$cy)")
        // 等待面板打开，确认标题出现
        for (i in 0 until 6) {
            delay(RandomDelay.uniform(600, 1000))
            if (ocrContainsAny("一键务农设置", "请选择工作内容")) {
                log("✅一键务农设置面板已打开")
                return true
            }
        }
        return false
    }

    /** OCR 全屏检测任一关键词是否存在（不点击）。用于确认面板/文字出现。 */
    private suspend fun ocrContainsAny(vararg keywords: String): Boolean {
        val provider = screenshotProvider ?: return false
        val bmp = runCatching { provider() }.getOrNull() ?: return false
        try {
            val latch = java.util.concurrent.CountDownLatch(1)
            var hit = false
            ocr.recognizeText(bmp) { _, blocks ->
                val full = blocks.joinToString("") { it.text.replace("\\s".toRegex(), "") }
                hit = keywords.any { kw -> full.contains(kw) || blocks.any { it.text.replace("\\s".toRegex(), "").contains(kw) } }
                latch.countDown()
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            return hit
        } catch (t: Throwable) {
            return false
        } finally {
            bmp.recycle()
        }
    }

    private data class OcrHit(
        val keyword: String,
        val x: Int,
        val y: Int,
        val imageWidth: Int,
        val imageHeight: Int
    )

    /** OCR 定位指定关键词的文本块中心坐标。找不到返回 null。 */
    private suspend fun ocrFindPos(
        keyword: String,
        filter: ((OcrHelper.TextBlock, Int, Int) -> Boolean)? = null
    ): OcrHit? = ocrFindPos(listOf(keyword), filter)

    /** OCR 按关键词优先级定位第一个文本块，可按截图尺寸过滤位置。 */
    private suspend fun ocrFindPos(
        keywords: List<String>,
        filter: ((OcrHelper.TextBlock, Int, Int) -> Boolean)? = null
    ): OcrHit? {
        val provider = screenshotProvider ?: return null
        val bmp = runCatching { provider() }.getOrNull() ?: return null
        var hit: OcrHit? = null
        try {
            val latch = java.util.concurrent.CountDownLatch(1)
            ocr.recognizeText(bmp) { _, blocks ->
                val found = keywords.firstNotNullOfOrNull { keyword ->
                    blocks.firstOrNull {
                        val clean = it.text.replace("\\s".toRegex(), "")
                        (clean.contains(keyword) || ocrTextVariant(clean, keyword)) &&
                                (filter == null || filter(it, bmp.width, bmp.height))
                    }?.let { keyword to it }
                }
                if (found != null) {
                    hit = OcrHit(
                        keyword = found.first,
                        x = found.second.x,
                        y = found.second.y,
                        imageWidth = bmp.width,
                        imageHeight = bmp.height
                    )
                }
                latch.countDown()
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            return hit
        } catch (t: Throwable) {
            return null
        } finally {
            bmp.recycle()
        }
    }

    /** 上次选种时间：一键务农每轮浇水/收获都会触发，限频避免反复开关种子面板。 */
    @Volatile private var lastSeedSelectAt = 0L

    /**
     * v1.4.37: 右下角种植按钮未选种子时，按助手配置选种。
     * 优先级：双倍期内→周末作物(金币)；双倍前卡点窗口→卡双倍(周五18点成熟)；平日→平日作物(经验)。
     * 流程：OCR定位右下角"种植"按钮→点开种子面板→点击目标作物→确定。
     * 一键种植使用当前已选种子，故须在"一键务农"确认前完成选种。
     * 找不到种植按钮/目标作物时如实返回 false（一键种植沿用上次所选种子），不阻断主流程。
     */
    private suspend fun ensureSeedSelected(): Boolean {
        val cfg = ConfigManager.getConfig()
        val now = System.currentTimeMillis()
        if (now - lastSeedSelectAt < 60_000L) return true   // 60s 内已选过，跳过（含上次失败限频）
        lastSeedSelectAt = now
        val (cropName, reason) = resolveTargetCrop(cfg)
        if (cropName.isNullOrBlank()) {
            log("⚠种植选种: 无法解析目标作物（等级${cfg.farmLevel}无可种作物），沿用当前种子")
            return false
        }
        log("🌱种植配置[$reason] → 目标作物: $cropName")
        val svc = com.farmbot.accessibility.FarmAccessibilityService.getInstance() ?: return false
        // 右下角"种植"按钮（限定画面下半部，避免命中大厅/面板内同名干扰文字）
        val plantPos = ocrFindPos("种植") { block, _, height -> block.y > height / 2 } ?: run {
            log("⚠未找到右下角[种植]按钮（可能无空地/不在此页面），本次不选种")
            return false
        }
        svc.click(plantPos.x, plantPos.y)
        log("🌱已点击种植按钮(${plantPos.x},${plantPos.y})，等待种子面板")
        delay(RandomDelay.uniform(1200, 2000))
        // 面板内点击目标作物种子（排除右下角按钮自身）
        var picked = false
        for (i in 0 until 3) {
            val exclusionRadius = (120 * plantPos.imageWidth / 1920f).toInt().coerceAtLeast(60)
            val seedPos = ocrFindPos(cropName) { block, _, height ->
                !(block.y > height / 2 && kotlin.math.abs(block.x - plantPos.x) < exclusionRadius)
            }
            if (seedPos != null) {
                svc.click(seedPos.x, seedPos.y)
                log("✓已选择种子[$cropName](${seedPos.x},${seedPos.y})")
                picked = true
                break
            }
            delay(RandomDelay.uniform(700, 1200))
        }
        if (!picked) {
            log("⚠种子面板未找到[$cropName]（可能等级未解锁或面板布局差异），本次不选种")
            return false
        }
        delay(RandomDelay.uniform(600, 1100))
        // 面板确认按钮（如有）；无则面板随后续操作自动应用已选种子
        if (tryClickTexts("确定", "确认")) {
            log("✓已确认选种")
        }
        return true
    }

    /**
     * v1.4.37: 按种植配置解析目标作物。
     *  - 周末双倍期(周五18点~周日)：用户配置周末作物，缺省自动推荐金币最高作物；
     *  - 卡双倍窗口(开启周末双倍策略且距下次双倍≤32小时)：选生长时长最接近剩余时间的作物，
     *    令其恰在周五18:00双倍开启时成熟，收获即吃产量x2；
     *  - 平日(周一~周四)：用户配置平日作物，缺省自动推荐经验最高作物。
     * @return 作物名 to 原因说明（作物名为 null 表示无可用作物）
     */
    private fun resolveTargetCrop(cfg: com.farmbot.config.FarmConfig): Pair<String?, String> {
        val now = java.util.Calendar.getInstance()
        if (GameRules.isWeekendDouble(now)) {
            val configured = availableConfiguredCrop(cfg.weekendCrop, cfg.farmLevel)
            val name = configured
                ?: CropDatabase.recommend(cfg.farmLevel, CropDatabase.Strategy.COIN_FIRST)?.name
            return name to if (configured != null) "周末双倍期: 自定义作物" else "周末双倍期: 金币作物"
        }
        if (cfg.weekendDoubleAware) {
            val hoursUntil = GameRules.minutesUntilNextDouble(now) / 60.0
            if (hoursUntil > 0.5 && hoursUntil <= 32.0) {
                val pick = CropDatabase.closestToGrowHours(cfg.farmLevel, hoursUntil)
                if (pick != null) {
                    return pick.name to "卡双倍: 距周五${GameRules.DOUBLE_START_HOUR}点约${hoursUntil.toInt()}h → ${pick.name}(${pick.growHours}h成熟)"
                }
            }
        }
        val configured = availableConfiguredCrop(cfg.weekdayCrop, cfg.farmLevel)
        val name = configured
            ?: CropDatabase.recommend(cfg.farmLevel, CropDatabase.Strategy.EXP_FIRST)?.name
        return name to if (configured != null) "平日: 自定义作物" else "平日: 经验作物"
    }

    private fun availableConfiguredCrop(name: String, farmLevel: Int): String? {
        val normalized = name.trim().takeIf { it.isNotEmpty() } ?: return null
        return CropDatabase.find(normalized)
            ?.takeIf { it.unlockLevel <= farmLevel }
            ?.name
    }

    /**
     * v1.4.37: 走到目标地块——朝 OCR 目标文字推摇杆让角色靠近。
     * 开垦/祝福/偷菜/单块操作均需走到对应地块才可操作：相机跟随角色，
     * 目标文字相对画面中心的偏移即角色需要移动的方向；每步推进后复查，
     * 文字靠近画面中心（角色已贴近）或从画面消失（走到身前/被遮挡）即视为到达。
     * @return true=已接近/到达目标；false=目标未找到或步数用尽（如实返回，不假成功）
     */
    private suspend fun walkNearText(
        keywords: List<String>,
        maxSteps: Int = 6,
        nearRadius: Int = 200
    ): Boolean {
        val svc = com.farmbot.accessibility.FarmAccessibilityService.getInstance() ?: return false
        if (screenshotProvider == null) return false
        val label = keywords.joinToString("/")
        for (step in 0 until maxSteps) {
            if (!_isRunning.value || !scope.isActive) return false
            val pos = ocrFindPos(keywords)
            if (pos == null) {
                // 目标文字消失：可能已贴近身前或视角遮挡，视为到达，交给后续点击校验
                if (step > 0) {
                    log("🚶走位到[$label]: 目标已离开画面，交由后续点击确认")
                    return true
                }
                log("🚶走位到[$label]: 当前画面未找到目标")
                return false
            }
            val scaleX = pos.imageWidth / 1920f
            val scaleY = pos.imageHeight / 1080f
            val moveScale = minOf(scaleX, scaleY)
            val joyX = (300 * scaleX).toInt()
            val joyY = (820 * scaleY).toInt()
            val cx = pos.imageWidth / 2
            val cy = pos.imageHeight / 2
            val dx = pos.x - cx
            val dy = pos.y - cy
            val distance = kotlin.math.hypot(dx.toDouble(), dy.toDouble())
            if (distance <= nearRadius * moveScale) {
                log("🚶走位到[${pos.keyword}]: 目标已在角色附近，无需移动")
                return true
            }
            // 摇杆推动方向与目标偏移同向，幅度按距离缩放(限幅避免推过头)
            val pushDistance = 260.0 * moveScale
            val scale = pushDistance / distance
            val maxPush = (280 * moveScale).toInt().coerceAtLeast(120)
            val pushX = (dx * scale).toInt().coerceIn(-maxPush, maxPush)
            val pushY = (dy * scale).toInt().coerceIn(-maxPush, maxPush)
            if (!svc.swipe(joyX, joyY, joyX + pushX, joyY + pushY, 900)) {
                log("🚶走位到[${pos.keyword}]失败：滑动手势未被系统接受")
                return false
            }
            log("🚶走位到[${pos.keyword}] 第${step + 1}步: 目标(${pos.x},${pos.y}) 推杆($pushX,$pushY)")
            delay(RandomDelay.uniform(900, 1400))
        }
        log("🚶走位到[$label]: 步数用尽，未确认接近目标")
        return false
    }

    /**
     * v1.4.35: 模拟"移动方向键"（虚拟摇杆）把角色推向雕像。
     * 实测（模拟器 1920x1080）：角色出生在画面下方中央圆台，雕像位于**正上方**（告示牌建筑方向）——
     * 靠近后右侧出现"一键务农/流光加速/农场升级"。此前 v1.4.30/31 按"雕像在左下方"向下/左下推摇杆，
     * 使角色被推离雕像、永远无法靠近（"一键务农"永不出现）。改为**向上**（为主）/左上/右上多方向推进，
     * 每步用 OCR 复查"一键务农"，出现即停。
     */
    private suspend fun moveTowardsStatue(): Boolean {
        val svc = com.farmbot.accessibility.FarmAccessibilityService.getInstance() ?: return false
        val provider = screenshotProvider ?: return false
        val bmp = runCatching { provider() }.getOrNull() ?: return false
        val scaleX = bmp.width / 1920f
        val scaleY = bmp.height / 1080f
        bmp.recycle()
        val joyX = (300 * scaleX).toInt()
        val joyY = (820 * scaleY).toInt()
        // 向上（雕像在角色上方）为主，辅以左上/右上微调；每次幅度适中，避免推过头
        val steps = listOf(
            0 to 0,       // 占位，直接先查一次
            0 to (-280 * scaleY).toInt(),
            (-160 * scaleX).toInt() to (-220 * scaleY).toInt(),
            (160 * scaleX).toInt() to (-220 * scaleY).toInt(),
            0 to (-200 * scaleY).toInt(),
            (-110 * scaleX).toInt() to (-160 * scaleY).toInt(),
            (110 * scaleX).toInt() to (-160 * scaleY).toInt()
        )
        for (i in steps.indices) {
            if (!_isRunning.value || !scope.isActive) return false
            if (i > 0) {
                val (dx, dy) = steps[i]
                svc.swipe(joyX, joyY, joyX + dx, joyY + dy, 1100)
                delay(RandomDelay.uniform(900, 1500))
            }
            if (ocrContainsAny("一键务农", "键务农")) {
                log("✅朝雕像移动成功(第${i}步)，[一键务农]已出现")
                return true
            }
            log("朝雕像移动(第${i}步)，仍未出现[一键务农]")
        }
        return false
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
        val canStealNow = cfg.enableSteal && stealRemain > 0 &&
                !(cfg.respectStealProtection && GameRules.isStealProtectionTime())
        val canBlessNow = cfg.enableBless && blessRemain > 0
        val stealFirst = cfg.stealBeforeBless
        // v1.4.7: 有截图+点击通道时走真实 OCR 导航（社交→好友列表→逐个拜访）；
        // 演示路径不再写入每日计数（此前模拟会虚假消耗真实配额）
        if (isRealMode() && screenshotProvider != null) {
            val visited = visitFriendsViaOcr(canStealNow, canBlessNow, stealRemain, blessRemain)
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
            // v1.4.37: 偷菜/祝福需走到对应地块才可操作——先朝目标走位再点击（目标不在画面则跳过走位）
            if (canSteal && stealLeft > 0 && ocrContainsAny("拿取", "可偷")) {
                walkNearText(listOf("拿取", "可偷"), maxSteps = 4)
            }
            if (canSteal && stealLeft > 0 && tryClickTexts("拿取", "可偷", "偷菜", "摘取")) {
                DailyCounters.addSteal(); stealLeft--; acted = true
                log("✓真实偷菜完成(今日剩余$stealLeft)")
            }
            if (canBless && blessLeft > 0 && ocrContainsAny("祝福", "祈福")) {
                walkNearText(listOf("祝福", "祈福"), maxSteps = 4)
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
        // 尽力回到自己农场，失败交由后续导航状态兜底。
        // v1.4.23: 末尾不再用裸词"农场"——它会被大厅聊天面板的"农场"标签/任意"农场"字样命中，
        // 导致误点进大厅聊天页（实测日志 "OCR定位[农场](275,49)"）。仅保留精确词。
        tryClickTexts("返回农场", "我的农场", "离开好友农场")
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
        if (!isRealMode() || screenshotProvider == null) {
            log("[模拟] 升级/开地演示（无截图/点击通道）")
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
            log("开垦未开启（expandAlways=false 且 upgradeExpandAfter=false），本轮跳过开垦")
            return
        }
        log("开垦门控开启：主动寻找开垦入口并执行")
        if (!confirmFarmScene()) {
            log("开垦前未确认在自家农场，尝试导航进入...")
            if (!doNavigateToFarm()) {
                log("⚠开垦前置导航失败，本轮跳过")
                return
            }
        }
        // v1.4.11: 关键词补「开垦/可开垦」——游戏告示牌显示"可开垦"，仅凭"开地/购买土地/
        // 解锁土地/扩建"无法命中（.contains 子串匹配），导致升级/开地功能不可用
        // v1.4.37: 开垦需走到对应地块才可操作——先走位接近"可开垦"地块再点击，
        // 否则远距离点击无效（相机跟随角色，入口文字可能位于角色远处）。
        val expandKw = arrayOf("开垦", "可开垦", "开地", "购买土地", "解锁土地", "扩建", "扩充土地")
        if (ocrContainsAny(*expandKw)) {
            walkNearText(expandKw.toList(), maxSteps = 5)
        }
        if (tryClickTexts(*expandKw)) {
            delay(RandomDelay.uniform(1000, 2000))
            if (tryClickTexts("确定", "确认", "购买")) {
                log("✓已执行开地/升级确认")
            } else {
                log("✓已点击开地/升级入口（未见确认弹窗，可能已直接生效）")
            }
        } else {
            log("⚠主动开垦: 农场页面未找到开垦/开地入口（当前可能无地可开，或需真机校准按钮位置）")
        }
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
