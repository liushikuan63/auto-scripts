package com.farmbot.vision

import android.content.Context
import android.util.Log
import com.farmbot.config.ConfigManager
import com.farmbot.config.FarmConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.Mat

class ApkTemplateImporter private constructor() {

    companion object {
        private const val TAG = "ApkTemplateImporter"

        private val TEMPLATE_KEYWORDS = mapOf(
            "water_can" to listOf("water", "watering", "can", "浇水", "水壶"),
            "harvest" to listOf("harvest", "reap", "collect", "收获", "采摘"),
            "steal" to listOf("steal", "rob", "偷", "盗"),
            "bless" to listOf("bless", "pray", "祝福", "祈祷"),
            "crop_ripe" to listOf("ripe", "mature", "grow", "成熟", "作物"),
            "scarecrow" to listOf("scarecrow", "straw", "稻草人", "一键"),
            "battle_entry" to listOf("battle", "fight", "match", "对战", "战斗"),
            "surrender" to listOf("surrender", "giveup", "投降"),
            "home_icon" to listOf("home", "hall", "lobby", "大厅", "首页"),
            "friend_list" to listOf("friend", "buddy", "好友"),
            "confirm" to listOf("confirm", "ok", "yes", "确定", "确认"),
            "close" to listOf("close", "cancel", "back", "关闭", "取消"),
            "setting" to listOf("setting", "config", "setup", "设置"),
            "gold" to listOf("gold", "coin", "money", "金币", "金"),
            "diamond" to listOf("diamond", "gem", "crystal", "钻石", "宝石"),
            "energy" to listOf("energy", "power", "stamina", "体力", "能量"),
            "shop" to listOf("shop", "store", "market", "商店", "购买"),
            "sell" to listOf("sell", "sale", "出售", "售卖"),
            "upgrade" to listOf("upgrade", "levelup", "升级"),
            "quest" to listOf("quest", "task", "mission", "任务", "目标"),
            "reward" to listOf("reward", "prize", "奖励", "奖品"),
            "daily" to listOf("daily", "everyday", "每日"),
            "vip" to listOf("vip", "privilege", "特权"),
            "newbie" to listOf("newbie", "beginner", "新手", "新手指引"),
            "banner" to listOf("banner", "popup", "公告", "活动"),
            "notice" to listOf("notice", "message", "消息", "通知"),
            "rank" to listOf("rank", "level", "rankings", "排行", "等级"),
            "season" to listOf("season", "event", "赛季", "活动"),
            "festival" to listOf("festival", "celebrate", "节日", "庆典")
        )

        @Volatile
        private var instance: ApkTemplateImporter? = null

        fun getInstance(): ApkTemplateImporter {
            return instance ?: synchronized(this) {
                instance ?: ApkTemplateImporter().also { instance = it }
            }
        }
    }

    data class ImportResult(
        val success: Boolean,
        val templatesImported: Int,
        val totalImagesFound: Int,
        val matchedTemplates: Map<String, Int>,
        val failedTemplates: List<String>,
        val message: String
    )

    data class TemplateMatch(
        val templateKey: String,
        val resourcePath: String,
        val confidence: Double
    )

    private val reader = ApkResourceReader.getInstance()
    private val recognizer = ImageRecognizer.getInstance()
    private val importHistory = mutableMapOf<String, List<String>>()

    suspend fun importFromApk(
        context: Context,
        apkPath: String,
        selectedKeywords: Set<String>? = null
    ): ImportResult = withContext(Dispatchers.IO) {
        val summary = reader.scanApk(apkPath)
        if (summary == null) {
            return@withContext ImportResult(
                success = false,
                templatesImported = 0,
                totalImagesFound = 0,
                matchedTemplates = emptyMap(),
                failedTemplates = emptyList(),
                message = "无法扫描APK文件"
            )
        }

        val entries = summary.imageEntriesList
        val keywordsToUse = selectedKeywords ?: TEMPLATE_KEYWORDS.keys.toSet()
        val matched = mutableMapOf<String, Int>()
        val failed = mutableListOf<String>()
        var imported = 0

        for (key in keywordsToUse) {
            val keywordList = TEMPLATE_KEYWORDS[key] ?: continue
            val matches = findBestMatches(entries, keywordList, maxMatches = 3)

            if (matches.isNotEmpty()) {
                var successCount = 0
                for (match in matches) {
                    val mat = reader.extractAndDecodeToMat(apkPath, match.resourcePath)
                    if (mat != null && !mat.empty()) {
                        val templateId = "tpl_${key}_${successCount}"
                        if (recognizer.loadTemplate(templateId, mat)) {
                            successCount++
                            imported++
                            Log.i(TAG, "Imported template: $templateId from ${match.resourcePath}")
                        }
                    }
                }
                if (successCount > 0) {
                    matched[key] = successCount
                } else {
                    failed.add(key)
                }
            } else {
                failed.add(key)
            }
        }

        importHistory[apkPath] = matched.keys.toList()

        ImportResult(
            success = imported > 0,
            templatesImported = imported,
            totalImagesFound = entries.size,
            matchedTemplates = matched,
            failedTemplates = failed,
            message = if (imported > 0) "成功导入 $imported 个模板" else "未找到匹配的模板图片"
        )
    }

    suspend fun importFromInstalledApp(
        context: Context,
        packageName: String,
        selectedKeywords: Set<String>? = null
    ): ImportResult = withContext(Dispatchers.IO) {
        val apkPath = reader.getInstalledAppApkPath(context, packageName)
            ?: return@withContext ImportResult(
                success = false,
                templatesImported = 0,
                totalImagesFound = 0,
                matchedTemplates = emptyMap(),
                failedTemplates = emptyList(),
                message = "无法获取应用APK路径"
            )
        importFromApk(context, apkPath, selectedKeywords)
    }

    suspend fun importFromInstalledFarmApp(
        context: Context,
        selectedKeywords: Set<String>? = null
    ): ImportResult = withContext(Dispatchers.IO) {
        val farmApps = reader.getInstalledFarmApps(context)
        if (farmApps.isEmpty()) {
            return@withContext ImportResult(
                success = false,
                templatesImported = 0,
                totalImagesFound = 0,
                matchedTemplates = emptyMap(),
                failedTemplates = emptyList(),
                message = "未检测到已安装的农场游戏应用"
            )
        }

        val app = farmApps.first()
        Log.i(TAG, "Importing from ${app.appName} (${app.packageName})")
        importFromInstalledApp(context, app.packageName, selectedKeywords)
    }

    fun scanAvailableTemplates(apkPath: String): Map<String, List<TemplateMatch>> {
        val entries = reader.listImageResources(apkPath)
        val result = mutableMapOf<String, List<TemplateMatch>>()

        for ((key, keywords) in TEMPLATE_KEYWORDS) {
            val matches = findBestMatches(entries, keywords, maxMatches = 5)
            if (matches.isNotEmpty()) {
                result[key] = matches
            }
        }

        return result
    }

    fun getAvailableFarmApps(context: Context): List<ApkResourceReader.InstalledAppInfo> {
        return reader.getInstalledFarmApps(context)
    }

    fun getImportHistory(): Map<String, List<String>> = importHistory.toMap()

    fun clearImportHistory() {
        importHistory.clear()
    }

    fun getTemplateKeywords(): Map<String, List<String>> = TEMPLATE_KEYWORDS.toMap()

    private fun findBestMatches(
        entries: List<ApkResourceReader.ResourceEntry>,
        keywords: List<String>,
        maxMatches: Int
    ): List<TemplateMatch> {
        val scored = mutableListOf<Pair<ApkResourceReader.ResourceEntry, Double>>()

        for (entry in entries) {
            val entryNameLower = entry.name.lowercase()
            val entryPathLower = entry.path.lowercase()
            var score = 0.0

            for (keyword in keywords) {
                val kwLower = keyword.lowercase()
                if (entryNameLower.contains(kwLower)) {
                    score += 10.0
                }
                if (entryPathLower.contains(kwLower)) {
                    score += 5.0
                }
            }

            if (score > 0) {
                scored.add(entry to score)
            }
        }

        return scored
            .sortedByDescending { it.second }
            .take(maxMatches)
            .map { (entry, score) ->
                TemplateMatch(
                    templateKey = "",
                    resourcePath = entry.path,
                    confidence = score
                )
            }
    }
}
