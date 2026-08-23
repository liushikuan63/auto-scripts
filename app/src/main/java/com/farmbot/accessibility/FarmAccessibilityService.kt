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

    override fun onInterrupt() {
        Timber.w("FarmAccessibilityService interrupted")
        isServiceConnected = false
    }

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
                        // 游戏进入前台：若设置了自动启动且引擎已停止，则启动
                        if (cfg.autoStartWhenGameForeground
                            && state == com.farmbot.engine.EngineState.STOPPED) {
                            Logger.i(TAG, "检测到游戏进入前台，自动启动引擎")
                            engine.start()
                        } else if (state == com.farmbot.engine.EngineState.PAUSED) {
                            // 暂停状态下回到游戏自动恢复
                            engine.start()
                        }
                    } else {
                        // 游戏离开前台：若设置了离开暂停，则暂停引擎
                        if (cfg.pauseWhenGameBackground
                            && state == com.farmbot.engine.EngineState.RUNNING) {
                            Logger.i(TAG, "检测到游戏离开前台，暂停引擎")
                            engine.pause()
                        }
                    }
                }.onFailure { Timber.w(it, "observeGameForeground error") }
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isServiceConnected = false
        Timber.i("FarmAccessibilityService unbound")
        Logger.w("FarmAccessibilityService", "onUnbind() 服务被解绑")
        return super.onUnbind(intent)
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        isServiceConnected = true
        Timber.i("FarmAccessibilityService rebound")
        observeGameForeground()
    }

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
        }
        return ok
    }

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

    fun scrollUp(centerX: Int, centerY: Int, distance: Int = 400): Boolean =
        swipeBezier(centerX, centerY + distance / 2, centerX, centerY - distance / 2)

    fun scrollDown(centerX: Int, centerY: Int, distance: Int = 400): Boolean =
        swipeBezier(centerX, centerY - distance / 2, centerX, centerY + distance / 2)

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
    fun performGlobalHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun performGlobalRecent(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun getRootNode(): AccessibilityNodeInfo? = rootInActiveWindow

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
            Timber.v("Gesture ok: $tag")
        }
        override fun onCancelled(gestureDescription: GestureDescription?) {
            Timber.w("Gesture cancelled: $tag")
        }
    }
}
