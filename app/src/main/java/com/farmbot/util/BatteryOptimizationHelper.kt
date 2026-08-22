package com.farmbot.util

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 电池优化白名单工具 — 检测与一键跳转
 *
 * 作用：把 App 加入"忽略电池优化"白名单，避免厂商 ROM 在 Doze / 后台限制下杀掉前台服务。
 *
 * 用法：
 *   if (!BatteryOptimizationHelper.isIgnoringBatteryOptimizations(ctx)) {
 *       BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(activity)
 *   }
 *
 * 注意：在 Android 11+ 普通应用请求 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 仍是允许的，
 * 但必须在 Manifest 声明该权限；部分厂商（小米/华为/OPPO）需额外跳转自启动管理页。
 */
object BatteryOptimizationHelper {

    /** 是否已加入"忽略电池优化"白名单 */
    @SuppressLint("ServiceCast")
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 弹出"忽略电池优化"系统对话框（一次性授权，最友好）
     * @return true 表示 Intent 成功发起；false 表示无法发起（用户需手动跳转）
     */
    fun requestIgnoreBatteryOptimizations(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${activity.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            true
        } catch (t: Throwable) {
            // 某些 ROM 不支持直接弹窗，跳转电池优化列表
            openBatteryOptimizationSettings(activity)
            false
        }
    }

    /** 跳转到系统"电池优化"列表页（让用户手动找应用并设置为不优化） */
    fun openBatteryOptimizationSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Throwable) {
            // 兜底：打开应用详情
            openAppDetailsSettings(context)
        }
    }

    /** 跳转到应用详情页（权限 / 电池手动管理） */
    fun openAppDetailsSettings(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Throwable) { false }
    }

    /**
     * 跳转厂商自启动管理页（小米 / 华为 / OPPO / vivo / 三星 / 魅族）
     * @return true 表示成功发起跳转
     */
    fun openManufacturerAutoStart(context: Context): Boolean {
        val intents = when (Build.MANUFACTURER.lowercase()) {
            "xiaomi" -> listOf(
                Intent().setComponent(ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                ))
            )
            "huawei", "honor" -> listOf(
                Intent().setComponent(ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )),
                Intent().setComponent(ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"
                ))
            )
            "oppo" -> listOf(
                Intent().setComponent(ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )),
                Intent().setComponent(ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"
                ))
            )
            "vivo" -> listOf(
                Intent().setComponent(ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )),
                Intent().setComponent(ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                ))
            )
            "samsung" -> listOf(
                Intent().setComponent(ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"
                ))
            )
            "meizu" -> listOf(
                Intent().setComponent(ComponentName(
                    "com.meizu.safe",
                    "com.meizu.safe.security.SHOW_APPSEC"
                ))
            )
            else -> emptyList()
        }
        for (i in intents) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
                return true
            } catch (_: Throwable) { /* try next */ }
        }
        // 所有厂商页面都打不开 → 回退到应用详情
        return openAppDetailsSettings(context)
    }

    /** 综合状态描述（用于 UI 显示） */
    fun describeStatus(context: Context): String {
        val battery = isIgnoringBatteryOptimizations(context)
        val manufacturer = Build.MANUFACTURER
        return buildString {
            append("电池优化白名单: ").append(if (battery) "✅ 已加入" else "❌ 未加入")
            appendLine()
            append("厂商: ").append(manufacturer)
            appendLine()
            if (listOf("xiaomi","huawei","honor","oppo","vivo","samsung","meizu")
                .contains(manufacturer.lowercase())) {
                append("⚠️ 建议同时开启厂商自启动管理")
            }
        }
    }
}
