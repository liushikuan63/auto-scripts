package com.farmbot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.farmbot.MainActivity
import com.farmbot.R
import com.farmbot.accessibility.ForegroundAppDetector
import com.farmbot.engine.EngineState
import com.farmbot.engine.FarmEngine
import com.farmbot.ui.FloatingLogView
import com.farmbot.ui.FloatingPanelManager
import com.farmbot.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 悬浮窗常驻前台服务 — 修复"App隐藏时悬浮窗也消失"的核心方案
 *
 * 设计思路（根据Android悬浮窗最佳实践总结）：
 * 1. 悬浮窗必须由前台Service持有（而不是Activity），这样Activity销毁/按Home返回桌面时，悬浮窗不会被系统回收
 * 2. Service使用ApplicationContext创建悬浮窗，避免Activity Context泄漏或失效
 * 3. 通过 startForeground() 提升进程优先级，防止系统在后台杀死悬浮窗所在进程
 * 4. 对外提供Action命令（SHOW_PANEL/HIDE_PANEL/SHOW_LOG/HIDE_LOG等），Activity仅做命令发送方
 *
 * 参考：
 * - Android 11+ 必须使用 TYPE_APPLICATION_OVERLAY + FOREGROUND_SERVICE
 * - 悬浮窗不显示常见原因：用Activity Context、权限未确认、窗口类型错误、服务未前台化
 */
class FloatingWindowService : Service() {

    companion object {
        private const val TAG = "FloatingWindowSvc"
        private const val CHANNEL_ID = "farmbot_float_win"
        private const val NOTIF_ID = 10010

        // ========= 对外 Action 命令 =========
        const val ACTION_SHOW_PANEL    = "com.farmbot.float.SHOW_PANEL"
        const val ACTION_HIDE_PANEL    = "com.farmbot.float.HIDE_PANEL"
        const val ACTION_TOGGLE_PANEL  = "com.farmbot.float.TOGGLE_PANEL"
        const val ACTION_SHOW_LOG      = "com.farmbot.float.SHOW_LOG"
        const val ACTION_HIDE_LOG      = "com.farmbot.float.HIDE_LOG"
        const val ACTION_SHOW_ALL      = "com.farmbot.float.SHOW_ALL"
        const val ACTION_HIDE_ALL      = "com.farmbot.float.HIDE_ALL"
        const val ACTION_STOP_SELF     = "com.farmbot.float.STOP_SELF"

        /** 便捷启动：仅启动服务（不自动显示悬浮窗，由后续命令控制） */
        fun start(ctx: Context) {
            val i = Intent(ctx, FloatingWindowService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        /** 发送命令 */
        fun sendCommand(ctx: Context, action: String) {
            // 先保证服务已启动
            start(ctx)
            val i = Intent(ctx, FloatingWindowService::class.java).apply { this.action = action }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        /** 判断服务是否在运行（根据悬浮窗状态估算） */
        @Volatile private var running: Boolean = false
        fun isRunning(): Boolean = running
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var notifUpdateJob: Job? = null

    private lateinit var panelManager: FloatingPanelManager
    private lateinit var logView: FloatingLogView

    // 接收来自Activity/外部的命令广播（可选支持）
    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.action?.let { handleCommand(it) }
        }
    }

    // ================================================================
    // Service 生命周期
    // ================================================================

    override fun onCreate() {
        super.onCreate()
        running = true
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("🌾 农场助手悬浮窗服务", "运行中"))

        // 关键：用 Service 的 Context（本质是 ApplicationContext 级别）创建悬浮窗
        // 注入回调：面板上的"⤓ 收起"按钮走本服务的 HIDE_PANEL 命令（收起为悬浮球）
        panelManager = FloatingPanelManager(this) {
            runCatching { handleCommand(ACTION_HIDE_PANEL) }
        }
        logView = FloatingLogView(this)

        // 初始化前台App检测器（保证双保险检测链路启动）
        ForegroundAppDetector.init(applicationContext)

        // 监听引擎状态，更新前台通知内容（让用户从通知栏知道服务在干活）
        startNotifUpdater()

        // 可选：注册广播接收器（允许通过广播控制）
        runCatching {
            val f = IntentFilter().apply {
                addAction(ACTION_SHOW_PANEL)
                addAction(ACTION_HIDE_PANEL)
                addAction(ACTION_TOGGLE_PANEL)
                addAction(ACTION_SHOW_LOG)
                addAction(ACTION_HIDE_LOG)
                addAction(ACTION_SHOW_ALL)
                addAction(ACTION_HIDE_ALL)
                addAction(ACTION_STOP_SELF)
            }
            ContextCompat.registerReceiver(
                this,
                commandReceiver,
                f,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        Logger.i(TAG, "onCreate: FloatingWindowService 已启动为前台服务")
    }

/**
 * 悬浮窗服务启动：显示悬浮面板
 */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action != null) {
            handleCommand(action)
        }
        return START_STICKY // 被系统杀死后自动重启，保证悬浮窗高存活率
    }

/**
 * 不提供绑定
 */
    override fun onBind(intent: Intent?): IBinder? = null

/**
 * 悬浮窗服务销毁：移除窗口
 */
    override fun onDestroy() {
        running = false
        runCatching { unregisterReceiver(commandReceiver) }
        runCatching { logView.destroy() }
        runCatching { panelManager.hide() }
        notifUpdateJob?.cancel()
        scope.cancel()
        super.onDestroy()
        Logger.i(TAG, "onDestroy: FloatingWindowService 已停止")
    }

    // ================================================================
    // 命令处理
    // ================================================================

    private fun handleCommand(action: String) {
        Logger.i(TAG, "handleCommand: $action")
        when (action) {
            ACTION_SHOW_PANEL -> {
                // 已显示但收起/贴边时 → 展开；未显示时 → 新建显示
                val ok = if (panelManager.isVisible()) {
                    panelManager.expand(); true
                } else {
                    panelManager.show()
                }
                Logger.i(TAG, "ACTION_SHOW_PANEL => ok=$ok")
                if (!ok) notifyError("控制面板显示失败，请检查悬浮窗权限")
            }
            ACTION_HIDE_PANEL -> {
                // "隐藏"改为"收起为悬浮球"，不再完全移除窗口
                panelManager.collapse()
            }
            ACTION_TOGGLE_PANEL -> {
                panelManager.toggle()
            }
            ACTION_SHOW_LOG -> {
                val ok = logView.show()
                if (!ok) notifyError("日志窗口显示失败，请检查悬浮窗权限")
            }
            ACTION_HIDE_LOG -> logView.hide()
            ACTION_SHOW_ALL -> {
                panelManager.show()
                logView.show()
            }
            ACTION_HIDE_ALL -> {
                panelManager.hide()
                logView.hide()
            }
            ACTION_STOP_SELF -> {
                panelManager.hide()
                logView.hide()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun notifyError(msg: String) {
        Logger.e(TAG, msg)
        // v1.4.3: 错误不再只写日志，以Toast反馈给用户（悬浮窗失败时用户可感知原因）
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(applicationContext, "⚠$msg", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // ================================================================
    // 通知栏
    // ================================================================

    private fun startNotifUpdater() {
        notifUpdateJob?.cancel()
        notifUpdateJob = scope.launch {
            val engine = runCatching { FarmEngine.getInstance(this@FloatingWindowService) }.getOrNull()
                ?: return@launch
            launch {
                engine.engineState.collectLatest { state -> updateNotif(state) }
            }
            launch {
                ForegroundAppDetector.isTargetGameForeground.collectLatest {
                    val s = engine.engineState.value
                    updateNotif(s)
                }
            }
        }
    }

    private fun updateNotif(engineState: EngineState) {
        val game = ForegroundAppDetector.isTargetGameForeground.value
        val pkg = ForegroundAppDetector.stableForegroundPkg.value ?: "未知"
        val stateText = when (engineState) {
            EngineState.RUNNING -> "引擎运行中"
            EngineState.PAUSED -> "已暂停"
            EngineState.STOPPED -> "已停止"
        }
        val gameText = if (game) "🎮游戏前台" else "⏸后台(${pkg.substringAfterLast('.').take(10)})"
        val nm = getSystemService(NotificationManager::class.java)
        runCatching { nm.notify(NOTIF_ID, buildNotification(stateText, gameText)) }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "悬浮窗服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "农场助手悬浮控制面板与日志窗口的前台保活服务"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val ctx = this
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT

        val openIntent = PendingIntent.getActivity(
            ctx, 100, Intent(ctx, MainActivity::class.java), flag
        )
        val togglePanelIntent = PendingIntent.getService(
            ctx, 101, Intent(ctx, FloatingWindowService::class.java).apply { action = ACTION_TOGGLE_PANEL }, flag
        )
        val hideAllIntent = PendingIntent.getService(
            ctx, 102, Intent(ctx, FloatingWindowService::class.java).apply { action = ACTION_HIDE_ALL }, flag
        )

        // 直接使用系统内置图标，避免项目资源缺失导致编译错误
        val iconRes = android.R.drawable.ic_menu_view

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(iconRes)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_view, "切换悬浮面板", togglePanelIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "隐藏全部", hideAllIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .build()
    }
}
