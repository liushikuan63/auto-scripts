package com.farmbot.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.farmbot.engine.FarmEngine
import com.farmbot.util.Logger

/**
 * 浇水周期 Worker — 由 WorkManager 周期性触发
 *
 * 作为精确 Alarm 的兜底：即使 SCHEDULE_EXACT_ALARM 被系统撤销，
 * WorkManager 仍能在 Doze 期间周期性触发（最长周期 ≈ 15 分钟）。
 */
class WateringWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val UNIQUE_NAME = "farmbot_watering_periodic"
        const val TAG = "WateringWorker"
    }

/**
 * 兜底浇水任务：定时触发一次浇水
 */
    override suspend fun doWork(): Result {
        return try {
            Logger.i(TAG, "WorkManager 触发浇水任务 start")
            val engine = FarmEngine.getInstance(applicationContext)
            engine.triggerWateringNow()
            Logger.i(TAG, "WorkManager 触发浇水任务 done")
            Result.success()
        } catch (t: Throwable) {
            Logger.e(TAG, "WorkManager 浇水任务失败", t)
            // 失败时让 WorkManager 按退避策略重试
            Result.retry()
        }
    }
}
