package com.farmbot.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.farmbot.screenshot.ScreenshotService
import com.farmbot.util.Logger
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模板采集器 — 融合 HonorFarmBot 版本
 *
 * 16 步模板采集引导：浇水/收获/偷菜/对战/出售/好友入口/农场入口/确认/关闭/开地/升级/投降/匹配/成熟作物/未成熟作物/浇水壶/倒计时区域
 *
 * 附加功能：
 * - 模板导入/导出(zip)
 * - 自动采集/逐步骤采集/裁剪区域采集
 * - 屏幕类型猜测(颜色特征)
 */
class TemplateCollector(private val context: Context) {

    companion object {
        const val TEMPLATE_DIR = "FarmBot/templates"
        const val CROP_DIR = "FarmBot/templates/crops"
        const val BUTTON_DIR = "FarmBot/templates/buttons"
        const val STATE_DIR = "FarmBot/templates/states"

        // === 标准模板文件名 (常量统一) ===
        const val TPL_WATER_BTN = "btn_water.png"
        const val TPL_HARVEST_BTN = "btn_harvest.png"
        const val TPL_STEAL_BTN = "btn_steal.png"
        const val TPL_BATTLE_BTN = "btn_battle.png"
        const val TPL_SELL_BTN = "btn_sell.png"
        const val TPL_FRIEND_ENTER = "btn_friend_enter.png"
        const val TPL_CROP_MATURE = "crop_mature.png"
        const val TPL_CROP_IMMATURE = "crop_immature.png"
        const val TPL_WATERING_CAN = "icon_watering_can.png"
        const val TPL_COUNTDOWN_AREA = "area_countdown.png"
        const val TPL_FARM_ENTER = "btn_enter_farm.png"
        const val TPL_CONFIRM = "btn_confirm.png"
        const val TPL_CLOSE = "btn_close.png"
        const val TPL_EXPAND_LAND = "btn_expand_land.png"
        const val TPL_UPGRADE = "btn_upgrade.png"
        const val TPL_SURRENDER = "btn_surrender.png"
        const val TPL_MATCH_CONFIRM = "btn_match_confirm.png"

        fun standardNameToKey(name: String): String = when (name) {
            TPL_WATER_BTN -> "water_can"
            TPL_HARVEST_BTN -> "harvest"
            TPL_STEAL_BTN -> "steal"
            TPL_BATTLE_BTN -> "battle_entry"
            TPL_SELL_BTN -> "sell"
            TPL_FRIEND_ENTER -> "friend_list"
            TPL_FARM_ENTER -> "home_icon"
            TPL_CONFIRM -> "confirm"
            TPL_CLOSE -> "close"
            TPL_EXPAND_LAND -> "upgrade"
            TPL_UPGRADE -> "upgrade"
            TPL_SURRENDER -> "surrender"
            TPL_MATCH_CONFIRM -> "confirm"
            TPL_CROP_MATURE -> "crop_ripe"
            TPL_CROP_IMMATURE -> "crop_ripe"
            TPL_WATERING_CAN -> "water_can"
            TPL_COUNTDOWN_AREA -> "energy"
            else -> "template"
        }
    }

    private val templateDir: File
    private val cropDir: File
    private val buttonDir: File
    private val stateDir: File
    private var captureService: ScreenshotService? = null
    private val collected = mutableListOf<String>()

    init {
        val base = File(context.getExternalFilesDir(null), TEMPLATE_DIR).parentFile!!
        templateDir = File(context.getExternalFilesDir(null), TEMPLATE_DIR)
        cropDir = File(templateDir, "crops")
        buttonDir = File(templateDir, "buttons")
        stateDir = File(templateDir, "states")
        templateDir.mkdirs(); cropDir.mkdirs(); buttonDir.mkdirs(); stateDir.mkdirs()
        Timber.i("TemplateCollector initialized at: ${templateDir.absolutePath}")
    }

    fun setCaptureService(svc: ScreenshotService?) { captureService = svc }

    // ==================== 采集步骤 ====================

    enum class CollectStep(
        val templateName: String,
        val description: String,
        val hint: String,
        val category: String // buttons / crops / states
    ) {
        WATER_BUTTON(TPL_WATER_BTN, "浇水按钮", "请进入农场，将镜头对准一个需要浇水的空闲地块", "buttons"),
        HARVEST_BUTTON(TPL_HARVEST_BTN, "收获按钮", "请点击一个成熟作物，使其弹出收获按钮", "buttons"),
        STEAL_BUTTON(TPL_STEAL_BTN, "偷菜按钮", "请进入好友农场，对准可偷菜的作物", "buttons"),
        BATTLE_BUTTON(TPL_BATTLE_BTN, "对战开始按钮", "请进入对战界面，确保看到「开始匹配」按钮", "buttons"),
        SELL_BUTTON(TPL_SELL_BTN, "出售按钮", "请进入商店/背包界面，确保看到「出售」按钮", "buttons"),
        FRIEND_ENTER(TPL_FRIEND_ENTER, "好友入口", "请进入好友列表界面", "buttons"),
        FARM_ENTER(TPL_FARM_ENTER, "进入农场按钮", "请确保在游戏主界面，能看到农场入口", "buttons"),
        CONFIRM_BTN(TPL_CONFIRM, "确认按钮", "请触发任意确认弹窗", "buttons"),
        CLOSE_BTN(TPL_CLOSE, "关闭按钮", "请找到任意弹窗的关闭(X)按钮", "buttons"),
        EXPAND_BTN(TPL_EXPAND_LAND, "开地按钮", "请在农场找到「开地/扩建」按钮", "buttons"),
        UPGRADE_BTN(TPL_UPGRADE, "升级按钮", "请在农场找到「升级」按钮", "buttons"),
        SURRENDER_BTN(TPL_SURRENDER, "投降按钮", "请进入对战，打开设置找到「投降」按钮", "buttons"),
        MATCH_CONFIRM(TPL_MATCH_CONFIRM, "匹配确认", "请在匹配界面找到「确认」按钮", "buttons"),
        CROP_MATURE(TPL_CROP_MATURE, "成熟作物", "请将镜头对准一个完全成熟的作物", "crops"),
        CROP_IMMATURE(TPL_CROP_IMMATURE, "未成熟作物", "请将镜头对准一个未成熟的作物", "crops"),
        WATERING_CAN(TPL_WATERING_CAN, "浇水壶图标", "请截取农场中的浇水壶悬浮图标", "states"),
        COUNTDOWN_AREA(TPL_COUNTDOWN_AREA, "倒计时区域", "请截取作物上方的倒计时数字区域", "states");
    }

    fun getAllSteps(): List<CollectStep> = CollectStep.values().toList()

    // ==================== 采集方法 ====================

    private fun categoryDir(cat: String): File = when (cat) {
        "crops" -> cropDir
        "buttons" -> buttonDir
        else -> stateDir
    }

    fun captureRegion(step: CollectStep, region: Rect, cb: (Boolean, String) -> Unit) {
        captureCurrentBitmap { bm ->
            if (bm == null) { cb(false, "截屏失败"); return@captureCurrentBitmap }
            runCatching {
                val safe = Rect(
                    region.left.coerceIn(0, bm.width - 1),
                    region.top.coerceIn(0, bm.height - 1),
                    region.right.coerceIn(0, bm.width),
                    region.bottom.coerceIn(0, bm.height)
                )
                if (safe.width() <= 0 || safe.height() <= 0) {
                    cb(false, "裁剪区域无效"); return@captureCurrentBitmap
                }
                val cropped = Bitmap.createBitmap(bm, safe.left, safe.top, safe.width(), safe.height())
                val file = File(categoryDir(step.category), step.templateName)
                FileOutputStream(file).use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }
                collected.add(step.templateName)
                Logger.i("TemplateCollector", "Template saved: ${file.absolutePath} (${cropped.width}x${cropped.height})")
                cb(true, file.absolutePath)
            }.onFailure {
                Logger.e("TemplateCollector", "captureRegion failed", it)
                cb(false, it.message ?: "Unknown error")
            }
        }
    }

    fun captureFullScreen(step: CollectStep, cb: (Boolean, String) -> Unit) {
        captureCurrentBitmap { bm ->
            if (bm == null) { cb(false, "截屏失败"); return@captureCurrentBitmap }
            runCatching {
                val target = categoryDir(step.category)
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val stamped = step.templateName.replace(".png", "_$ts.png")
                FileOutputStream(File(target, stamped)).use {
                    bm.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                val std = File(target, step.templateName)
                FileOutputStream(std).use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
                collected.add(step.templateName)
                Logger.i("TemplateCollector", "FullScreen: ${std.absolutePath}")
                cb(true, std.absolutePath)
            }.onFailure { cb(false, it.message ?: "error") }
        }
    }

    private inline fun captureCurrentBitmap(crossinline cb: (Bitmap?) -> Unit) {
        val svc = captureService
        if (svc != null) {
            val mat = svc.captureFresh()
            if (mat != null) {
                cb(matToBitmap(mat))
                return
            }
        }
        // Fallback: 回调传 null
        cb(null)
    }

    private fun matToBitmap(mat: org.opencv.core.Mat): Bitmap? {
        // Stub → 使用 OpenCV android Utils 真实实现，在 AAR 存在时生效
        return try {
            val bmp = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
            org.opencv.android.Utils.matToBitmap(mat, bmp)
            bmp
        } catch (_: Throwable) { null }
    }

    /** 自动采集所有步骤（每个步骤保存全屏，用户需手动切界面） */
    fun autoCollectAll(
        onProgress: (Int, Int, String) -> Unit,
        onComplete: (List<String>) -> Unit
    ) {
        val steps = getAllSteps()
        Thread {
            steps.forEachIndexed { i, step ->
                onProgress(i + 1, steps.size, "正在采集: ${step.description}")
                val latch = java.util.concurrent.CountDownLatch(1)
                captureFullScreen(step) { _, _ -> latch.countDown() }
                runCatching { latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS) }
                Thread.sleep(3000L)
            }
            onComplete(collected.toList())
        }.start()
    }

    // ==================== 查询 & 加载 ====================

    fun hasTemplate(name: String): Boolean =
        listOf(buttonDir, cropDir, stateDir).any { File(it, name).exists() }

    fun getTemplateFile(name: String): File? {
        return listOf(
            File(buttonDir, name), File(cropDir, name), File(stateDir, name)
        ).firstOrNull { it.exists() }
    }

    fun loadTemplate(name: String): Bitmap? {
        val f = getTemplateFile(name) ?: return null
        return try { BitmapFactory.decodeFile(f.absolutePath) } catch (t: Throwable) {
            Logger.e("TemplateCollector", "loadTemplate: $name", t); null
        }
    }

    fun deleteTemplate(name: String): Boolean {
        val f = getTemplateFile(name) ?: return false
        return if (f.delete()) { collected.remove(name); true } else false
    }

    fun clearAllTemplates() {
        listOf(buttonDir, cropDir, stateDir).forEach { d -> d.listFiles()?.forEach { it.delete() } }
        collected.clear()
    }

    fun getCollectedTemplates(): List<String> = collected.toList()

    fun getCollectionInfo(): String = "已采集: ${collected.size} / ${CollectStep.values().size}"

    // ==================== 导入/导出 Zip ====================

    fun exportTemplates(zipFile: File): Boolean {
        return runCatching {
            val files = mutableListOf<File>()
            listOf(buttonDir, cropDir, stateDir).forEach { d -> d.listFiles()?.let(files::addAll) }
            java.util.zip.ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                files.forEach { f ->
                    zos.putNextEntry(java.util.zip.ZipEntry(f.name))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            true
        }.getOrDefault(false)
    }

    fun importTemplates(zipFile: File): Int {
        var count = 0
        runCatching {
            java.util.zip.ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    val dir = when {
                        name.contains("crop") -> cropDir
                        name.contains("btn") || name.contains("icon") -> buttonDir
                        else -> stateDir
                    }
                    val out = File(dir, name)
                    FileOutputStream(out).use { zis.copyTo(it) }
                    collected.add(name)
                    count++
                    entry = zis.nextEntry
                }
            }
        }
        return count
    }

    // ==================== 屏幕类型猜测 ====================

    enum class ScreenType { FARM, BATTLE, FRIEND_LIST, SHOP, MATCHING, LOADING, UNKNOWN }

    fun detectCurrentScreen(cb: (ScreenType) -> Unit) {
        captureCurrentBitmap { bm ->
            if (bm == null) cb(ScreenType.UNKNOWN) else cb(analyzeScreen(bm))
        }
    }

    private fun analyzeScreen(bm: Bitmap): ScreenType {
        val w = bm.width; val h = bm.height
        val bottom = Bitmap.createBitmap(bm, 0, (h * 0.8f).toInt(), w, (h * 0.2f).toInt())
        val top = Bitmap.createBitmap(bm, 0, 0, w, (h * 0.15f).toInt())
        val (br, bg, bb) = averageColor(bottom)
        val (tr, tg, _) = averageColor(top)
        return when {
            bg > 150 -> ScreenType.BATTLE
            tr > 80 -> ScreenType.FARM
            bb > 120 -> ScreenType.FRIEND_LIST
            else -> ScreenType.UNKNOWN
        }
    }

    private fun averageColor(bm: Bitmap): Triple<Int, Int, Int> {
        var r = 0; var g = 0; var b = 0
        val pixels = IntArray(bm.width * bm.height)
        bm.getPixels(pixels, 0, bm.width, 0, 0, bm.width, bm.height)
        for (p in pixels) {
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
        }
        val c = pixels.size.coerceAtLeast(1)
        return Triple(r / c, g / c, b / c)
    }
}
