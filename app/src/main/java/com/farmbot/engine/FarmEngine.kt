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
    private var collector: TemplateCollector = TemplateCollector(context).also { stateMachine.setTemplateCollector(it) }
    private var matcher = TemplateMatcher()
    private var ocr = OcrHelper()

    private val _engineState = MutableStateFlow(EngineState.STOPPED)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val _statusMessage = MutableStateFlow("引擎未启动")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    val currentState = stateMachine.currentState
    val isRunning = stateMachine.isRunning
    val logs = stateMachine.logs

    init {
        runCatching { ConfigManager.init(context) }
        collector = TemplateCollector(context)
        stateMachine.setTemplateCollector(collector)
    }

    fun setScreenshotService(svc: ScreenshotService?) {
        screenshotService = svc
        collector.setCaptureService(svc)
        // v1.4.3: 为状态机提供截图通道（OCR文字定位点击的关键依赖）
        stateMachine.setScreenshotProvider(
            if (svc != null) ({ svc.captureBitmap() }) else null
        )
        Log.i(TAG, "setScreenshotService ok: ${svc != null}")
    }

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

    fun getActionExecutor(): ActionExecutor? = actionExecutor
    fun getTemplateCollector(): TemplateCollector = collector
    fun getTemplateMatcher(): TemplateMatcher = matcher
    fun getOcr(): OcrHelper = ocr

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
        taskScheduler.scheduleDailyBattleCheck()
    }

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

    fun triggerWateringNow() {
        if (_engineState.value == EngineState.STOPPED) start()
        else {
            Log.i(TAG, "触发浇水任务")
            stateMachine.transitionTo(FarmState.NavigateToFarm)
        }
    }

    /** 触发异常恢复测试（调试用） */
    fun triggerRecoverFlowForDebug() {
        Log.w(TAG, "手动触发恢复流程（调试）")
        stateMachine.transitionTo(FarmState.ReconnectA11y)
    }

    fun getStateDisplayName(): String = stateMachine.currentState.value.displayName()
    fun getLogs(): List<String> = stateMachine.getLogs()

    fun destroy() {
        stop()
        runCatching { ocr.shutdown() }
        actionExecutor = null
        screenshotService = null
        Log.i(TAG, "农场引擎销毁")
    }
}
