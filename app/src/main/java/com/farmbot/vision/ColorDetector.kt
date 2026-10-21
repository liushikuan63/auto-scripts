/**
 * 颜色识别
 */

package com.farmbot.vision

import android.util.Log
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt

/**
 * 颜色识别
 *
 * 提供红 / 绿 / 蓝 / 黄 / 白 / 黑的标准色域，以及自定义色域、区域取色、
 * 主色统计与按钮激活态判断，用于按颜色区分作物成熟度与界面元素。
 */
object ColorDetector {

    private const val TAG = "ColorDetector"

    data class HSVRange(
        val hueMin: Int,
        val hueMax: Int,
        val saturationMin: Int,
        val saturationMax: Int,
        val valueMin: Int,
        val valueMax: Int
    )

    data class DetectionResult(
        val isDetected: Boolean,
        val matchRatio: Double,
        val pixelCount: Int
    )

/**
 * 红色标准色域
 */
    val RED_RANGE = HSVRange(0, 10, 100, 255, 100, 255)
/**
 * 绿色标准色域
 */
    val GREEN_RANGE = HSVRange(35, 85, 100, 255, 100, 255)
/**
 * 蓝色标准色域
 */
    val BLUE_RANGE = HSVRange(100, 130, 100, 255, 100, 255)
/**
 * 黄色标准色域
 */
    val YELLOW_RANGE = HSVRange(20, 35, 100, 255, 100, 255)
/**
 * 白色标准色域
 */
    val WHITE_RANGE = HSVRange(0, 180, 0, 50, 200, 255)
/**
 * 黑色标准色域
 */
    val BLACK_RANGE = HSVRange(0, 180, 0, 255, 0, 50)

/**
 * 判断图中主要颜色属于哪个标准色域
 */
    fun detectColor(
        source: Mat,
        range: HSVRange,
        minRatio: Double = 0.01
    ): DetectionResult {
        if (source.empty()) {
            return DetectionResult(false, 0.0, 0)
        }

        val hsvMat = Mat()
        Imgproc.cvtColor(source, hsvMat, Imgproc.COLOR_BGR2HSV)

        val lower = Scalar(range.hueMin.toDouble(), range.saturationMin.toDouble(), range.valueMin.toDouble())
        val upper = Scalar(range.hueMax.toDouble(), range.saturationMax.toDouble(), range.valueMax.toDouble())

        val mask = Mat()
        Core.inRange(hsvMat, lower, upper, mask)

        val pixelCount = Core.countNonZero(mask)
        val totalPixels = source.rows() * source.cols()
        val ratio = if (totalPixels > 0) pixelCount.toDouble() / totalPixels else 0.0

        hsvMat.release()
        mask.release()

        return DetectionResult(
            isDetected = ratio >= minRatio,
            matchRatio = ratio,
            pixelCount = pixelCount
        )
    }

/**
 * 判断按钮是否处于激活（高亮）状态
 */
    fun isButtonActive(
        source: Mat,
        activeRange: HSVRange = GREEN_RANGE,
        inactiveRange: HSVRange = GRAY_RANGE,
        minRatio: Double = 0.05
    ): Boolean {
        val activeResult = detectColor(source, activeRange, minRatio)
        val inactiveResult = detectColor(source, inactiveRange, minRatio)

        return activeResult.matchRatio > inactiveResult.matchRatio
    }

/**
 * 取图像主色（HSV）
 */
    fun getDominantColor(source: Mat): IntArray {
        if (source.empty()) return intArrayOf(0, 0, 0)

        val hsvMat = Mat()
        Imgproc.cvtColor(source, hsvMat, Imgproc.COLOR_BGR2HSV)

        val hist = Mat()
        val histSize = MatOfInt(180)
        val ranges = floatArrayOf(0f, 180f)
        Imgproc.calcHist(listOf(hsvMat), MatOfInt(0), Mat(), hist, histSize, ranges)

        val minMax = Core.minMaxLoc(hist)
        val dominantHue = minMax.maxLoc.y.toInt()

        hist.release()
        hsvMat.release()

        return intArrayOf(dominantHue, 0, 0)
    }

/**
 * 按参数构造自定义色域
 */
    fun createCustomRange(
        centerHue: Int,
        hueTolerance: Int = 10,
        minSaturation: Int = 100,
        maxSaturation: Int = 255,
        minValue: Int = 100,
        maxValue: Int = 255
    ): HSVRange {
        val hueMin = (centerHue - hueTolerance).coerceAtLeast(0)
        val hueMax = (centerHue + hueTolerance).coerceAtMost(180)
        return HSVRange(hueMin, hueMax, minSaturation, maxSaturation, minValue, maxValue)
    }

/**
 * 取出指定区域的颜色统计
 */
    fun extractColorRegion(
        source: Mat,
        range: HSVRange
    ): Mat {
        if (source.empty()) return Mat()

        val hsvMat = Mat()
        Imgproc.cvtColor(source, hsvMat, Imgproc.COLOR_BGR2HSV)

        val lower = Scalar(range.hueMin.toDouble(), range.saturationMin.toDouble(), range.valueMin.toDouble())
        val upper = Scalar(range.hueMax.toDouble(), range.saturationMax.toDouble(), range.valueMax.toDouble())

        val mask = Mat()
        Core.inRange(hsvMat, lower, upper, mask)

        hsvMat.release()
        return mask
    }

/**
 * 分析区域颜色构成
 */
    fun analyzeRegion(
        source: Mat,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        range: HSVRange,
        minRatio: Double = 0.01
    ): DetectionResult {
        if (source.empty()) return DetectionResult(false, 0.0, 0)

        val roi = Mat(source, org.opencv.core.Rect(x, y, width, height))
        val result = detectColor(roi, range, minRatio)
        roi.release()
        return result
    }

    private val GRAY_RANGE = HSVRange(0, 180, 0, 50, 50, 150)
}