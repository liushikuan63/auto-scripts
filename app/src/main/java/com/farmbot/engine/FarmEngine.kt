/**
 * 引擎对外状态
 */

package com.farmbot.engine

import android.content.Context
import android.content.Intent
import android.util.Log
import com.farmbot.accessibility.FarmAccessibilityService
import com.farmbot.config.ConfigManager
import com.farmbot.ocr.OcrHelper
import com.farmbot.recognition.TemplateCollector
import com.farmbot.recognition.TemplateMatcher
import com.farmbot.screenshot.ScreenshotService
import com.farmbot.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 引擎对外状态
 *
 * RUNNING：状态机正在推进；PAUSED：已暂停，等待人工恢复或重新授权录屏；
 * STOPPED：完全停止。UI 与悬浮面板据此展示当前允许执行的操作。
 */
enum class EngineState {
    RUNNING,
    PAUSED,
    STOPPED
}

/**
 * FarmEngine — 增强版（融合 HonorFarmBot 全局装配）
 *
 * - 初始化 ConfigManager / Logger / 依赖注入
 * - 提供 setScreenshotService / setAccessibilityService 装配点
 * - 自动从 ActionExecutor 初始化并关联状态机
 */
class FarmEngine private constructor(private val context: Context) {

    companion object {
        private const val TAG = "FarmEngine"

        @Volatile private var instance: FarmEngine? = null

        fun getInstance(context: Context): FarmEngine {
            return instance ?: synchronized(this) {
                instance ?: FarmEngine(context.applicationContext).also {
                    instance = it
                }
            }
        }

        /** 测试场景下允许重置 */
        fun resetForTest() { instance = null }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val taskScheduler = TaskScheduler(context)
    private val stateMachine = FarmStateMachine(taskScheduler)

    // ========= 子系统 (可选装配) =========
    private var screenshotService: ScreenshotService? = null
    private var actionExecutor: ActionExecutor? = null
    private var collector: TemplateCollector = TemplateCollector(context)
    private var matcher = TemplateMatcher()
    private var ocr = OcrHelper()

    private val _engineState = MutableStateFlow(EngineState.STOPPED)
/**
 * 引擎对外状态流：RUNNING / PAUSED / STOPPED */
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val _statusMessage = MutableStateFlow("引擎未启动")
/**
 * 状态提示文案流，供主界面与悬浮面板展示当前处境 */
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

/**
 * 当前农场状态（FarmState）流，直接透出状态机的状态 */
    val currentState = stateMachine.currentState
/**
 * 引擎是否正在运行 */
    val isRunning = stateMachine.isRunning
/**
 * 引擎日志流，透出状态机的日志缓冲 */
    val logs = stateMachine.logs

    init {
        runCatching { ConfigManager.init(context) }
        collector = TemplateCollector(context)
        // v1.4.8: 录屏授权失效（不可自愈故障）→ 状态机回调暂停引擎并发告警通知，替代数百次无效重试
        stateMachine.setOnCaptureLost { pause() }
    }

/**
 * 注入截图服务：录屏授权就绪后调用，OCR 文字定位依赖该通道 */
    fun setScreenshotService(svc: ScreenshotService?) {
        screenshotService = svc
        collector.setCaptureService(svc)
        // v1.4.3: 为状态机提供截图通道（OCR文字定位点击的关键依赖）
        val provider = svc?.let { captureService ->
            { captureService.captureBitmap() }
        }
        stateMachine.setScreenshotProvider(provider)
        Log.i(TAG, "setScreenshotService ok: ${svc != null}")
    }

/**
 * 注入无障碍服务：无障碍连接后调用，注入动作执行器并开启目标游戏检测 */
    fun setAccessibilityService(svc: FarmAccessibilityService?) {
        // 懒创建 ActionExecutor
        val svcNonNull = svc ?: return
        val exec = actionExecutor ?: run {
            ActionExecutor(
                context = context,
                accessibility = svcNonNull,
                templateMatcher = matcher,
                templateCollector = collector,
                ocrHelper = ocr
            ).also { actionExecutor = it }
        }
        // 关键修复(v1.3.0"不能自动点击")：把执行器注入状态机，
        // 否则状态机所有业务动作只会打模拟日志、从不产生真实点击
        stateMachine.setActionExecutor(exec)
        Log.i(TAG, "setAccessibilityService ok (executor 已注入状态机)")
    }

/**
 * 动作执行器：无障碍服务连接后才存在，未连接时为 null（此时走模拟流程） */
    fun getActionExecutor(): ActionExecutor? = actionExecutor
/**
 * 模板采集器：供模板采集界面与资源导入使用 */
    fun getTemplateCollector(): TemplateCollector = collector
/**
 * 模板匹配器：按模板在截图中查找目标 */
    fun getTemplateMatcher(): TemplateMatcher = matcher
/**
 * OCR 封装：中文文字识别入口 */
    fun getOcr(): OcrHelper = ocr

/**
 * 启动引擎：拉起状态机并进入导航流程 */
    fun start() {
        if (_engineState.value == EngineState.RUNNING) {
            Log.d(TAG, "引擎已在运行")
            return
        }
        runCatching { ConfigManager.init(context) }
        _engineState.value = EngineState.RUNNING
        _statusMessage.value = "引擎运行中"
        Log.i(TAG, "农场引擎启动 (dailyLimit=${ConfigManager.getConfig().maxDailyOperations})")
        stateMachine.start()
        taskScheduler.start()
    }

/**
 * 暂停引擎：停止状态推进但保留依赖，重新授权录屏后可直接继续 */
    fun pause() {
        if (_engineState.value != EngineState.RUNNING) {
            Log.d(TAG, "引擎未在运行，无法暂停")
            return
        }
        _engineState.value = EngineState.PAUSED
        _statusMessage.value = "引擎已暂停"
        Log.i(TAG, "农场引擎暂停")
        stateMachine.pause()
        taskScheduler.cancelAllAlarms()
    }

/**
 * 停止引擎：结束状态推进并回到空闲 */
    fun stop() {
        if (_engineState.value == EngineState.STOPPED) {
            Log.d(TAG, "引擎已停止"); return
        }
        _engineState.value = EngineState.STOPPED
        _statusMessage.value = "引擎已停止"
        Log.i(TAG, "农场引擎停止")
        stateMachine.stop()
        taskScheduler.cancelAllAlarms()
    }

/**
 * 立即执行一次浇水：供闹钟广播与调试工具调用 */
    fun triggerWateringNow() {
        // v1.4.34: 引擎已停止时不隐式启动（延续 v1.4.6 治理）。此前 STOPPED 时调 start()，
        // 导致 WorkManager 兜底周期任务 / 开机广播在应用启动或重启后静默开跑 [模拟] 流程无休止重试。
        if (_engineState.value != EngineState.RUNNING) {
            Log.w(TAG, "引擎未运行，忽略触发浇水（state=${_engineState.value}）")
            return
        }
        Log.i(TAG, "触发浇水任务")
        stateMachine.transitionTo(FarmState.NavigateToFarm)
    }

    /** 触发异常恢复测试（调试用） */
    fun triggerRecoverFlowForDebug() {
        Log.w(TAG, "手动触发恢复流程（调试）")
        stateMachine.transitionTo(FarmState.ReconnectA11y)
    }

/**
 * 当前状态的中文名 */
    fun getStateDisplayName(): String = stateMachine.currentState.value.displayName()
/**
 * 日志快照：一次性取出当前全部日志 */
    fun getLogs(): List<String> = stateMachine.getLogs()

/**
 * 释放引擎资源：取消调度、释放执行器与截图通道 */
    fun destroy() {
        stop()
        stateMachine.destroy()
        taskScheduler.destroy()
        runCatching { ocr.shutdown() }
        actionExecutor = null
        screenshotService = null
        Log.i(TAG, "农场引擎销毁")
    }
}