package com.farmbot.engine.runtime

import android.util.Log
import com.farmbot.engine.ActionExecutor
import com.farmbot.ocr.OcrHelper
import com.farmbot.recognition.TemplateMatcher
import com.farmbot.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 状态机运行时：共享依赖实例、运行标志、计数与日志
 *
 * 由 FarmStateMachine.kt 拆分而来。本类只做「持有 + 暴露」，不承载任何状态判定或点击决策；
 * 成员声明与函数体原样搬迁，仅可见性由 private 放宽到 internal-class 可见（供状态实现读取）。
 */
class FarmRuntime {

    companion object {
        /** 与拆分前 FarmStateMachine 的日志 TAG 保持同值，避免日志行为变化 */
        private const val TAG = "FarmStateMachine"
    }

    private val _isRunning = MutableStateFlow(false)
/**
 * 运行标志：状态机运行中为 true */
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /** 运行标志的唯一写入口：原 `_isRunning.value = x` 的等价替换 */
    fun setRunning(value: Boolean) { _isRunning.value = value }

    private val _logs = MutableStateFlow<List<String>>(emptyList())
/**
 * 日志缓冲流：每条引擎日志都会进入这里 */
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

/**
 * 引擎协程作用域：状态任务的执行域，停止时统一取消 */
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

/**
 * 当前状态已重试次数（恢复第一阶段的计数） */
    var retryCount = 0
/**
 * 已进行的恢复阶段：0 未恢复 / 1 重连无障碍 / 2 重启游戏 */
    var recoverCount = 0
/**
 * 今日已完成的操作次数，用于每日上限判定 */
    var dailyOperationCount = 0
/**
 * 最近一次状态切换的时间戳 */
    var lastStateTime = 0L

    /** 懒初始化：OCR 依赖 ML Kit/Android，改懒加载后 FarmRuntime 可在纯 JVM 单测里构造 */
    val ocr by lazy { OcrHelper() }
/**
 * 模板匹配器：懒初始化，避免无设备环境提前加载 OpenCV */
    val matcher by lazy { TemplateMatcher() }

/**
 * 动作执行器：无障碍连接后注入；为 null 时走模拟流程 */
    @Volatile var actionExecutor: ActionExecutor? = null

/**
 * 截图通道：无障碍服务未连接时为空，此时 OCR 文字定位不可用 */
    @Volatile var screenshotProvider: (() -> android.graphics.Bitmap?)? = null

    // v1.4.8: 录屏授权失效回调 —— 由 FarmEngine 注入为 pause()；通道死亡时快速暂停替代数百次无效重试
    @Volatile var onCaptureLost: (() -> Unit)? = null

/**
 * 写一条引擎日志：同时进内存缓冲与 Logcat */
    fun log(msg: String) {
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

/**
 * 日志快照：一次性取出当前全部日志 */
    fun getLogs(): List<String> = _logs.value.toList()

}
