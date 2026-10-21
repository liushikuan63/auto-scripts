/**
 * WorkManager 调度入口 — 周期性浇水任务的注册/取消
 */

package com.farmbot.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * WorkManager 调度入口 — 周期性浇水任务的注册/取消
 *
 * 注意：WorkManager 周期任务最短间隔为 15 分钟，因此作为"兜底"：
 * 真实浇水时间点仍由 TaskScheduler 的精确 Alarm 控制；
 * WorkManager 在设备空闲 / Doze 期间持续唤醒引擎做检查与补偿。
 */
object WorkScheduler {

    private const val UNIQUE_NAME = WateringWorker.UNIQUE_NAME

    /** 启动 15 分钟周期浇水检查任务（幂等） */
    fun startPeriodicWatering(context: Context, intervalMinutes: Long = 15L) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(false)
            .setRequiresDeviceIdle(false)
            .build()

        // WorkManager 最短周期 15 分钟
        val minutes = intervalMinutes.coerceAtLeast(15L)
        val req = PeriodicWorkRequestBuilder<WateringWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .addTag("farmbot")
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,   // 已存在则更新参数
            req
        )
    }

    /** 取消周期浇水任务 */
    fun cancelPeriodicWatering(context: Context) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(UNIQUE_NAME)
    }

/**
 * 兜底任务是否已排期
 */
    fun isScheduled(context: Context): Boolean {
        val infos = WorkManager.getInstance(context.applicationContext)
            .getWorkInfosForUniqueWork(UNIQUE_NAME).get() ?: return false
        return infos.any { !it.state.isFinished }
    }
}