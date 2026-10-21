/**
 * FarmBot Application — 全局初始化入口
 */

package com.farmbot

import android.app.Application
import com.farmbot.config.ConfigManager
import com.farmbot.policy.PolicyManager
import com.farmbot.recognition.AssetTemplateInstaller
import com.farmbot.util.Logger
import timber.log.Timber

/**
 * FarmBot Application — 全局初始化入口
 *
 * 任务：
 *  - Timber & Logger 初始化
 *  - ConfigManager 初始化
 *  - OpenCV 初始化(若存在 native 库)
 *  - 预置模板安装（首次启动）
 *  - 策略插件加载与激活恢复
 */
class FarmBotApp : Application() {

    companion object {
        @Volatile lateinit var instance: FarmBotApp
            private set
        @Volatile lateinit var policyManager: PolicyManager
            private set
    }

/**
 * 应用初始化：配置、日志与崩溃兜底
 */
    override fun onCreate() {
        super.onCreate()
        instance = this
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        ConfigManager.init(this)

        // 预置模板安装（首次启动从 assets 复制）
        runCatching {
            val copied = AssetTemplateInstaller(this).installIfNeeded()
            if (copied > 0) Logger.i("FarmBotApp", "预置模板安装: $copied 个文件")
        }

        // 策略插件加载与激活恢复
        runCatching {
            policyManager = PolicyManager(this)
            Logger.i("FarmBotApp", "策略插件加载完成，当前激活: ${policyManager.activePolicy.value.displayName}")
        }

        runCatching {
            // 加载 OpenCV — 如果可用
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                Class.forName("org.opencv.android.OpenCVLoader").getMethod("initLocal").invoke(null)
            }
        }
        Logger.i("FarmBotApp", "onCreate done, debug=${BuildConfig.DEBUG}")
    }
}