package com.farmbot.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.farmbot.receiver.WateringAlarmReceiver
import com.farmbot.util.Logger
import com.farmbot.work.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/**
 * 任务调度器 — 双通道：精确 Alarm（主） + WorkManager（兜底）
 *
 * - 主通道：setExactAndAllowWhileIdle，到点准时触发 WateringAlarmReceiver
 * - 兜底通道：WorkManager 周期任务（最短 15 分钟），在 Doze/省电模式下也能唤醒引擎检查
 *
 * 在 Android 12+ 需 SCHEDULE_EXACT_ALARM 权限；如果被撤销会自动降级为普通 Alarm + 加大 WorkManager 周期。
 */
class TaskScheduler(private val context: Context) {

    companion object {
        private const val TAG = "TaskScheduler"
        private const val ALARM_REQUEST_CODE = 1001
        private const val PREFS_NAME = "farm_scheduler"
        private const val KEY_LAST_BATTLE_DATE = "last_battle_date"
        private const val KEY_WATER_INTERVAL = "water_interval_min"
        private const val DEFAULT_WATER_INTERVAL = 120
    }

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _nextWateringAt = MutableStateFlow(0L)
    val nextWateringAt: StateFlow<Long> = _nextWateringAt.asStateFlow()

    private val _isDailyBattleDone = MutableStateFlow(false)
    val isDailyBattleDone: StateFlow<Boolean> = _isDailyBattleDone.asStateFlow()
    private var dailyBattleJob: Job? = null

    fun start() {
        // 只在引擎运行时启用后台兜底，暂停/停止后再次启动会重新建立调度。
        runCatching {
            WorkScheduler.startPeriodicWatering(context)
            Logger.i(TAG, "WorkManager 兜底周期任务已启动")
        }.onFailure { Logger.e(TAG, "启动 WorkManager 失败", it) }
        scheduleDailyBattleCheck()
    }

    fun getWaterInterval(): Int =
        prefs.getInt(KEY_WATER_INTERVAL, DEFAULT_WATER_INTERVAL)

    fun setWaterInterval(minutes: Int) {
        prefs.edit().putInt(KEY_WATER_INTERVAL, minutes).apply()
        // 同步更新 WorkManager 周期（作为兜底；实际时间点仍由 Alarm 控制）
        runCatching {
            WorkScheduler.startPeriodicWatering(context, intervalMinutes = 15L)
        }
    }

    fun scheduleNextWatering() {
        val intervalMin = getWaterInterval()
        val triggerAt = System.currentTimeMillis() + intervalMin * 60 * 1000L
        val intent = wateringIntent()
        val pi = PendingIntent.getBroadcast(
            context, ALARM_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Android 12+ 需要判断 canScheduleExactAlarms
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    } else {
                        Logger.w(TAG, "无 SCHEDULE_EXACT_ALARM 权限，降级为 inexact Alarm + WorkManager")
                        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                        // 强制确保 WorkManager 在跑
                        runCatching { WorkScheduler.startPeriodicWatering(context) }
                    }
                } else {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
            _nextWateringAt.value = triggerAt
            Log.i(TAG, "下次浇水闹钟已设置: $triggerAt (${intervalMin} 分钟后)")
        } catch (e: SecurityException) {
            Log.e(TAG, "无权限设置精确闹钟，使用普通闹钟 + WorkManager 兜底", e)
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            runCatching { WorkScheduler.startPeriodicWatering(context) }
            _nextWateringAt.value = triggerAt
        }
    }

    fun scheduleImmediateWatering() {
        val triggerAt = System.currentTimeMillis() + 500L
        val intent = wateringIntent()
        val pi = PendingIntent.getBroadcast(
            context, ALARM_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        Log.i(TAG, "已设置立即浇水闹钟")
    }

    fun cancelAllAlarms() {
        val intent = wateringIntent()
        val pi = PendingIntent.getBroadcast(
            context, ALARM_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pi)
        runCatching { WorkScheduler.cancelPeriodicWatering(context) }
        dailyBattleJob?.cancel()
        dailyBattleJob = null
        _nextWateringAt.value = 0L
        Log.i(TAG, "已取消所有闹钟与 WorkManager 任务")
    }

    fun markDailyBattleDone() {
        val today = LocalDate.now().toString()
        prefs.edit().putString(KEY_LAST_BATTLE_DATE, today).apply()
        _isDailyBattleDone.value = true
        Log.i(TAG, "每日对战已标记完成: $today")
    }

    fun isDailyBattleDone(): Boolean {
        val lastDate = prefs.getString(KEY_LAST_BATTLE_DATE, "") ?: ""
        val today = LocalDate.now().toString()
        val done = lastDate == today
        _isDailyBattleDone.value = done
        return done
    }

    fun scheduleDailyBattleCheck() {
        if (dailyBattleJob?.isActive == true) return
        dailyBattleJob = scope.launch {
            while (isActive) {
                val now = LocalTime.now()
                if (now.hour in 8..23) {
                    if (!isDailyBattleDone()) {
                        Log.d(TAG, "检测到今日对战未完成，当前时间: ${now.hour}:${now.minute}")
                    }
                }
                delay(60 * 60 * 1000L)
            }
        }
        Log.i(TAG, "每日对战检查已启动")
    }

    private fun wateringIntent(): Intent =
        Intent(context, WateringAlarmReceiver::class.java).apply {
            action = WateringAlarmReceiver.ACTION_WATERING_ALARM
        }

    fun destroy() {
        cancelAllAlarms()
        scope.cancel()
    }

    fun getNextWateringTimeDisplay(): String {
        val ts = _nextWateringAt.value
        if (ts <= 0) return "未设置"
        val remainingMs = ts - System.currentTimeMillis()
        if (remainingMs <= 0) return "即将触发"
        val minutes = remainingMs / 60000
        val hours = minutes / 60
        return if (hours > 0) "${hours}小时${minutes % 60}分钟后" else "${minutes}分钟后"
    }

    /** Android 12+ 是否拥有 SCHEDULE_EXACT_ALARM 权限 */
    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else true
    }
}
