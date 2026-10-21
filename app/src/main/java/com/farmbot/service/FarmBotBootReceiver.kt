/**
 * 开机自启 / 应用更新自启 — 拉起前台服务
 */

package com.farmbot.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.farmbot.util.Logger

/**
 * 开机自启 / 应用更新自启 — 拉起前台服务
 *
 * 注意：需在 Manifest 中声明 BOOT_COMPLETED / MY_PACKAGE_REPLACED 权限。
 */
class FarmBotBootReceiver : BroadcastReceiver() {
/**
 * 开机广播：只做初始化，不直接拉起引擎
 */
    override fun onReceive(context: Context, intent: Intent?) {
        val act = intent?.action ?: return
        Logger.i("FarmBotBootReceiver", "received: $act")
        if (act == Intent.ACTION_BOOT_COMPLETED ||
            act == Intent.ACTION_MY_PACKAGE_REPLACED ||
            act == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            runCatching { FarmBotForegroundService.start(context) }
        }
    }
}