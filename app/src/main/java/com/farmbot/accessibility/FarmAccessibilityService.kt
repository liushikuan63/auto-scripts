package com.farmbot.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.farmbot.config.ConfigManager
import com.farmbot.engine.FarmEngine
import com.farmbot.util.GestureBuilder
import com.farmbot.util.Logger
import com.farmbot.util.RandomDelay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 无障碍服务 — 融合 HonorFarmBot 版本
 *
 * 核心能力：
 * 1. dispatchGesture 模拟点击/滑动/长按（贝塞尔曲线）
 * 2. 控件文字/ID 查找与点击
 * 3. 全局动作（BACK/HOME/RECENTS）
 * 4. 操作计数与每日上限
 * 5. 对外单例访问（getInstance）+ 连接状态查询
 */
class FarmAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FarmA11ySvc"

        @Volatile private var instance: FarmAccessibilityService? = null

        /** 检查服务是否已绑定且可用 */
        fun getInstance(): FarmAccessibilityService? {
            val svc = instance
            return if (svc != null && svc.isServiceConnected) svc else null
        }

        fun isConnected(): Boolean = instance != null && (instance?.isServiceConnected == true)

        /**
         * 从系统设置读取无障碍服务是否已启用（真实状态）。
         * 与 isConnected() 的区别：进程刚启动、服务尚未绑定回调时也能准确反映用户是否开启过。
         */
        fun isEnabledInSettings(context: android.content.Context): Boolean {
            return runCatching {
                val enabled = android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                enabled.split(':').any { flat ->
                    val cn = android.content.ComponentName.unflattenFromString(flat.trim())
                    cn != null && cn.packageName == context.packageName
                            && cn.className.endsWith("FarmAccessibilityService")
                }
            }.getOrDefault(false)
        }

        // 统计
        @Volatile var totalClicks: Int = 0
            private set
        @Volatile var totalSwipes: Int = 0
            private set
        @Volatile var dailyClicks: Int = 0
            private set

        fun resetDailyCount() { dailyClicks = 0 }
    }

    /** 当执行过 dispatchGesture 成功一次后设为 true，表示系统已确认绑定 */
    @Volatile private var isServiceConnected: Boolean = false

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var gameForegroundJob: Job? = null
    private var pausedForGameBackground = false

/**
 * 无障碍服务创建：初始化目标包检测与事件监听
 */
    override fun onCreate() {
        super.onCreate()
        instance = this
        // 初始化前台App检测器（全局单例）
        ForegroundAppDetector.init(applicationContext)
        // 把自己装配到引擎
        runCatching {
            FarmEngine.getInstance(applicationContext).setAccessibilityService(this)
        }
        Timber.i("FarmAccessibilityService created")
        Logger.i("FarmAccessibilityService", "onCreate() 服务已启动")
    }

/**
 * 无障碍事件入口：转发给前台应用与场景检测，并按配置启停引擎
 */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // 1. 交给前台App检测器处理（识别游戏/目标App）
        ForegroundAppDetector.onAccessibilityEvent(this, event)
        // 2. 游戏内场景识别（农场/大厅/对局，内部自带2秒节流）
        GameSceneDetector.onAccessibilityEvent(this, event)
        // 3. 调试日志（只保留状态变化，避免刷屏）
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: ""
            Timber.v("WindowStateChanged: $pkg")
        }
    }

/**
 * 无障碍服务被打断：记录状态并停止推进
 */
    override fun onInterrupt() {
        Timber.w("FarmAccessibilityService interrupted")
        isServiceConnected = false
    }

/**
 * 无障碍连接成功：绑定执行器、开启目标游戏检测并恢复引擎
 */
    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceConnected = true
        Timber.i("FarmAccessibilityService connected (BIND)")
        Logger.i("FarmAccessibilityService", "onServiceConnected() 绑定成功")
        // 监听目标游戏进入/离开前台，自动触发引擎启停（可配置开关）
        observeGameForeground()
    }

    private fun observeGameForeground() {
        gameForegroundJob?.cancel()
        gameForegroundJob = scope.launch {
            ForegroundAppDetector.isTargetGameForeground.collect { isGame ->
                runCatching {
                    val engine = FarmEngine.getInstance(this@FarmAccessibilityService)
                    val cfg = com.farmbot.config.ConfigManager.getConfig()
                    val state = engine.engineState.value
                    if (isGame) {
                        if (pausedForGameBackground && state != com.farmbot.engine.EngineState.PAUSED) {
                            pausedForGameBackground = false
                        }
                        if (pausedForGameBackground && state == com.farmbot.engine.EngineState.PAUSED) {
                            if (com.farmbot.screenshot.ScreenshotService.isCapturing()) {
                                pausedForGameBackground = false
                                Logger.i(TAG, "游戏回到前台且录屏通道就绪，恢复后台联动暂停的引擎")
                                engine.start()
                            } else {
                                Logger.w(TAG, "游戏回到前台但录屏通道未就绪，保持暂停")
                            }
                            return@runCatching
                        }
                        // 游戏进入前台：若设置了自动启动且引擎已停止，则启动。
                        // v1.4.34: 真机动作入口必须先校验录屏截图通道（v1.4.5 纪律）——
                        // 王者农场为 SurfaceView，无障碍节点点击恒失败，无录屏通道时启动只会静默走 [模拟] 流程。
                        if (cfg.autoStartWhenGameForeground
                            && state == com.farmbot.engine.EngineState.STOPPED) {
                            if (com.farmbot.screenshot.ScreenshotService.isCapturing()) {
                                Logger.i(TAG, "检测到游戏进入前台且录屏通道就绪，自动启动引擎")
                                engine.start()
                            } else {
                                Logger.w(TAG, "检测到游戏进入前台但未授权录屏，拒绝自动启动（避免无通道静默走 [模拟]）")
                            }
                        }
                    } else {
                        // 游戏离开前台：若设置了离开暂停，则暂停引擎
                        if (cfg.pauseWhenGameBackground
                            && state == com.farmbot.engine.EngineState.RUNNING) {
                            pausedForGameBackground = true
                            Logger.i(TAG, "检测到游戏离开前台，暂停引擎")
                            engine.pause()
                        }
                    }
                }.onFailure { Timber.w(it, "observeGameForeground error") }
            }
        }
    }

/**
 * 解绑：停止目标游戏检测
 */
    override fun onUnbind(intent: Intent?): Boolean {
        isServiceConnected = false
        Timber.i("FarmAccessibilityService unbound")
        Logger.w("FarmAccessibilityService", "onUnbind() 服务被解绑")
        return super.onUnbind(intent)
    }

/**
 * 重新绑定：重新登记目标游戏检测
 */
    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        isServiceConnected = true
        Timber.i("FarmAccessibilityService rebound")
        observeGameForeground()
    }

/**
 * 服务销毁：释放执行器与检测器
 */
    override fun onDestroy() {
        runCatching { gameForegroundJob?.cancel() }
        runCatching { scope.cancel() }
        super.onDestroy()
        instance = null
        isServiceConnected = false
        Timber.i("FarmAccessibilityService destroyed")
    }

    // ==================== 点击 ====================

    fun click(x: Int, y: Int): Boolean {
        val limit = ConfigManager.getConfig().dailyOperationLimit
        if (dailyClicks >= limit) {
            Logger.w("FarmAccessibilityService", "每日点击上限已达: $dailyClicks/$limit")
            return false
        }
        val range = ConfigManager.getConfig().randomOffsetRange
        val actualX = x + RandomDelay.randomInt(-range, range)
        val actualY = y + RandomDelay.randomInt(-range, range)
        val path = GestureBuilder.buildClickPath(actualX, actualY)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(
                path, 0L, RandomDelay.randomLong(120L, 220L)))
            .build()
        val ok = dispatchGesture(gesture, GestureResultListener("click($actualX,$actualY)"), null)
        if (ok) {
            totalClicks++
            dailyClicks++
            isServiceConnected = true
            Timber.d("Click ($actualX,$actualY) total=$totalClicks")
        } else {
            // v1.4.17: 派发失败可见化——常见原因：服务未连接 / 无障碍未开启 / 手势被系统拒绝
            Logger.w("FarmAccessibilityService", "点击($actualX,$actualY) 派发失败（无效应服务是否连接、无障碍是否开启）")
        }
        return ok
    }

/**
 * 按矩形中心点击
 */
    fun click(rect: Rect): Boolean = click(rect.centerX(), rect.centerY())

    /**
     * 查找并点击指定文字的控件
     */
    fun clickByText(text: String, exact: Boolean = true): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = findNodesByText(root, text, exact)
        if (nodes.isEmpty()) {
            Timber.d("Text not found: $text")
            return false
        }
        val rect = Rect()
        nodes.first().getBoundsInScreen(rect)
        return click(rect)
    }

/**
 * 按控件 viewId 查找并点击节点
 */
    fun clickById(viewId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        if (nodes.isEmpty()) return false
        val rect = Rect()
        nodes.first().getBoundsInScreen(rect)
        return click(rect)
    }

    // ==================== 滑动 ====================

    fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(fromX.toFloat(), fromY.toFloat())
            lineTo(toX.toFloat(), toY.toFloat())
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        val ok = dispatchGesture(g, GestureResultListener("swipe"), null)
        if (ok) { totalSwipes++; isServiceConnected = true }
        return ok
    }

/**
 * 贝塞尔曲线滑动：控制点随机化，避免直线轨迹
 */
    fun swipeBezier(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long = 500): Boolean {
        val path = GestureBuilder.buildBezierPath(fromX, fromY, toX, toY)
        val jittered = RandomDelay.durationJitter(durationMs, 0.25f)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, jittered))
            .build()
        val ok = dispatchGesture(g, GestureResultListener("bezierSwipe"), null)
        if (ok) { totalSwipes++; isServiceConnected = true }
        return ok
    }

/**
 * 向上滚动指定距离
 */
    fun scrollUp(centerX: Int, centerY: Int, distance: Int = 400): Boolean =
        swipeBezier(centerX, centerY + distance / 2, centerX, centerY - distance / 2)

/**
 * 向下滚动指定距离
 */
    fun scrollDown(centerX: Int, centerY: Int, distance: Int = 400): Boolean =
        swipeBezier(centerX, centerY - distance / 2, centerX, centerY + distance / 2)

/**
 * 按方向滚动（方向取无障碍的滚动常量）
 */
    fun scroll(direction: Int, centerX: Int, centerY: Int, distance: Int = 400): Boolean {
        return when (direction) {
            0 -> scrollUp(centerX, centerY, distance)
            1 -> swipeBezier(centerX - distance / 2, centerY, centerX + distance / 2, centerY)
            2 -> scrollDown(centerX, centerY, distance)
            else -> swipeBezier(centerX + distance / 2, centerY, centerX - distance / 2, centerY)
        }
    }

    // ==================== 长按 ====================

    fun longPress(x: Int, y: Int, durationMs: Long = 800): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L,
                RandomDelay.durationJitter(durationMs, 0.1f)))
            .build()
        val ok = dispatchGesture(g, GestureResultListener("longPress"), null)
        if (ok) isServiceConnected = true
        return ok
    }

    // ==================== 查找 ====================

    private fun findNodesByText(
        root: AccessibilityNodeInfo, text: String, exact: Boolean
    ): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        traverse(root, text, exact, results)
        return results
    }

    private fun traverse(
        node: AccessibilityNodeInfo?, text: String, exact: Boolean,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        if (node == null) return
        val nt = node.text?.toString() ?: ""
        val nd = node.contentDescription?.toString() ?: ""
        val match = if (exact) nt == text || nd == text else nt.contains(text) || nd.contains(text)
        if (match) results.add(node)
        for (i in 0 until node.childCount) traverse(node.getChild(i), text, exact, results)
    }

    // ==================== 全局动作 ====================

    fun performGlobalBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
/**
 * 发送系统 HOME 动作：回到桌面用于重启游戏
 */
    fun performGlobalHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
/**
 * 发送系统最近任务动作
 */
    fun performGlobalRecent(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

/**
 * 当前窗口的根节点（农场为自绘界面时通常为空）
 */
    fun getRootNode(): AccessibilityNodeInfo? = rootInActiveWindow

/**
 * 导出当前窗口的全部文本，用于排查识别问题
 */
    fun dumpAllText(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        dumpNode(root, sb, 0)
        return sb.toString()
    }

    private fun dumpNode(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        val indent = "  ".repeat(depth)
        val t = node.text?.toString() ?: ""
        val d = node.contentDescription?.toString() ?: ""
        val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
        if (t.isNotEmpty() || d.isNotEmpty()) sb.append("$indent[$cls] text=$t desc=$d\n")
        for (i in 0 until node.childCount) dumpNode(node.getChild(i), sb, depth + 1)
    }

    private class GestureResultListener(private val tag: String) :
        GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            // v1.4.17: 升级为可见日志，便于判断点击是否真的执行成功（此前 Timber.v 默认不可见）
            Logger.i("FarmAccessibilityService", "手势完成: $tag")
        }
        override fun onCancelled(gestureDescription: GestureDescription?) {
            Logger.w("FarmAccessibilityService", "手势被取消: $tag（可能被窗口切换/其它输入抢占）")
        }
    }
}
