package com.farmbot.accessibility

import android.view.accessibility.AccessibilityEvent
import com.farmbot.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 游戏内场景检测器 — 解决 v1.3.0 "仅能检测游戏前台，检测不到游戏内状态" 的问题
 *
 * 三级证据链（从强到弱）：
 * 1. 无障碍节点树全文关键字匹配（王者农场页面有"一键收获/一键浇水"等可访问文本）
 * 2. AccessibilityEvent 的窗口类名特征（Activity 名含 farm 等）
 * 3. 兜底：目标游戏在前台但无法细分 → 游戏内其他页面
 *
 * 说明：游戏主体是 SurfaceView 渲染，节点树文本有限，故采用关键字命中制而非精确制；
 * 事件驱动 + 2秒节流，避免 WINDOW_CONTENT_CHANGED 高频触发导致主线程卡顿。
 */
object GameSceneDetector {

    private const val TAG = "GameSceneDetector"

    /** 游戏内场景枚举 */
    enum class Scene(val displayName: String) {
        UNKNOWN("检测中"),
        NOT_IN_GAME("不在游戏内"),
        GAME_HALL("游戏大厅"),
        FARM("农场页面"),
        BATTLE("对局中"),
        OTHER_IN_GAME("游戏内其他页面")
    }

    /** 农场页面特征词（命中任一即判定） */
    private val FARM_KEYWORDS = listOf(
        "王者农场", "一键收获", "一键浇水", "农场小摊", "好友农场", "我的农场"
    )

    /** 对局特征词（需命中≥2个，避免误判） */
    private val BATTLE_KEYWORDS = listOf(
        "发起进攻", "撤退", "集合", "回城", "经济", "补刀", "推塔"
    )

    /** 大厅特征词 */
    private val HALL_KEYWORDS = listOf(
        "排位赛", "巅峰赛", "稷下学院", "铭文", "备战", "战队", "商城"
    )

    /** Activity 类名特征 → 场景（弱证据，仅作辅助） */
    private val ACTIVITY_HINTS: List<Pair<String, Scene>> = listOf(
        "farm" to Scene.FARM,
        "battle" to Scene.BATTLE,
        "fight" to Scene.BATTLE,
        "lobby" to Scene.GAME_HALL,
        "main" to Scene.GAME_HALL
    )

    /** 最小检测间隔（毫秒）：节流窗口 */
    private const val MIN_DETECT_INTERVAL_MS = 2000L

    @Volatile private var lastDetectTime = 0L

    private val _currentScene = MutableStateFlow(Scene.UNKNOWN)
    val currentScene: StateFlow<Scene> = _currentScene.asStateFlow()

    /**
     * 由 FarmAccessibilityService 在 onAccessibilityEvent 中转发调用。
     * 内部节流：2秒内重复事件直接忽略。
     */
    fun onAccessibilityEvent(service: FarmAccessibilityService, event: AccessibilityEvent) {
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val now = System.currentTimeMillis()
        if (now - lastDetectTime < MIN_DETECT_INTERVAL_MS) return
        lastDetectTime = now

        detectNow(service)
    }

    /**
     * 立即执行一次场景检测。
     * 必须在主线程调用（rootInActiveWindow 仅主线程可靠）。
     */
    fun detectNow(service: FarmAccessibilityService) {
        val inGame = ForegroundAppDetector.isTargetGameForeground.value
        if (!inGame) {
            update(Scene.NOT_IN_GAME)
            return
        }
        // 证据1：节点树全文
        val allText = runCatching { service.dumpAllText() }.getOrDefault("")
        if (allText.isNotBlank()) {
            update(classifyFromText(allText))
            return
        }
        // 证据2：窗口类名特征（SurfaceView 游戏常见节点树为空，退化为 Activity 名）
        val className = runCatching {
            service.rootInActiveWindow?.className?.toString() ?: ""
        }.getOrDefault("")
        if (className.isNotBlank()) {
            val hint = ACTIVITY_HINTS.firstOrNull { className.lowercase().contains(it.first) }
            if (hint != null) {
                update(hint.second)
                return
            }
        }
        // 证据3：兜底
        update(Scene.OTHER_IN_GAME)
    }

    /** 纯文本分类（包内可见，便于单元测试） */
    fun classifyFromText(text: String): Scene = when {
        FARM_KEYWORDS.any { text.contains(it) } -> Scene.FARM
        BATTLE_KEYWORDS.count { text.contains(it) } >= 2 -> Scene.BATTLE
        HALL_KEYWORDS.any { text.contains(it) } -> Scene.GAME_HALL
        text.isBlank() -> Scene.UNKNOWN
        else -> Scene.OTHER_IN_GAME
    }

    @Synchronized
    private fun update(scene: Scene) {
        val old = _currentScene.value
        if (old != scene) {
            _currentScene.value = scene
            Logger.i(TAG, "游戏场景切换: ${old.displayName} -> ${scene.displayName}")
        }
    }
}
