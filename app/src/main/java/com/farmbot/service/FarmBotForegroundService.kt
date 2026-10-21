/**
 * 常驻前台服务 — 让引擎在后台保活
 */

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
import com.farmbot.engine.FarmEngine
import com.farmbot.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 常驻前台服务 — 让引擎在后台保活
 *
 * 功能：
 *  - 启动时把引擎保持在运行/暂停状态，不随系统回收
 *  - 更新常驻通知(显示状态、日志最后一行)
 *  - 监听"停止/暂停/继续/立即浇水"广播命令 (ACTION_STOP 等)
 */
class FarmBotForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "farmbot_foreground"
        private const val NOTIF_ID = 10086
        const val ACTION_START = "com.farmbot.action.START"
        const val ACTION_STOP = "com.farmbot.action.STOP"
        const val ACTION_PAUSE = "com.farmbot.action.PAUSE"
        const val ACTION_RESUME = "com.farmbot.action.RESUME"
        const val ACTION_WATER_NOW = "com.farmbot.action.WATER_NOW"
        // v1.4.6: 仅保活不启动引擎——App 打开/开机自启不应隐式开跑（无录屏通道时会静默走 [模拟] 路径）
        const val ACTION_KEEP_ALIVE = "com.farmbot.action.KEEP_ALIVE"

        fun start(ctx: Context) {
            val i = Intent(ctx, FarmBotForegroundService::class.java).apply { action = ACTION_KEEP_ALIVE }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, FarmBotForegroundService::class.java))
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var collectJob: Job? = null

    private val receiver = object : BroadcastReceiver() {
        /**
         * 
常驻通知动作广播处理：暂停 / 恢复 / 停止 / 立即浇水
         */
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_STOP -> { stopSelf(); FarmEngine.getInstance(applicationContext).stop() }
                ACTION_PAUSE -> FarmEngine.getInstance(applicationContext).pause()
                ACTION_RESUME -> {
                    // 没有录屏通道时拒绝恢复，避免真实操作悄悄降级成模拟流程。
                    if (!com.farmbot.screenshot.ScreenshotService.isCapturing()) {
                        android.widget.Toast.makeText(
                            applicationContext,
                            "录屏通道未就绪，无法恢复引擎",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        Logger.w("FarmBotForegroundSvc", "拒绝恢复：录屏通道未就绪")
                        return
                    }
                    FarmEngine.getInstance(applicationContext).start()
                }
                ACTION_WATER_NOW -> FarmEngine.getInstance(applicationContext).triggerWateringNow()
            }
        }
    }

/**
 * 前台服务创建：建立常驻通知
 */
    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification("FarmBot 后台运行中", "准备就绪"))
        val filter = IntentFilter().apply {
            addAction(ACTION_STOP); addAction(ACTION_PAUSE); addAction(ACTION_RESUME); addAction(ACTION_WATER_NOW)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        Logger.i("FarmBotForegroundSvc", "onCreate")
        collectLogsForNotification()
    }

/**
 * 前台服务启动：按命令启停引擎
 */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // v1.4.6: 仅显式 ACTION_START 启动引擎；KEEP_ALIVE（App 打开/开机自启）只保活不隐式开跑
        if (intent?.action == ACTION_START) {
            try { FarmEngine.getInstance(this).start() } catch (t: Throwable) { Logger.e("FarmBotFgSvc", "start engine", t) }
        }
        return START_STICKY
    }

/**
 * 不提供绑定
 */
    override fun onBind(p0: Intent?): IBinder? = null

    private fun collectLogsForNotification() {
        collectJob?.cancel()
        collectJob = scope.launch {
            val engine = runCatching { FarmEngine.getInstance(this@FarmBotForegroundService) }.getOrNull() ?: return@launch
            engine.logs.collectLatest { logs ->
                val last = logs.lastOrNull() ?: "—"
                val state = engine.currentState.value.displayName()
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIF_ID, buildNotification("FarmBot · $state", last))
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "农场机器人常驻", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示 FarmBot 后台运行状态"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val ctx = this
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        val openIntent = PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java), flag)
        val pauseIntent = PendingIntent.getBroadcast(ctx, 2, commandIntent(ACTION_PAUSE), flag)
        val stopIntent = PendingIntent.getBroadcast(ctx, 3, commandIntent(ACTION_STOP), flag)
        val waterIntent = PendingIntent.getBroadcast(ctx, 4, commandIntent(ACTION_WATER_NOW), flag)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "暂停", pauseIntent)
            .addAction(android.R.drawable.ic_menu_add, "立即浇水", waterIntent)
            .addAction(android.R.drawable.ic_delete, "停止", stopIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .build()
    }

    private fun commandIntent(action: String): Intent =
        Intent(action).setPackage(packageName)

/**
 * 前台服务销毁
 */
    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        collectJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}