/**
 * 增强版 TemplateMatcher — 融合 HonorFarmBot 版本
 */

package com.farmbot.recognition

import android.graphics.Bitmap
import com.farmbot.util.Logger
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * 增强版 TemplateMatcher — 融合 HonorFarmBot 版本
 *
 * 新增: findAllTemplates (mask NMS 多结果), findMatureCrops (HSV 金色轮廓), createAlphaMask, waitForTemplate, detectDominantColor, isCropMature, hasHarvestGlow, findInGrid.
 */
class TemplateMatcher {

    data class MatchResult(
        val found: Boolean,
        val x: Int,
        val y: Int,
        val confidence: Double,
        val templateName: String = ""
    )

    private var threshold: Double = 0.85

/**
 * 设置匹配相似度阈值
 */
    fun setThreshold(t: Double) { threshold = t }
/**
 * 当前匹配阈值
 */
    fun getThreshold(): Double = threshold

    // ==================== 核心查找 ====================

    fun findTemplate(
        screenshot: Bitmap,
        template: Bitmap,
        templateName: String = "",
        searchRegion: Rect? = null,
        useMask: Boolean = true
    ): MatchResult {
        if (screenshot.width <= 0 || screenshot.height <= 0) return MatchResult(false, 0, 0, 0.0)

        val screenMat = bitmapToMat(screenshot)
        val tplMat = bitmapToMat(template)
        val searchMat = if (searchRegion != null) Mat(screenMat, searchRegion) else screenMat
        val scales = listOf(0.9, 0.95, 1.0, 1.05, 1.1)
        var best = MatchResult(false, 0, 0, 0.0, templateName)

        for (scale in scales) {
            val scaled = resizeMat(tplMat, scale)
            val mask = if (useMask) createAlphaMask(scaled) else Mat()
            val result = Mat()
            if (useMask && !mask.empty()) {
                try { Imgproc.matchTemplate(searchMat, scaled, result, Imgproc.TM_CCOEFF_NORMED, mask) }
                catch (_: Throwable) { Imgproc.matchTemplate(searchMat, scaled, result, Imgproc.TM_CCOEFF_NORMED) }
            } else {
                Imgproc.matchTemplate(searchMat, scaled, result, Imgproc.TM_CCOEFF_NORMED)
            }
            val mm = Core.minMaxLoc(result)
            if (mm.maxVal > best.confidence) {
                var cx = mm.maxLoc.x.toInt() + scaled.cols() / 2
                var cy = mm.maxLoc.y.toInt() + scaled.rows() / 2
                if (searchRegion != null) { cx += searchRegion.x; cy += searchRegion.y }
                best = MatchResult(mm.maxVal >= 0.5, cx, cy, mm.maxVal, templateName)
            }
            result.release()
            if (!mask.empty()) mask.release()
        }
        screenMat.release(); tplMat.release()
        if (best.found) Timber.d("Match [$templateName]=(${best.x},${best.y}) conf=${"%.3f".format(best.confidence)}")
        return best
    }

/**
 * 在图中查找全部命中位置
 */
    fun findAllTemplates(
        screenshot: Bitmap,
        template: Bitmap,
        templateName: String = "",
        threshold: Double = this.threshold,
        maxResults: Int = 20
    ): List<MatchResult> {
        if (screenshot.width <= 0) return emptyList()
        val screenMat = bitmapToMat(screenshot)
        val tplMat = bitmapToMat(template)
        val result = Mat()
        Imgproc.matchTemplate(screenMat, tplMat, result, Imgproc.TM_CCOEFF_NORMED)
        val mask = Mat.zeros(result.size(), CvType.CV_8UC1)
        val out = mutableListOf<MatchResult>()
        val suppressR = (tplMat.cols() * 0.5).toInt().coerceAtLeast(10)
        for (i in 0 until maxResults) {
            val mm = Core.minMaxLoc(result, mask)
            if (mm.maxVal < threshold) break
            val cx = mm.maxLoc.x.toInt() + tplMat.cols() / 2
            val cy = mm.maxLoc.y.toInt() + tplMat.rows() / 2
            out.add(MatchResult(true, cx, cy, mm.maxVal, templateName))
            val rect = Rect(
                (mm.maxLoc.x - suppressR).toInt().coerceAtLeast(0),
                (mm.maxLoc.y - suppressR).toInt().coerceAtLeast(0),
                (suppressR * 2).coerceAtMost(result.cols()),
                (suppressR * 2).coerceAtMost(result.rows())
            )
            val roi = Mat(mask, rect)
            roi.setTo(Scalar(255.0))
            roi.release()
        }
        result.release(); mask.release(); screenMat.release(); tplMat.release()
        Timber.d("findAll [$templateName] count=${out.size}")
        return out
    }

/**
 * 查找成熟作物位置
 */
    fun findMatureCrops(
        screenshot: Bitmap,
        searchRegion: Rect? = null
    ): List<MatchResult> {
        val mat = bitmapToMat(screenshot)
        val search = if (searchRegion != null) Mat(mat, searchRegion) else mat
        val hsv = Mat()
        Imgproc.cvtColor(search, hsv, Imgproc.COLOR_RGB2HSV)
        val lowerGold = Scalar(15.0, 100.0, 100.0)
        val upperGold = Scalar(35.0, 255.0, 255.0)
        val m = Mat()
        Core.inRange(hsv, lowerGold, upperGold, m)
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        Imgproc.morphologyEx(m, m, Imgproc.MORPH_CLOSE, k)
        Imgproc.morphologyEx(m, m, Imgproc.MORPH_OPEN, k)
        val contours = mutableListOf<Mat>()
        val hier = Mat()
        Imgproc.findContours(m, contours, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        val out = mutableListOf<MatchResult>()
        for (c in contours) {
            val area = Imgproc.contourArea(c)
            if (area in 200.0..5000.0) {
                val mom = Imgproc.moments(c)
                if (mom.m00 > 0) {
                    var cx = (mom.m10 / mom.m00).toInt()
                    var cy = (mom.m01 / mom.m00).toInt()
                    if (searchRegion != null) { cx += searchRegion.x; cy += searchRegion.y }
                    out.add(MatchResult(true, cx, cy, (area / 5000.0), "mature_crop"))
                }
            }
            c.release()
        }
        hier.release(); hsv.release(); m.release(); k.release()
        search.release(); mat.release()
        Logger.d("TemplateMatcher", "findMatureCrops => ${out.size}")
        return out
    }

    // ==================== 颜色工具 ====================

    fun detectDominantColor(bm: Bitmap, region: Rect? = null): Int {
        val target = if (region != null)
            Bitmap.createBitmap(bm, region.x, region.y, region.width, region.height) else bm
        val s = Bitmap.createScaledBitmap(target, 50, 50, false)
        val px = IntArray(s.width * s.height)
        s.getPixels(px, 0, s.width, 0, 0, s.width, s.height)
        var r = 0; var g = 0; var b = 0
        for (p in px) { r += android.graphics.Color.red(p); g += android.graphics.Color.green(p); b += android.graphics.Color.blue(p) }
        val c = px.size.coerceAtLeast(1)
        return android.graphics.Color.rgb(r / c, g / c, b / c)
    }

/**
 * 判断该地块作物是否成熟
 */
    fun isCropMature(bm: Bitmap, r: Rect): Boolean {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(detectDominantColor(bm, r), hsv)
        return hsv[0] in 15f..35f && hsv[1] > 0.3f
    }

/**
 * 判断作物是否有可收获的光效
 */
    fun hasHarvestGlow(bm: Bitmap, r: Rect): Boolean {
        val mat = bitmapToMat(bm)
        val roi = Mat(mat, r)
        val gray = Mat()
        Imgproc.cvtColor(roi, gray, Imgproc.COLOR_RGB2GRAY)
        val mean = Core.mean(gray)
        val v = mean.`val`[0]
        gray.release(); roi.release(); mat.release()
        return v > 200.0
    }

    // ==================== 辅助工具 ====================

    private fun bitmapToMat(bm: Bitmap): Mat {
        val m = Mat(bm.height, bm.width, CvType.CV_8UC4)
        Utils.bitmapToMat(bm, m)
        val rgb = Mat()
        Imgproc.cvtColor(m, rgb, Imgproc.COLOR_RGBA2RGB)
        m.release()
        return rgb
    }

    private fun resizeMat(m: Mat, s: Double): Mat {
        if (s == 1.0) return m
        val ns = Size((m.cols() * s).roundToInt().toDouble(), (m.rows() * s).roundToInt().toDouble())
        val out = Mat()
        Imgproc.resize(m, out, ns, 0.0, 0.0, Imgproc.INTER_LINEAR)
        return out
    }

    private fun createAlphaMask(m: Mat): Mat {
        if (m.channels() == 4) {
            val channels = mutableListOf<Mat>()
            Core.split(m, channels)
            val alpha = channels[3]
            channels.forEach { it.release() }
            return alpha
        }
        val s = m.size()
        return Mat(s.height.toInt(), s.width.toInt(), CvType.CV_8UC1).apply {
            setTo(Scalar(255.0))
        }
    }

/**
 * 在网格化的地块中逐格查找
 */
    fun findInGrid(
        bm: Bitmap, tpl: Bitmap,
        gridBounds: Rect, rows: Int, cols: Int, name: String = ""
    ): List<MatchResult> {
        val out = mutableListOf<MatchResult>()
        val cw = gridBounds.width / cols
        val ch = gridBounds.height / rows
        for (r in 0 until rows) for (c in 0 until cols) {
            val cell = Rect(gridBounds.x + c * cw, gridBounds.y + r * ch, cw, ch)
            val res = findTemplate(bm, tpl, name, cell)
            if (res.found) out.add(res)
        }
        return out
    }

/**
 * 轮询等待目标出现
 */
    fun waitForTemplate(
        provider: () -> Bitmap?,
        tpl: Bitmap,
        name: String,
        timeoutMs: Long = 10_000,
        intervalMs: Long = 500
    ): MatchResult {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val shot = provider() ?: continue
            val r = findTemplate(shot, tpl, name)
            if (r.found) return r
            Thread.sleep(intervalMs)
        }
        return MatchResult(false, 0, 0, 0.0, name)
    }
}