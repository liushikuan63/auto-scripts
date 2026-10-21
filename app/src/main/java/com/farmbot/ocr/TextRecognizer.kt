package com.farmbot.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.opencv.core.Mat
import org.opencv.android.Utils
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ML Kit 中文文字识别封装
 *
 * 支持从 OpenCV Mat 或 Bitmap 识别中文文本块，按最小置信度过滤，
 * 结果以文本块列表返回（每块含文字与边界框），并在 close() 时释放底层识别器。
 */
class TextRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "TextRecognizer"
        private const val DEFAULT_MIN_CONFIDENCE = 0.5f
    }

    private val recognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )

    suspend fun recognizeFromMat(
        mat: Mat,
        minConfidence: Float = DEFAULT_MIN_CONFIDENCE
    ): OcrResult = suspendCancellableCoroutine { continuation ->
        val bitmap = matToBitmap(mat)
        if (bitmap == null) {
            continuation.resume(OcrResult(emptyList()))
            return@suspendCancellableCoroutine
        }

        recognizeFromBitmap(bitmap, minConfidence) { result ->
            bitmap.recycle()
            if (continuation.isActive) {
                continuation.resume(result)
            }
        }
    }

    suspend fun recognizeRegion(
        mat: Mat,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        minConfidence: Float = DEFAULT_MIN_CONFIDENCE
    ): OcrResult = suspendCancellableCoroutine { continuation ->
        if (x < 0 || y < 0 || width <= 0 || height <= 0) {
            continuation.resume(OcrResult(emptyList()))
            return@suspendCancellableCoroutine
        }

        val clampedWidth = minOf(width, mat.cols() - x)
        val clampedHeight = minOf(height, mat.rows() - y)

        if (clampedWidth <= 0 || clampedHeight <= 0) {
            continuation.resume(OcrResult(emptyList()))
            return@suspendCancellableCoroutine
        }

        val roi = Mat(mat, org.opencv.core.Rect(x, y, clampedWidth, clampedHeight))
        val bitmap = matToBitmap(roi)
        roi.release()

        if (bitmap == null) {
            continuation.resume(OcrResult(emptyList()))
            return@suspendCancellableCoroutine
        }

        recognizeFromBitmap(bitmap, minConfidence) { result ->
            bitmap.recycle()
            if (continuation.isActive) {
                continuation.resume(result)
            }
        }
    }

    private fun recognizeFromBitmap(
        bitmap: Bitmap,
        minConfidence: Float,
        callback: (OcrResult) -> Unit
    ) {
        val scaledBitmap = scaleBitmap(bitmap)
        val image = InputImage.fromBitmap(scaledBitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { text ->
                val blocks = text.textBlocks
                val results = mutableListOf<TextBlock>()

                for (block in blocks) {
                    val lines = mutableListOf<TextLine>()
                    for (line in block.lines) {
                        val elements = mutableListOf<TextElement>()
                        for (element in line.elements) {
                            val confidence = element.confidence
                            if (confidence >= minConfidence) {
                                elements.add(
                                    TextElement(
                                        text = element.text,
                                        confidence = confidence,
                                        boundingBox = element.boundingBox?.let {
                                            Rect(
                                                it.left,
                                                it.top,
                                                it.right,
                                                it.bottom
                                            )
                                        }
                                    )
                                )
                            }
                        }
                        if (elements.isNotEmpty()) {
                            lines.add(
                                TextLine(
                                    text = line.text,
                                    elements = elements,
                                    boundingBox = line.boundingBox?.let {
                                        Rect(it.left, it.top, it.right, it.bottom)
                                    }
                                )
                            )
                        }
                    }
                    if (lines.isNotEmpty()) {
                        results.add(
                            TextBlock(
                                text = block.text,
                                lines = lines,
                                boundingBox = block.boundingBox?.let {
                                    Rect(it.left, it.top, it.right, it.bottom)
                                }
                            )
                        )
                    }
                }

                if (scaledBitmap !== bitmap) scaledBitmap.recycle()
                callback(OcrResult(results))
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR failed", e)
                if (scaledBitmap !== bitmap) scaledBitmap.recycle()
                callback(OcrResult(emptyList()))
            }
    }

    private fun scaleBitmap(bitmap: Bitmap): Bitmap {
        val minDimension = minOf(bitmap.width, bitmap.height)
        val targetSize = 1280

        if (minDimension >= targetSize) return bitmap

        val scale = targetSize.toFloat() / minDimension
        val matrix = Matrix()
        matrix.postScale(scale, scale)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun matToBitmap(mat: Mat): Bitmap? {
        if (mat.empty()) return null

        val rgbMat = Mat()
        org.opencv.imgproc.Imgproc.cvtColor(mat, rgbMat, org.opencv.imgproc.Imgproc.COLOR_BGR2RGB)

        val bitmap = Bitmap.createBitmap(rgbMat.cols(), rgbMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgbMat, bitmap)
        rgbMat.release()

        return bitmap
    }

    fun close() {
        recognizer.close()
        Log.i(TAG, "TextRecognizer closed")
    }

    data class OcrResult(
        val blocks: List<TextBlock>
    ) {
        fun getFullText(): String {
            return blocks.joinToString("\n") { it.text }
        }

        fun getTextsInRegion(rect: Rect): List<String> {
            return blocks.flatMap { block ->
                block.lines.filter { line ->
                    val lineBounds = line.boundingBox
                    lineBounds?.let { Rect.intersects(it, rect) }
                        ?: line.elements.any { element ->
                            element.boundingBox?.let { Rect.intersects(it, rect) } == true
                        }
                }.map { it.text }
            }.filter { it.isNotEmpty() }
        }

        fun isEmpty(): Boolean = blocks.isEmpty()
    }

    data class TextBlock(
        val text: String,
        val lines: List<TextLine>,
        val boundingBox: Rect?
    )

    data class TextLine(
        val text: String,
        val elements: List<TextElement>,
        val boundingBox: Rect?
    )

    data class TextElement(
        val text: String,
        val confidence: Float,
        val boundingBox: Rect?
    )
}
