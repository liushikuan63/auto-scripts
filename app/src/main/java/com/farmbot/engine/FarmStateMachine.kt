package com.farmbot.engine

import com.farmbot.engine.runtime.FarmRuntime
import com.farmbot.engine.runtime.FarmStateContext
import com.farmbot.engine.states.BattleUpgradeHandler
import com.farmbot.engine.states.CropOpsHandler
import com.farmbot.engine.states.NavigateAndScanHandler
import com.farmbot.engine.states.RecoveryHandler
import com.farmbot.engine.states.SocialHandler
import com.farmbot.engine.runtime.MovementSupport
import com.farmbot.engine.runtime.OcrClickSupport
import com.farmbot.engine.runtime.OneKeyFarmingSupport
import com.farmbot.config.ConfigManager
import com.farmbot.ocr.OcrHelper
import com.farmbot.policy.CropDatabase
import com.farmbot.policy.GameRules
import com.farmbot.util.DailyCounters
import com.farmbot.util.Logger
import com.farmbot.util.RandomDelay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
) : FarmStateContext {
    companion object {
        private const val TAG = "FarmStateMachine"
        private const val MAX_RETRY = 5
        private const val MAX_RECOVER_RETRY = 2
        private const val STATE_TIMEOUT_MS = 3 * 60 * 1000L   // 单状态3分钟超时
    }

    private val _currentState = MutableStateFlow<FarmState>(FarmState.Idle)
    val currentState: StateFlow<FarmState> = _currentState.asStateFlow()

    /** 运行时：共享依赖实例、计数与日志；业务实现一律通过 rt 访问 */
    override val rt = FarmRuntime()

    /** OCR 定位与点击链路 */
    override val ocrClick = OcrClickSupport(rt)

    /** 走位支撑（依赖 OCR 定位） */
    override val movement = MovementSupport(rt, ocrClick)

    /** 一键务农与选种（依赖 OCR 定位与走位） */
    override val oneKey = OneKeyFarmingSupport(rt, ocrClick, movement)

    /** 状态处理器：按状态分组，各自只依赖 FarmStateContext */
    private val navigator = NavigateAndScanHandler(this)
    private val cropOps = CropOpsHandler(this, navigator)
    private val social = SocialHandler(this)
    private val battle = BattleUpgradeHandler(this, navigator, taskScheduler)
    private val recovery = RecoveryHandler(this)

    /** 在当前状态的互斥锁内同步切换状态（等价于原 transitionToInternal） */
    override fun gotoState(next: FarmState) = transitionToInternal(next)

    /** 对外暴露的运行状态：委托运行时，FarmEngine / UI 的既有取用方式不变 */
    val isRunning: StateFlow<Boolean> get() = rt.isRunning

    /** 对外暴露的日志流：委托运行时（浮动日志窗与主界面仍按原方式订阅） */
    val logs: StateFlow<List<String>> get() = rt.logs

    /** 日志快照：委托运行时（FarmEngine / 主界面沿用原调用方式） */
    fun getLogs(): List<String> = rt.getLogs()

    private val mutex = Mutex()
    @Volatile private var activeStateJob: Job? = null
    private val transitionGeneration = AtomicLong()

    /** 注入动作执行器：无障碍服务连接后由 FarmEngine 调用；注入后业务动作走真实点击 */
    fun setActionExecutor(exec: ActionExecutor?) { rt.actionExecutor = exec }

    /** 注入截图提供者（v1.4.3）：授权录屏后由 FarmEngine 调用，供 OCR 文字定位取帧 */
    fun setScreenshotProvider(provider: (() -> android.graphics.Bitmap?)?) { rt.screenshotProvider = provider }

    /** 注入录屏授权失效回调（v1.4.8）：由 FarmEngine 注入为暂停引擎+发告警通知 */
    fun setOnCaptureLost(cb: (() -> Unit)?) { rt.onCaptureLost = cb }

    fun start() {
        rt.scope.launch { mutex.withLock {
            if (rt.isRunning.value) return@withLock
            rt.setRunning(true)
            rt.retryCount = 0
            rt.recoverCount = 0
            rt.dailyOperationCount = 0
            rt.log("状态机启动")
            if (rt.screenshotProvider == null) {
                rt.log("⚠⚠ 未检测到录屏截图通道：OCR 定位不可用，自动点击无法精确落点，请先在主界面授权录屏！")
            }
            transitionToInternal(FarmState.NavigateToFarm)
        }}
    }

    fun pause() {
        rt.setRunning(false)
        transitionGeneration.incrementAndGet()
        activeStateJob?.cancel()
        rt.scope.launch { mutex.withLock {
            rt.log("状态机暂停")
            transitionToInternal(FarmState.Idle)
        }}
    }

    fun stop() {
        rt.setRunning(false)
        transitionGeneration.incrementAndGet()
        activeStateJob?.cancel()
        rt.scope.launch { mutex.withLock {
            rt.log("状态机停止")
            transitionToInternal(FarmState.Idle)
        }}
    }

    fun transitionTo(next: FarmState) {
        rt.scope.launch { mutex.withLock { transitionToInternal(next) } }
    }

    private fun transitionToInternal(next: FarmState) {
        if (!rt.isRunning.value && next != FarmState.Idle) {
            rt.log("状态机未运行，忽略转换请求: ${next.displayName()}")
            return
        }
        val prev = _currentState.value
        _currentState.value = next
        rt.lastStateTime = System.currentTimeMillis()
        rt.log("状态转换: ${prev.displayName()} → ${next.displayName()}")

        val generation = transitionGeneration.incrementAndGet()
        rt.scope.launch {
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
            if (rt.isRunning.value && _currentState.value == state) {
                Logger.w(TAG, "State '${state.displayName()}' 超时(${STATE_TIMEOUT_MS}ms)，强制中断并恢复")
                rt.log("状态超时，进入恢复流程")
                handleRecover(state)
            }
        } catch (t: CancellationException) {
            throw t
        } finally {
            if (activeStateJob === job) activeStateJob = null
        }
    }

    private suspend fun executeState(state: FarmState) = mutex.withLock {
        if (!rt.isRunning.value) return@withLock

        try {
            when (state) {
                FarmState.Idle -> rt.log("进入空闲状态")
                FarmState.ReconnectA11y -> recovery.reconnectA11y()
                FarmState.RecoverGame -> recovery.recoverGame()
                FarmState.NavigateToFarm -> navigator.navigateToFarm()
                FarmState.ScanCrops -> navigator.scanCrops()
                FarmState.WaterCrops -> cropOps.waterCrops()
                FarmState.HarvestCrops -> cropOps.harvestCrops()
                FarmState.SellOrStore -> cropOps.sellOrStore()
                FarmState.VisitFriends -> social.visitFriends()
                FarmState.DailyBattle -> battle.dailyBattle()
                FarmState.UpgradeDecision -> battle.upgradeDecision()
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Logger.e(TAG, "executeState 异常 in ${state.displayName()}", t)
            rt.log("异常: ${t.message}")
            handleRecover(state)
        }
    }

    fun destroy() {
        rt.setRunning(false)
        transitionGeneration.incrementAndGet()
        activeStateJob?.cancel()
        runCatching { rt.ocr.shutdown() }
        rt.scope.cancel()
    }

    /**
     * 异常容错三级机制：
     *   1. MAX_RETRY 次内重试当前状态
     *   2. 触发重连无障碍服务
     *   3. 触发重启游戏应用（冷启动导航）
     * 连续 MAX_RECOVER_RETRY 次恢复失败则进入空闲。
     */
    override suspend fun handleRecover(failedState: FarmState) {
        // v1.4.8: 录屏授权失效属"不可自愈"故障（重连无障碍/重启游戏都救不了，必须用户重新授权）。
        // 此前按普通失败计入重试且上限取 maxDailyOperations(500)，退避累加导致近一小时无意义空转
        // （日志表现为"截屏失败…重试(n/500)"）；现检测到真实模式+通道死亡立即回调暂停并告警，
        // 待用户重新授权录屏后再点"开始/继续"恢复。
        if (rt.actionExecutor != null &&
            failedState != FarmState.ReconnectA11y &&
            failedState != FarmState.RecoverGame &&
            !com.farmbot.screenshot.ScreenshotService.isCapturing()
        ) {
            rt.log("⛔录屏授权已失效，OCR点击通道中断 — 自动暂停（不再空转重试），请重新授权录屏后点继续")
            rt.onCaptureLost?.invoke()
            return
        }
        rt.retryCount++
        // v1.4.16: 重试上限此前误取 maxDailyOperations(500)，导致导航/场景失败被无限重试
        // （日志"重试(n/500)"，反复点击入口、长时间无结论）。改为按 MAX_RETRY 常量封顶，
        // 失败快速升级到 重连无障碍→重启游戏→进入空闲，给出明确结果。
        val limit = MAX_RETRY
        if (rt.retryCount < limit) {
            rt.log("重试 (${rt.retryCount}/$limit)...")
            delay(2000L + rt.retryCount * 1000L)
            transitionToInternal(failedState)
        } else {
            rt.retryCount = 0
            rt.recoverCount++
            when {
                rt.recoverCount == 1 -> {
                    rt.log("尝试恢复阶段1: 重连无障碍服务")
                    transitionToInternal(FarmState.ReconnectA11y)
                }
                rt.recoverCount == 2 -> {
                    rt.log("尝试恢复阶段2: 重启游戏应用")
                    transitionToInternal(FarmState.RecoverGame)
                }
                else -> {
                    rt.log("达到最大恢复次数，进入空闲")
                    rt.recoverCount = 0
                    transitionToInternal(FarmState.Idle)
                }
            }
        }
    }

    // ============ 恢复流程 ============

    override suspend fun <T> withErrorLog(op: String, block: suspend () -> T?): T? {
        return try { block() } catch (e: Exception) {
            Logger.e(TAG, "$op 失败", e)
            rt.log("$op 失败: ${e.message}")
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
}
