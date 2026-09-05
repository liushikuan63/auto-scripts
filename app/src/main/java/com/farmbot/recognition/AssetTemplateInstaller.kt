package com.farmbot.recognition

import android.content.Context
import com.farmbot.util.Logger
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/**
 * 预置模板安装器 — 首次启动时把 assets 目录下 templates 中的 PNG 文件自动复制到
 * 外部存储的 TemplateCollector 目录（buttons / crops / states）
 *
 * 用户后续可通过 TemplateCollectActivity 重新采集覆盖。
 *
 * assets/templates/ 目录约定：
 *   btn_ 开头的 PNG    → buttons/
 *   crop_ 开头的 PNG   → crops/
 *   其它                → states/
 */
class AssetTemplateInstaller(private val context: Context) {

    companion object {
        private const val ASSET_DIR = "templates"
        private const val SP_NAME = "farmbot_assets"
        private const val KEY_INSTALLED_VERSION = "installed_version"
        private const val CURRENT_VERSION = 1
    }

    private val sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    private val collector = TemplateCollector(context)

    /**
     * @return 实际复制文件数；若已安装且版本一致返回 0
     */
    fun installIfNeeded(): Int {
        if (sp.getInt(KEY_INSTALLED_VERSION, 0) >= CURRENT_VERSION) {
            Timber.d("AssetTemplateInstaller: 已安装过预置模板 v=$CURRENT_VERSION，跳过")
            return 0
        }
        return runCatching {
            val files = listAssetFiles(ASSET_DIR)
            if (files.isEmpty()) {
                Logger.w("AssetTemplateInstaller", "assets/templates 下没有文件，跳过")
                return 0
            }
            var copied = 0
            for (name in files) {
                val target = targetFile(name) ?: continue
                if (target.exists()) continue   // 用户已采集的同名文件优先
                runCatching {
                    context.assets.open("$ASSET_DIR/$name").use { ins ->
                        FileOutputStream(target).use { ins.copyTo(it) }
                    }
                    copied++
                }.onFailure { Logger.e("AssetTemplateInstaller", "复制失败 $name", it) }
            }
            sp.edit().putInt(KEY_INSTALLED_VERSION, CURRENT_VERSION).apply()
            Logger.i("AssetTemplateInstaller", "预置模板安装完成: $copied 个文件 → ${collector.getCollectionInfo()}")
            copied
        }.onFailure {
            Logger.e("AssetTemplateInstaller", "installIfNeeded", it)
        }.getOrDefault(0)
    }

    /** 强制重新安装（覆盖用户已采集文件） */
    fun forceReinstall(): Int {
        sp.edit().putInt(KEY_INSTALLED_VERSION, 0).apply()
        val files = listAssetFiles(ASSET_DIR)
        var copied = 0
        for (name in files) {
            val target = targetFile(name) ?: continue
            runCatching {
                context.assets.open("$ASSET_DIR/$name").use { ins ->
                    FileOutputStream(target).use { ins.copyTo(it) }
                }
                copied++
            }
        }
        sp.edit().putInt(KEY_INSTALLED_VERSION, CURRENT_VERSION).apply()
        Logger.i("AssetTemplateInstaller", "强制重装完成: $copied")
        return copied
    }

    private fun listAssetFiles(dir: String): List<String> = runCatching {
        context.assets.list(dir)?.toList() ?: emptyList()
    }.getOrDefault(emptyList())

    private fun targetFile(name: String): File? {
        // 直接用反射获取 TemplateCollector 的内部目录
        val baseDir = collector.javaClass.getDeclaredField("buttonDir").apply { isAccessible = true }
            .get(collector) as? File ?: return null
        val buttonsDir = baseDir
        val cropsDir = File(baseDir.parentFile, "crops")
        val statesDir = File(baseDir.parentFile, "states")
        buttonsDir.mkdirs(); cropsDir.mkdirs(); statesDir.mkdirs()
        return when {
            name.startsWith("btn_") || name.startsWith("icon_") -> File(buttonsDir, name)
            name.startsWith("crop_") -> File(cropsDir, name)
            else -> File(statesDir, name)
        }
    }
}
