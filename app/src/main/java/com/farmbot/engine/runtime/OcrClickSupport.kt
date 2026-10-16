package com.farmbot.engine.runtime

import com.farmbot.ocr.OcrHelper
import com.farmbot.util.RandomDelay
import kotlinx.coroutines.delay

internal data class OcrHit(
    val keyword: String,
    val x: Int,
    val y: Int,
    val imageWidth: Int,
    val imageHeight: Int
)

class OcrClickSupport(private val rt: FarmRuntime) {

    internal suspend fun tryClickTexts(vararg texts: String): Boolean {
        if (rt.actionExecutor == null) return false   // 执行器未注入 → 模拟模式
        val svc = com.farmbot.accessibility.FarmAccessibilityService.getInstance() ?: return false
        for (t in texts) {
            if (svc.clickByText(t)) {
                rt.log("✓ 已点击[$t]")
                delay(RandomDelay.uniform(400, 900))
                return true
            }
        }
        for (t in texts) {
            if (svc.clickByText(t, exact = false)) {
                rt.log("✓ 已点击[$t](模糊匹配)")
                delay(RandomDelay.uniform(400, 900))
                return true
            }
        }
        return tryClickViaOcr(svc, texts.toList())
    }

    /**
     * v1.4.32: OCR 中文识别常丢失首字/某些字（实测"一键务农"被识别为"键务农"）。
     * 当目标是"一键务农"时，折叠为"键务农"变体再匹配，避免 OCR 漏字导致找不到按钮。
     */

    internal fun ocrTextVariant(clean: String, target: String): Boolean {
        if (target == "一键务农") return clean.contains("键务农")
        return false
    }

    /**
     * OCR 坐标点击降级层（v1.4.3）：
     * MediaProjection 截屏 → MLKit 中文OCR定位目标文字中心坐标 → dispatchGesture 真实点击。
     */

    internal suspend fun tryClickViaOcr(
        svc: com.farmbot.accessibility.FarmAccessibilityService,
        texts: List<String>
    ): Boolean {
        val provider = rt.screenshotProvider ?: run {
            rt.log("⚠截图通道未就绪（未授权录屏），跳过OCR定位")
            return false
        }
        val bmp = runCatching { provider() }.getOrNull()
        if (bmp == null) {
            rt.log("⚠截屏失败（录屏授权可能已失效）")
            return false
        }
        try {
            // v1.4.4: 单次OCR识别全部文字块后本地匹配多目标，
            // 替代此前逐词重复全屏识别（每词200~500ms，多词时延迟成倍增加）
            val latch = java.util.concurrent.CountDownLatch(1)
            var hit: OcrHelper.TextBlock? = null
            var hitText: String? = null
            rt.ocr.recognizeText(bmp) { _, blocks ->
                val found = texts.firstNotNullOfOrNull { t ->
                    blocks.firstOrNull { b ->
                        val clean = b.text.replace("\\s".toRegex(), "")
                        clean.contains(t) || ocrTextVariant(clean, t)
                    }?.let { t to it }
                }
                if (found != null) { hitText = found.first; hit = found.second }
                latch.countDown()
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            val target = hit
            val t = hitText
            if (target != null && t != null) {
                // OcrHelper.TextBlock 的 x/y 已经是边界框中心，不可再次叠加半宽/半高。
                var cx = target.x
                var cy = target.y
                val scaleX = bmp.width / 1920f
                val scaleY = bmp.height / 1080f
                // v1.4.20: 「王者农场 周末双倍」为横幅卡片，OCR 只命中"王者农场"文字块，
                // 其中心偏右（实测文字中心约 (572,842)，而横幅可点区中心约 (486,810)）。
                // 若按文字中心点击会落到"对战"区域误入模式选择页。对入口横幅做向左校正，
                // 命中横幅主体可点区。偏移量基于真机/模拟器大厅实测的横幅卡片几何关系。
                // v1.4.25: 此前仅 cx-=150（过度修正，且未调 y），实测点 (422,842) 偏离卡片中心
                // (486,810)。按注释实测值改为 (cx-86, cy-32)，命中横幅主体。
                if (t.contains("王者农场") || t.contains("周末双倍")) {
                    if (cx > 300 * scaleX) {
                        cx -= (86 * scaleX).toInt()
                        cy -= (32 * scaleY).toInt()
                    }
                }
                // v1.4.28: 「一键务农」为蓝色圆形图标+下方文字标签，OCR 命中标签文字，可点的圆图标在其上方。
                // 点标签中心会落在图标下方导致面板不开（实测日志：点(1330,688)后仍停留在农场主界面）。
                // 向上偏移命中圆形图标。
                // v1.4.33: 实测文字中心(1330,688)、图标中心约(1290,630)，按 `cx-40, cy-58` 命中圆形按钮主体。
                if (t.contains("一键务农")) {
                    if (cy > 200 * scaleY) {
                        cx -= (40 * scaleX).toInt()
                        cy -= (58 * scaleY).toInt()
                    }
                }
                svc.click(cx, cy)
                rt.log("✅ OCR定位[$t]($cx,$cy) 已点击")
                delay(RandomDelay.uniform(600, 1200))
                return true
            }
            rt.log("⚠OCR未在屏幕中找到: ${texts.joinToString("/")}")
        } finally {
            bmp.recycle()
        }
        return false
    }

    /** 无障碍是否处于可用状态（执行器注入 + 服务连接） */

    internal fun isRealMode(): Boolean =
            rt.actionExecutor != null &&
            com.farmbot.accessibility.FarmAccessibilityService.getInstance() != null

    // ========== 内部实现占位（由 ActionExecutor/ScreenController 替换） ==========


    internal suspend fun ocrContainsAny(vararg keywords: String): Boolean {
        val provider = rt.screenshotProvider ?: return false
        val bmp = runCatching { provider() }.getOrNull() ?: return false
        try {
            val latch = java.util.concurrent.CountDownLatch(1)
            var hit = false
            rt.ocr.recognizeText(bmp) { _, blocks ->
                val full = blocks.joinToString("") { it.text.replace("\\s".toRegex(), "") }
                hit = keywords.any { kw -> full.contains(kw) || blocks.any { it.text.replace("\\s".toRegex(), "").contains(kw) } }
                latch.countDown()
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            return hit
        } catch (t: Throwable) {
            return false
        } finally {
            bmp.recycle()
        }
    }

    /** OCR 定位指定关键词的文本块中心坐标。找不到返回 null。 */

    internal suspend fun ocrFindPos(
        keyword: String,
        filter: ((OcrHelper.TextBlock, Int, Int) -> Boolean)? = null
    ): OcrHit? = ocrFindPos(listOf(keyword), filter)

    /** OCR 按关键词优先级定位第一个文本块，可按截图尺寸过滤位置。 */

    internal suspend fun ocrFindPos(
        keywords: List<String>,
        filter: ((OcrHelper.TextBlock, Int, Int) -> Boolean)? = null
    ): OcrHit? {
        val provider = rt.screenshotProvider ?: return null
        val bmp = runCatching { provider() }.getOrNull() ?: return null
        var hit: OcrHit? = null
        try {
            val latch = java.util.concurrent.CountDownLatch(1)
            rt.ocr.recognizeText(bmp) { _, blocks ->
                val found = keywords.firstNotNullOfOrNull { keyword ->
                    blocks.firstOrNull {
                        val clean = it.text.replace("\\s".toRegex(), "")
                        (clean.contains(keyword) || ocrTextVariant(clean, keyword)) &&
                                (filter == null || filter(it, bmp.width, bmp.height))
                    }?.let { keyword to it }
                }
                if (found != null) {
                    hit = OcrHit(
                        keyword = found.first,
                        x = found.second.x,
                        y = found.second.y,
                        imageWidth = bmp.width,
                        imageHeight = bmp.height
                    )
                }
                latch.countDown()
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            return hit
        } catch (t: Throwable) {
            return null
        } finally {
            bmp.recycle()
        }
    }

    /** 上次选种时间：一键务农每轮浇水/收获都会触发，限频避免反复开关种子面板。 */
    /**
     * v1.4.37: 右下角种植按钮未选种子时，按助手配置选种。
     * 优先级：双倍期内→周末作物(金币)；双倍前卡点窗口→卡双倍(周五18点成熟)；平日→平日作物(经验)。
     * 流程：OCR定位右下角"种植"按钮→点开种子面板→点击目标作物→确定。
     * 一键种植使用当前已选种子，故须在"一键务农"确认前完成选种。
     * 找不到种植按钮/目标作物时如实返回 false（一键种植沿用上次所选种子），不阻断主流程。
     */
}
