package com.farmbot.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.farmbot.accessibility.FarmAccessibilityService
import com.farmbot.metrics.MetricsCollector
import com.farmbot.ocr.OcrHelper
import com.farmbot.recognition.TemplateCollector
import com.farmbot.recognition.TemplateMatcher
import com.farmbot.util.Logger
import com.farmbot.util.RandomDelay

/**
 * 动作执行器 — 融合 HonorFarmBot 版本
 *
 * 职责：把"识别 → 延迟 → 点击"的组合封装成语义化高层操作：
 * - findAndClick / findAndClickAll
 * - waitForAppear / waitForDisappear
 * - scrollToFind
 * - smartClick (模板优先 + OCR兜底 + 文字匹配三级降级)
 * - executeSequence (批量动作序列)
 * - handleDialog (弹窗处理)
 *
 * 全部识别动作自动埋点到 MetricsCollector（用于成功率统计与阈值自适应）。
 */
class ActionExecutor(
    private val context: Context,
    private val accessibility: FarmAccessibilityService?,
    private val templateMatcher: TemplateMatcher,
    private val templateCollector: TemplateCollector,
    private val ocrHelper: OcrHelper
) {

    private val metrics: MetricsCollector by lazy { MetricsCollector.getInstance(context) }

    data class ActionResult(
        val success: Boolean,
        val message: String,
        val x: Int = 0,
        val y: Int = 0
    )

    // ==================== 基础：识别+点击 ====================

    fun findAndClick(
        screenshot: Bitmap,
        templateName: String,
        description: String = "",
        maxRetries: Int = 3,
        threshold: Double? = null
    ): ActionResult {
        val template = templateCollector.loadTemplate(templateName)
            ?: return ActionResult(false, "模板不存在: $templateName")

        for (attempt in 1..maxRetries) {
            val t0 = System.currentTimeMillis()
            val result = templateMatcher.findTemplate(
                screenshot, template, templateName, useMask = true
            )
            val latency = System.currentTimeMillis() - t0
            val pass = threshold?.let { result.confidence >= it } ?: (result.confidence >= 0.75)
            // 埋点
            metrics.record(templateName, found = result.found && pass, confidence = result.confidence, latencyMs = latency)
            if (result.found && pass) {
                RandomDelay.sleep(RandomDelay.hesitationDelay())
                val ok = accessibility?.click(result.x, result.y) ?: false
                if (ok) {
                    Logger.i("ActionExecutor",
                        "✓ 点击[$description] (${result.x},${result.y}) 置信度=${"%.2f".format(result.confidence)} 尝试$attempt/$maxRetries")
                    return ActionResult(true, "点击成功", result.x, result.y)
                }
            }
            RandomDelay.sleepWithJitter(500)
        }
        Logger.w("ActionExecutor", "✗ 未找到[$description] 重试$maxRetries 次后失败")
        return ActionResult(false, "未找到目标: $description")
    }

/**
 * 在整屏查找模板，并依次点击全部命中 */
    fun findAndClickAll(
        screenshot: Bitmap,
        templateName: String,
        description: String = "",
        maxCount: Int = 20
    ): Int {
        val template = templateCollector.loadTemplate(templateName) ?: return 0
        val t0 = System.currentTimeMillis()
        val results = templateMatcher.findAllTemplates(screenshot, template, templateName)
        metrics.record(templateName, found = results.isNotEmpty(), confidence = results.firstOrNull()?.confidence ?: 0.0, latencyMs = System.currentTimeMillis() - t0)
        var successCount = 0
        for ((index, match) in results.take(maxCount).withIndex()) {
            RandomDelay.sleep(RandomDelay.batchInterval(index, results.size))
            if (accessibility?.click(match.x, match.y) == true) successCount++
            Logger.d("ActionExecutor", "批量点击[$description] ($successCount/${results.size})")
        }
        return successCount
    }

/**
 * 在指定区域查找模板并点击命中 */
    fun findAndClickInRegion(
        screenshot: Bitmap,
        templateName: String,
        region: Rect,
        description: String = ""
    ): ActionResult {
        val template = templateCollector.loadTemplate(templateName)
            ?: return ActionResult(false, "模板不存在: $templateName")
        val cvRect = org.opencv.core.Rect(region.left, region.top, region.width(), region.height())
        val t0 = System.currentTimeMillis()
        val result = templateMatcher.findTemplate(screenshot, template, templateName, cvRect)
        metrics.record(templateName, found = result.found, confidence = result.confidence, latencyMs = System.currentTimeMillis() - t0)
        if (result.found) {
            accessibility?.click(result.x, result.y)
            return ActionResult(true, "区域点击成功", result.x, result.y)
        }
        return ActionResult(false, "区域内未找到: $description")
    }

    // ==================== 滚动查找 ====================

    fun scrollToFind(
        screenshotProvider: () -> Bitmap?,
        templateName: String,
        description: String,
        maxScrolls: Int = 10,
        scrollDistance: Int = 400
    ): ActionResult {
        for (scroll in 0 until maxScrolls) {
            val shot = screenshotProvider() ?: continue
            val tpl = templateCollector.loadTemplate(templateName) ?: break
            val r = templateMatcher.findTemplate(shot, tpl, templateName)
            if (r.found) {
                Logger.i("ActionExecutor", "滚动${scroll}次后找到[$description]")
                return ActionResult(true, "找到目标", r.x, r.y)
            }
            accessibility?.scrollDown(shot.width / 2, shot.height / 2, scrollDistance)
            RandomDelay.sleepWithJitter(800)
        }
        return ActionResult(false, "滚动${maxScrolls}次未找到: $description")
    }

    // ==================== 智能点击：三级降级 ====================

    fun smartClick(
        screenshot: Bitmap,
        templateName: String,
        textAlternatives: List<String>,
        description: String = ""
    ): ActionResult {
        // 1) 模板匹配
        val tpl = templateCollector.loadTemplate(templateName)
        if (tpl != null) {
            val r = templateMatcher.findTemplate(screenshot, tpl, templateName)
            if (r.found && r.confidence >= 0.7) {
                accessibility?.click(r.x, r.y)
                return ActionResult(true, "模板匹配点击成功", r.x, r.y)
            }
        }
        // 2) OCR 文字精确查找
        val ocrDone = java.util.concurrent.atomic.AtomicBoolean(false)
        val ocrResult = java.util.concurrent.atomic.AtomicReference<Pair<Int, Int>?>(null)
        val latch = java.util.concurrent.CountDownLatch(1)
        for (text in textAlternatives) {
            ocrHelper.findTextPosition(screenshot, text) { x, y ->
                if (x != 0 || y != 0) {
                    ocrResult.set(Pair(x, y))
                    ocrDone.set(true)
                }
                latch.countDown()
            }
            if (latch.await(3, java.util.concurrent.TimeUnit.SECONDS) && ocrDone.get()) {
                val (x, y) = ocrResult.get()!!
                accessibility?.click(x, y)
                Logger.i("ActionExecutor", "OCR 定位文字[$text] (${x},${y})")
                return ActionResult(true, "OCR 文字匹配成功", x, y)
            }
        }
        // 3) 文字控件匹配 (无障碍)
        for (text in textAlternatives) {
            if (accessibility?.clickByText(text) == true) {
                Logger.i("ActionExecutor", "无障碍文字匹配[$text]成功")
                return ActionResult(true, "无障碍文字匹配成功")
            }
            if (accessibility?.clickByText(text, exact = false) == true) {
                Logger.i("ActionExecutor", "无障碍模糊文字[$text]成功")
                return ActionResult(true, "无障碍模糊匹配成功")
            }
        }
        return ActionResult(false, "所有方法均未找到: $description")
    }

    // ==================== 等待出现/消失 ====================

    fun waitForAppear(
        screenshotProvider: () -> Bitmap?,
        templateName: String,
        description: String,
        timeoutMs: Long = 10000
    ): ActionResult {
        val start = System.currentTimeMillis()
        val tpl = templateCollector.loadTemplate(templateName)
            ?: return ActionResult(false, "模板不存在: $templateName")
        while (System.currentTimeMillis() - start < timeoutMs) {
            val shot = screenshotProvider() ?: continue
            val r = templateMatcher.findTemplate(shot, tpl, templateName)
            if (r.found) {
                val cost = System.currentTimeMillis() - start
                Logger.d("ActionExecutor", "等待[$description] 出现耗时${cost}ms")
                return ActionResult(true, "目标出现", r.x, r.y)
            }
            RandomDelay.sleep(300)
        }
        return ActionResult(false, "等待超时: $description")
    }

/**
 * 等待目标从画面消失，超时返回 false */
    fun waitForDisappear(
        screenshotProvider: () -> Bitmap?,
        templateName: String,
        description: String,
        timeoutMs: Long = 10000
    ): ActionResult {
        val start = System.currentTimeMillis()
        val tpl = templateCollector.loadTemplate(templateName)
            ?: return ActionResult(true, "模板不存在，视为已消失")
        while (System.currentTimeMillis() - start < timeoutMs) {
            val shot = screenshotProvider() ?: continue
            val r = templateMatcher.findTemplate(shot, tpl, templateName)
            if (!r.found) {
                val cost = System.currentTimeMillis() - start
                Logger.d("ActionExecutor", "等待[$description] 消失耗时${cost}ms")
                return ActionResult(true, "目标已消失")
            }
            RandomDelay.sleep(300)
        }
        return ActionResult(false, "等待消失超时: $description")
    }

    // ==================== 弹窗处理 ====================

    fun handleDialog(
        screenshot: Bitmap,
        confirmText: String = "确认",
        cancelText: String = "取消",
        shouldConfirm: Boolean = true
    ): ActionResult {
        val confirmTpl = templateCollector.loadTemplate(TemplateCollector.TPL_CONFIRM)
        if (confirmTpl != null) {
            val r = templateMatcher.findTemplate(screenshot, confirmTpl, TemplateCollector.TPL_CONFIRM)
            if (r.found) {
                if (shouldConfirm) {
                    accessibility?.click(r.x, r.y)
                    return ActionResult(true, "确认弹窗", r.x, r.y)
                } else {
                    accessibility?.clickByText(cancelText)
                    return ActionResult(true, "取消弹窗")
                }
            }
        }
        val target = if (shouldConfirm) confirmText else cancelText
        if (accessibility?.clickByText(target) == true)
            return ActionResult(true, "文字确认弹窗")
        return ActionResult(false, "未找到弹窗按钮")
    }

    // ==================== 动作序列批量执行 ====================

    enum class ActionType { CLICK_TEMPLATE, CLICK_TEXT, SMART_CLICK, SWIPE, WAIT, BACK, HOME }

    data class ActionStep(
        val type: ActionType,
        val target: String,       // 模板名/文字/x1,y1,x2,y2/等待ms
        val secondary: String? = null, // SMART_CLICK 备用文字(逗号分隔)
        val description: String = "",
        val isRequired: Boolean = true
    )

/**
 * 按顺序执行一串动作步骤，任一步失败即停止 */
    fun executeSequence(
        screenshotProvider: () -> Bitmap?,
        actions: List<ActionStep>
    ): List<ActionResult> {
        val results = mutableListOf<ActionResult>()
        for (step in actions) {
            val shot = screenshotProvider() ?: break
            val res = when (step.type) {
                ActionType.CLICK_TEMPLATE -> findAndClick(shot, step.target, step.description)
                ActionType.CLICK_TEXT -> {
                    val ok = accessibility?.clickByText(step.target) ?: false
                    ActionResult(ok, if (ok) "文字点击成功" else "未找到文字")
                }
                ActionType.SMART_CLICK -> {
                    val alts = step.secondary?.split(",")?.map { it.trim() } ?: emptyList()
                    smartClick(shot, step.target, alts, step.description)
                }
                ActionType.SWIPE -> {
                    val parts = step.target.split(",")
                    if (parts.size == 4) {
                        val (x1, y1, x2, y2) = parts.map { it.trim().toInt() }
                        accessibility?.swipeBezier(x1, y1, x2, y2)
                        ActionResult(true, "滑动完成")
                    } else ActionResult(false, "滑动参数错误")
                }
                ActionType.WAIT -> {
                    val ms = step.target.toLongOrNull() ?: 1000L
                    RandomDelay.sleep(ms)
                    ActionResult(true, "等待完成")
                }
                ActionType.BACK -> {
                    accessibility?.performGlobalBack()
                    ActionResult(true, "返回")
                }
                ActionType.HOME -> {
                    accessibility?.performGlobalHome()
                    ActionResult(true, "Home")
                }
            }
            results.add(res)
            if (!res.success && step.isRequired) {
                Logger.w("ActionExecutor", "必要步骤失败: ${step.description}")
                break
            }
            RandomDelay.sleep(RandomDelay.clickInterval())
        }
        return results
    }

    // ==================== 作物成熟度检测（颜色+形状） ====================

    fun detectMatureCrops(screenshot: Bitmap, searchRect: Rect? = null): List<TemplateMatcher.MatchResult> {
        val cvRect = if (searchRect != null)
            org.opencv.core.Rect(searchRect.left, searchRect.top, searchRect.width(), searchRect.height())
        else null
        return templateMatcher.findMatureCrops(screenshot, cvRect)
    }
}
