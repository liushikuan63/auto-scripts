package com.farmbot.engine.runtime

import com.farmbot.config.ConfigManager
import com.farmbot.policy.CropDatabase
import com.farmbot.policy.GameRules
import com.farmbot.util.RandomDelay
import kotlinx.coroutines.delay

class OneKeyFarmingSupport(private val rt: FarmRuntime, private val ocr: OcrClickSupport, private val movement: MovementSupport) {

    /** 上次选种时间：一键务农每轮浇水/收获都会触发，限频避免反复开关种子面板 */
    @Volatile private var lastSeedSelectAt = 0L

    internal suspend fun doOneKeyFarming(): Int {
        // 确保"一键务农"可见（需靠近雕像才显示），否则先走位逼近
        if (!ocr.ocrContainsAny("一键务农", "键务农") && !movement.moveTowardsStatue()) {
            rt.log("⚠未找到[一键务农]（需靠近雕像才显示），本轮跳过")
            return 0
        }
        delay(RandomDelay.uniform(700, 1300))
        // v1.4.37: 一键种植使用当前已选种子 → 确认前先按种植配置选种（限频60s，失败不阻断）
        ensureSeedSelected()
        // 点击齿轮(设置)打开"一键务农设置"面板
        if (!openOneKeySettingsPanel()) {
            rt.log("⚠未能打开一键务农设置面板")
            return 0
        }
        delay(RandomDelay.uniform(800, 1500))
        // 面板默认勾选"一键浇水/一键收获/一键种植"，点确定即统一执行
        if (ocr.tryClickTexts("确定", "确认")) {
            rt.log("✓已确认一键务农：一键收获 → 一键种植 → 一键浇水")
            delay(RandomDelay.uniform(2500, 4000))
            return 1
        }
        rt.log("⚠一键务农设置面板未找到确定按钮")
        return 0
    }

    /**
     * 打开"一键务农设置"面板：
     * 一键务农按钮右上方有一个齿轮(设置)图标，点击才弹出面板（点按钮本体不弹出）。
     * 齿轮无文字，需基于一键务农文字相对偏移（实测偏移 +90,-125 @1920x1080），OCR 定位失败则兜底绝对坐标。
     * 点击后轮询 OCR 确认标题"一键务农设置/请选择工作内容"出现。
     */

    internal suspend fun openOneKeySettingsPanel(): Boolean {
        val svc = com.farmbot.accessibility.FarmAccessibilityService.getInstance() ?: return false
        val provider = rt.screenshotProvider ?: return false
        val bmp = runCatching { provider() }.getOrNull() ?: return false
        val scaleX = bmp.width / 1920f
        val scaleY = bmp.height / 1080f
        var gearX = (1350 * scaleX).toInt()
        var gearY = (550 * scaleY).toInt()
        var byOcr = false
        try {
            val latch = java.util.concurrent.CountDownLatch(1)
            rt.ocr.recognizeText(bmp) { _, blocks ->
                val t = blocks.firstOrNull {
                    val clean = it.text.replace("\\s".toRegex(), "")
                    clean.contains("一键务农") || clean.contains("键务农")
                }
                if (t != null) {
                    gearX = t.x + (90 * scaleX).toInt()
                    gearY = t.y - (125 * scaleY).toInt()
                    byOcr = true
                }
                latch.countDown()
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            bmp.recycle()
        }
        val cx = gearX + kotlin.random.Random.nextInt(-8, 9)
        val cy = gearY + kotlin.random.Random.nextInt(-8, 9)
        svc.click(cx, cy)
        rt.log(if (byOcr) "✅OCR定位一键务农→点齿轮($cx,$cy)" else "⚠OCR未定位一键务农，兜底点齿轮($cx,$cy)")
        // 等待面板打开，确认标题出现
        for (i in 0 until 6) {
            delay(RandomDelay.uniform(600, 1000))
            if (ocr.ocrContainsAny("一键务农设置", "请选择工作内容")) {
                rt.log("✅一键务农设置面板已打开")
                return true
            }
        }
        return false
    }

    /** OCR 全屏检测任一关键词是否存在（不点击）。用于确认面板/文字出现。 */

    internal suspend fun ensureSeedSelected(): Boolean {
        val cfg = ConfigManager.getConfig()
        val now = System.currentTimeMillis()
        if (now - lastSeedSelectAt < 60_000L) return true   // 60s 内已选过，跳过（含上次失败限频）
        lastSeedSelectAt = now
        val (cropName, reason) = resolveTargetCrop(cfg)
        if (cropName.isNullOrBlank()) {
            rt.log("⚠种植选种: 无法解析目标作物（等级${cfg.farmLevel}无可种作物），沿用当前种子")
            return false
        }
        rt.log("🌱种植配置[$reason] → 目标作物: $cropName")
        val svc = com.farmbot.accessibility.FarmAccessibilityService.getInstance() ?: return false
        // 右下角"种植"按钮（限定画面下半部，避免命中大厅/面板内同名干扰文字）
        val plantPos = ocr.ocrFindPos("种植") { block, _, height -> block.y > height / 2 } ?: run {
            rt.log("⚠未找到右下角[种植]按钮（可能无空地/不在此页面），本次不选种")
            return false
        }
        svc.click(plantPos.x, plantPos.y)
        rt.log("🌱已点击种植按钮(${plantPos.x},${plantPos.y})，等待种子面板")
        delay(RandomDelay.uniform(1200, 2000))
        // 面板内点击目标作物种子（排除右下角按钮自身）
        var picked = false
        for (i in 0 until 3) {
            val exclusionRadius = (120 * plantPos.imageWidth / 1920f).toInt().coerceAtLeast(60)
            val seedPos = ocr.ocrFindPos(cropName) { block, _, height ->
                !(block.y > height / 2 && kotlin.math.abs(block.x - plantPos.x) < exclusionRadius)
            }
            if (seedPos != null) {
                svc.click(seedPos.x, seedPos.y)
                rt.log("✓已选择种子[$cropName](${seedPos.x},${seedPos.y})")
                picked = true
                break
            }
            delay(RandomDelay.uniform(700, 1200))
        }
        if (!picked) {
            rt.log("⚠种子面板未找到[$cropName]（可能等级未解锁或面板布局差异），本次不选种")
            return false
        }
        delay(RandomDelay.uniform(600, 1100))
        // 面板确认按钮（如有）；无则面板随后续操作自动应用已选种子
        if (ocr.tryClickTexts("确定", "确认")) {
            rt.log("✓已确认选种")
        }
        return true
    }

    /**
     * v1.4.37: 按种植配置解析目标作物。
     *  - 周末双倍期(周五18点~周日)：用户配置周末作物，缺省自动推荐金币最高作物；
     *  - 卡双倍窗口(开启周末双倍策略且距下次双倍≤32小时)：选生长时长最接近剩余时间的作物，
     *    令其恰在周五18:00双倍开启时成熟，收获即吃产量x2；
     *  - 平日(周一~周四)：用户配置平日作物，缺省自动推荐经验最高作物。
     * @return 作物名 to 原因说明（作物名为 null 表示无可用作物）
     */

    internal fun resolveTargetCrop(cfg: com.farmbot.config.FarmConfig): Pair<String?, String> {
        val now = java.util.Calendar.getInstance()
        if (GameRules.isWeekendDouble(now)) {
            val configured = availableConfiguredCrop(cfg.weekendCrop, cfg.farmLevel)
            val name = configured
                ?: CropDatabase.recommend(cfg.farmLevel, CropDatabase.Strategy.COIN_FIRST)?.name
            return name to if (configured != null) "周末双倍期: 自定义作物" else "周末双倍期: 金币作物"
        }
        if (cfg.weekendDoubleAware) {
            val hoursUntil = GameRules.minutesUntilNextDouble(now) / 60.0
            if (hoursUntil > 0.5 && hoursUntil <= 32.0) {
                val pick = CropDatabase.closestToGrowHours(cfg.farmLevel, hoursUntil)
                if (pick != null) {
                    return pick.name to "卡双倍: 距周五${GameRules.DOUBLE_START_HOUR}点约${hoursUntil.toInt()}h → ${pick.name}(${pick.growHours}h成熟)"
                }
            }
        }
        val configured = availableConfiguredCrop(cfg.weekdayCrop, cfg.farmLevel)
        val name = configured
            ?: CropDatabase.recommend(cfg.farmLevel, CropDatabase.Strategy.EXP_FIRST)?.name
        return name to if (configured != null) "平日: 自定义作物" else "平日: 经验作物"
    }


    internal fun availableConfiguredCrop(name: String, farmLevel: Int): String? {
        val normalized = name.trim().takeIf { it.isNotEmpty() } ?: return null
        return CropDatabase.find(normalized)
            ?.takeIf { it.unlockLevel <= farmLevel }
            ?.name
    }

    /**
     * v1.4.37: 走到目标地块——朝 OCR 目标文字推摇杆让角色靠近。
     * 开垦/祝福/偷菜/单块操作均需走到对应地块才可操作：相机跟随角色，
     * 目标文字相对画面中心的偏移即角色需要移动的方向；每步推进后复查，
     * 文字靠近画面中心（角色已贴近）或从画面消失（走到身前/被遮挡）即视为到达。
     * @return true=已接近/到达目标；false=目标未找到或步数用尽（如实返回，不假成功）
     */
}
