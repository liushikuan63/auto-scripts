/**
 * OpenCV 模板加载与查找
 */

package com.farmbot.vision

import android.util.Log
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Range
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.imgproc.MatchTemplate

/**
 * OpenCV 模板加载与查找
 *
 * 模板可来自 assets、文件或字节流；查找支持返回首个匹配与全部匹配，
 * 并提供模板释放接口，避免长期持有 Mat。
 */
class ImageRecognizer private constructor() {

    companion object {
        private const val TAG = "ImageRecognizer"

        @Volatile
        private var instance: ImageRecognizer? = null

        fun getInstance(): ImageRecognizer {
            return instance ?: synchronized(this) {
                instance ?: ImageRecognizer().also { instance = it }
            }
        }

        const val DEFAULT_THRESHOLD = 0.7
        const val DEFAULT_MAX_SCALE = 1.0
        const val DEFAULT_MIN_SCALE = 0.5
        const val DEFAULT_SCALE_STEP = 0.1
        const val DEFAULT_MAX_RESULTS = 10
        const val NMS_OVERLAP_THRESHOLD = 0.4
    }

    data class MatchResult(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val confidence: Double,
        val scale: Double
    ) {
        fun centerX(): Int = x + width / 2
        fun centerY(): Int = y + height / 2
    }

    private val templateCache = mutableMapOf<String, Mat>()
    private val templateInfoCache = mutableMapOf<String, TemplateInfo>()

    private data class TemplateInfo(
        val originalWidth: Int,
        val originalHeight: Int
    )

/**
 * 注册模板 Mat
 */
    fun loadTemplate(id: String, templateMat: Mat): Boolean {
        return try {
            templateInfoCache[id] = TemplateInfo(templateMat.cols(), templateMat.rows())
            templateCache[id] = templateMat.clone()
            Log.d(TAG, "Template loaded: $id (${templateMat.cols()}x${templateMat.rows()})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load template: $id", e)
            false
        }
    }

/**
 * 从 assets 加载模板
 */
    fun loadTemplateFromAssets(id: String, assetPath: String): Boolean {
        return try {
            val stream = android.content.res.Resources.getSystem().assets.open(assetPath)
            val bytes = stream.readBytes()
            stream.close()
            val mat = Imgcodecs.imdecode(MatOfByte(bytes), Imgcodecs.IMREAD_COLOR)
            if (mat.empty()) {
                Log.e(TAG, "Failed to decode template: $assetPath")
                return false
            }
            loadTemplate(id, mat)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load template from assets: $assetPath", e)
            false
        }
    }

/**
 * 从文件加载模板
 */
    fun loadTemplateFromFile(id: String, filePath: String): Boolean {
        return try {
            val mat = Imgcodecs.imread(filePath, Imgcodecs.IMREAD_COLOR)
            if (mat.empty()) {
                Log.e(TAG, "Failed to read template file: $filePath")
                return false
            }
            loadTemplate(id, mat)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load template from file: $filePath", e)
            false
        }
    }

/**
 * 从字节流加载模板
 */
    fun loadTemplateFromBytes(id: String, bytes: ByteArray): Boolean {
        return try {
            val mat = Imgcodecs.imdecode(MatOfByte(bytes), Imgcodecs.IMREAD_COLOR)
            if (mat.empty()) {
                Log.e(TAG, "Failed to decode template bytes for: $id")
                return false
            }
            loadTemplate(id, mat)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load template from bytes: $id", e)
            false
        }
    }

/**
 * 释放单个模板
 */
    fun releaseTemplate(id: String) {
        templateCache.remove(id)?.release()
        templateInfoCache.remove(id)
    }

/**
 * 释放全部模板
 */
    fun releaseAll() {
        templateCache.values.forEach { it.release() }
        templateCache.clear()
        templateInfoCache.clear()
    }

/**
 * 查找首个命中位置
 */
    fun findFirst(
        source: Mat,
        templateId: String,
        threshold: Double = DEFAULT_THRESHOLD,
        minScale: Double = DEFAULT_MIN_SCALE,
        maxScale: Double = DEFAULT_MAX_SCALE,
        scaleStep: Double = DEFAULT_SCALE_STEP
    ): MatchResult? {
        val template = templateCache[templateId] ?: run {
            Log.w(TAG, "Template not found: $templateId")
            return null
        }

        val matches = findAllInternal(source, template, threshold, minScale, maxScale, scaleStep, 1)
        return matches.firstOrNull()
    }

/**
 * 查找全部命中位置
 */
    fun findAll(
        source: Mat,
        templateId: String,
        threshold: Double = DEFAULT_THRESHOLD,
        minScale: Double = DEFAULT_MIN_SCALE,
        maxScale: Double = DEFAULT_MAX_SCALE,
        scaleStep: Double = DEFAULT_SCALE_STEP,
        maxResults: Int = DEFAULT_MAX_RESULTS
    ): List<MatchResult> {
        val template = templateCache[templateId] ?: run {
            Log.w(TAG, "Template not found: $templateId")
            return emptyList()
        }

        return findAllInternal(source, template, threshold, minScale, maxScale, scaleStep, maxResults)
    }

    private fun findAllInternal(
        source: Mat,
        template: Mat,
        threshold: Double,
        minScale: Double,
        maxScale: Double,
        scaleStep: Double,
        maxResults: Int
    ): List<MatchResult> {
        if (source.empty() || template.empty()) return emptyList()

        val results = mutableListOf<MatchResult>()
        var scale = minScale

        while (scale <= maxScale) {
            if (scale != 1.0) {
                val newWidth = (template.cols() * scale).toInt()
                val newHeight = (template.rows() * scale).toInt()
                if (newWidth < 8 || newHeight < 8) break
            }

            val scaledTemplate = if (scale == 1.0) {
                template
            } else {
                val newWidth = (template.cols() * scale).toInt()
                val newHeight = (template.rows() * scale).toInt()
                Imgproc.resize(template, Mat(), Size(newWidth.toDouble(), newHeight.toDouble()))
            }

            val matches = TemplateMatcher.match(source, scaledTemplate, threshold)
            for (m in matches) {
                results.add(
                    MatchResult(
                        x = (m.x / scale).toInt(),
                        y = (m.y / scale).toInt(),
                        width = (m.width / scale).toInt(),
                        height = (m.height / scale).toInt(),
                        confidence = m.confidence,
                        scale = scale
                    )
                )
            }

            if (scale != 1.0) {
                scaledTemplate.release()
            }

            scale += scaleStep
        }

        val nmsResults = applyNMS(results, NMS_OVERLAP_THRESHOLD)
        val sortedResults = nmsResults.sortedByDescending { it.confidence }

        return if (maxResults > 0) sortedResults.take(maxResults) else sortedResults
    }

    private fun applyNMS(
        detections: List<MatchResult>,
        overlapThreshold: Double
    ): List<MatchResult> {
        if (detections.isEmpty()) return emptyList()

        val sorted = detections.sortedByDescending { it.confidence }
        val keep = mutableListOf<MatchResult>()

        for (i in sorted.indices) {
            var shouldKeep = true
            for (k in keep) {
                val iou = calculateIoU(sorted[i], k)
                if (iou > overlapThreshold) {
                    shouldKeep = false
                    break
                }
            }
            if (shouldKeep) {
                keep.add(sorted[i])
            }
        }

        return keep
    }

    private fun calculateIoU(a: MatchResult, b: MatchResult): Double {
        val x1 = maxOf(a.x, b.x)
        val y1 = maxOf(a.y, b.y)
        val x2 = minOf(a.x + a.width, b.x + b.width)
        val y2 = minOf(a.y + a.height, b.y + b.height)

        val intersectionWidth = maxOf(0, x2 - x1)
        val intersectionHeight = maxOf(0, y2 - y1)
        val intersectionArea = intersectionWidth.toDouble() * intersectionHeight

        val areaA = a.width.toDouble() * a.height
        val areaB = b.width.toDouble() * b.height
        val unionArea = areaA + areaB - intersectionArea

        return if (unionArea > 0) intersectionArea / unionArea else 0.0
    }
}