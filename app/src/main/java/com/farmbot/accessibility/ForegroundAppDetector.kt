package com.farmbot.accessibility

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.farmbot.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.view.inputmethod.InputMethodManager

/**
 * 稳定的前台App检测器
 *
 * 核心链路：事件快照 → 事件筛选 → 候选包名 → 短暂窗口分流 → 稳定状态提交
 * 参考：Android 设备管控无障碍前台App识别最佳实践
 *
 * 双通道：
 * 1. AccessibilityService 事件（主通道，实时）- 需要用户开启无障碍
 * 2. UsageStatsManager 轮询（兜底通道，分钟级）- 需要用户授权使用情况访问
 */
object ForegroundAppDetector {

    private const val TAG = "ForegroundAppDetector"

    /** 默认目标游戏包名（王者农场） */
    const val TARGET_GAME_PKG = com.farmbot.config.FarmConfig.DEFAULT_TARGET_PKG

    /** 短暂窗口保持时长（毫秒）：在这个时间内的包名切换如果回到原稳定包，就不提交变化 */
    private const val TRANSIENT_WINDOW_MS = 1500L

    /** UsageStats 兜底轮询间隔（毫秒） */
    private const val USAGE_POLL_INTERVAL_MS = 30_000L

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ========== 公开状态 ==========

    /** 当前稳定前台包名（排除了输入法/SystemUI/权限弹窗等干扰） */
    private val _stableForegroundPkg = MutableStateFlow<String?>(null)
    val stableForegroundPkg: StateFlow<String?> = _stableForegroundPkg.asStateFlow()

    /** 目标游戏是否在前台 */
    private val _isTargetGameForeground = MutableStateFlow(false)
    val isTargetGameForeground: StateFlow<Boolean> = _isTargetGameForeground.asStateFlow()

    /** 可被用户自定义的目标包名集合（默认包含王者农场） */
    private val targetPackages = mutableSetOf(TARGET_GAME_PKG)

    // ========== 内部状态 ==========

    /** 最近一次稳定前台包（用于短暂窗口回流判断） */
    @Volatile private var lastStablePkg: String? = null

    /** 短暂候选包：如果在 TRANSIENT_WINDOW_MS 内没被证实，则不升级为稳定 */
    @Volatile private var transientCandidate: String? = null

    @Volatile private var transientStartedAt = 0L

    /** 基础干扰包（系统界面、输入法等）— 永远不会被提交为稳定前台 */
    private val baseTransientPackages = mutableSetOf(
        "android",
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.android.settings",
        "com.miui.securitycenter",
        "com.miui.home",
        "com.huawei.systemmanager"
    )

    /** 动态计算的干扰包集合（包含已启用输入法 + Launcher） */
    @Volatile private var transientPackages: Set<String> = emptySet()

    /** 是否已初始化 */
    @Volatile private var initialized = false

    // ==================== 初始化 ====================

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            // 从配置加载用户自定义的目标应用集合（支持多游戏/自定义包名）
            runCatching {
                setTargetPackages(com.farmbot.config.ConfigManager.getConfig().targetPackages)
            }
            refreshTransientPackages(context)
            initialized = true
            startUsageStatsPoller(context.applicationContext)
            Logger.i(TAG, "init done, targets=$targetPackages, transientPkgs count=${transientPackages.size}")
        }
    }

    /** 添加自定义目标包名（应用支持多游戏时使用） */
    fun addTargetPackage(pkg: String) {
        targetPackages.add(pkg)
        updateTargetGameFlag()
    }

    fun setTargetPackages(pkgs: Collection<String>) {
        targetPackages.clear()
        targetPackages.addAll(pkgs)
        targetPackages.add(TARGET_GAME_PKG) // 始终包含默认游戏
        updateTargetGameFlag()
    }

    /** 当前生效的目标包名集合（供UI展示） */
    fun currentTargets(): Set<String> = targetPackages.toSet()

    private fun refreshTransientPackages(context: Context) {
        val imePkgs = runCatching {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.enabledInputMethodList?.map { it.packageName }?.toSet() ?: emptySet()
        }.getOrDefault(emptySet())

        val launcherPkg = runCatching {
            val home = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
            context.packageManager.resolveActivity(home, 0)?.activityInfo?.packageName
        }.getOrNull()

        transientPackages = baseTransientPackages + imePkgs + listOfNotNull(launcherPkg)
    }

    // ==================== Accessibility 事件入口 ====================

    /**
     * 由 FarmAccessibilityService 在 onAccessibilityEvent 中调用
     * 只处理 TYPE_WINDOW_STATE_CHANGED / TYPE_WINDOWS_CHANGED 两种改变窗口归属的信号
     */
    fun onAccessibilityEvent(service: FarmAccessibilityService, event: AccessibilityEvent) {
        if (!initialized) init(service)
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return

        val eventPkg = event.packageName?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        val activeWinPkg = getActiveWindowPackage(service)
        val rootPkg = service.rootInActiveWindow?.packageName?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        // 三者都可能短暂为空或延迟，优先顺序：activeWinPkg > rootPkg > eventPkg
        val candidate = activeWinPkg ?: rootPkg ?: eventPkg ?: return

        if (transientPackages.contains(candidate)) {
            // 明确是系统/输入法/Launcher，直接丢弃
            return
        }

        processCandidate(candidate)
    }

    private fun getActiveWindowPackage(service: FarmAccessibilityService): String? {
        return runCatching {
            service.windows.asSequence()
                .filter { it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { it.root?.packageName?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
                .firstOrNull()
        }.getOrNull()
    }

    // ==================== 短暂窗口分流 + 稳定状态提交 ====================

    private fun processCandidate(candidate: String) {
        val now = System.currentTimeMillis()
        val currentStable = _stableForegroundPkg.value

        if (candidate == currentStable) {
            // 与稳定值一致，清除短暂候选记录
            transientCandidate = null
            return
        }

        if (candidate == transientCandidate) {
            // 同一个候选持续出现了
            if (now - transientStartedAt >= TRANSIENT_WINDOW_MS) {
                // 超过保持时长，提交为稳定
                commitStable(candidate)
            }
            return
        }

        // 新候选出现：如果前一个稳定包与当前候选的切换是"快速回跳"则忽略
        // 例如 Launcher 短暂出现 300ms 后回到游戏：不更新
        if (candidate == lastStablePkg && transientCandidate != null
            && now - transientStartedAt < TRANSIENT_WINDOW_MS) {
            // 回到了原稳定包，清掉短暂候选，保持稳定不变
            transientCandidate = null
            return
        }

        transientCandidate = candidate
        transientStartedAt = now

        // 短暂延迟后二次确认：如果到时 candidate 仍是 transientCandidate 才提交
        scope.launch {
            delay(TRANSIENT_WINDOW_MS + 50)
            val tc = transientCandidate
            val ts = transientStartedAt
            if (tc == candidate
                && System.currentTimeMillis() - ts >= TRANSIENT_WINDOW_MS) {
                commitStable(candidate)
            }
        }
    }

    @Synchronized
    private fun commitStable(pkg: String) {
        if (pkg == _stableForegroundPkg.value) return
        lastStablePkg = _stableForegroundPkg.value
        _stableForegroundPkg.value = pkg
        transientCandidate = null
        updateTargetGameFlag()
        Logger.i(TAG, "前台App切换: $lastStablePkg -> $pkg, 目标游戏前台=${_isTargetGameForeground.value}")
    }

    private fun updateTargetGameFlag() {
        val pkg = _stableForegroundPkg.value
        val isTarget = pkg != null && targetPackages.contains(pkg)
        _isTargetGameForeground.value = isTarget
    }

    // ==================== UsageStatsManager 兜底 ====================

    /** UsageStats 轮询启动（初始化时自动启动） */
    private fun startUsageStatsPoller(appContext: Context) {
        scope.launch {
            while (true) {
                runCatching {
                    if (hasUsageStatsPermission(appContext)) {
                        val latest = queryLatestPackage(appContext)
                        if (latest != null && !transientPackages.contains(latest)) {
                            // 如果Accessibility主通道长期没更新，则兜底提交
                            val cur = _stableForegroundPkg.value
                            if (cur == null || System.currentTimeMillis() - transientStartedAt > 60_000L) {
                                processCandidate(latest)
                            }
                        }
                    }
                }.onFailure { Logger.e(TAG, "UsageStats poll failed", it) }
                delay(USAGE_POLL_INTERVAL_MS)
            }
        }
    }

    fun hasUsageStatsPermission(context: Context): Boolean {
        return runCatching {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }

    fun requestUsageStatsPermission(context: Context) {
        runCatching {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private fun queryLatestPackage(context: Context): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val now = System.currentTimeMillis()
        // 查过去 30 分钟：即使游戏长时间停留前台、期间无新事件，也能查到最近一次 ACTIVITY_RESUMED
        val events = usm.queryEvents(now - 30L * 60_000L, now) ?: return null
        var latestPkg: String? = null
        var latestTime = 0L
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
                || event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                if (event.timeStamp > latestTime) {
                    latestTime = event.timeStamp
                    latestPkg = event.packageName
                }
            }
        }
        return latestPkg
    }
}
