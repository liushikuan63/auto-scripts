package com.farmbot.ocr

import android.graphics.Bitmap
import com.farmbot.util.Logger
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import timber.log.Timber

/**
 * OCR 文字识别助手 — 融合 HonorFarmBot 版本
 *
 * 扩展：
 * - recognizeCountdown：解析倒计时 (HH:MM / MM:SS / 纯数字秒) → 秒数
 * - recognizeNumber：提取纯数字
 * - findTextPosition：文字中心坐标
 * - containsText：判断是否包含某段文字
 */
class OcrHelper {

    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    // ---------------- 基础识别 ----------------

    fun recognizeText(bm: Bitmap, cb: (fullText: String, blocks: List<TextBlock>) -> Unit) {
        val img = InputImage.fromBitmap(bm, 0)
        recognizer.process(img)
            .addOnSuccessListener { v ->
                val blocks = v.textBlocks.map {
                    TextBlock(
                        it.text,
                        it.boundingBox?.centerX() ?: 0,
                        it.boundingBox?.centerY() ?: 0,
                        it.boundingBox?.width() ?: 0,
                        it.boundingBox?.height() ?: 0
                    )
                }
                Timber.d("OCR => ${v.text}")
                cb(v.text, blocks)
            }
            .addOnFailureListener { t ->
                Logger.e("OcrHelper", "recognizeText failed", t)
                cb("", emptyList())
            }
    }

/**
 * 识别整图文字（可限定区域）：逐块回调文字与边界框
 */
    fun recognizeText(
        bm: Bitmap,
        region: android.graphics.Rect?,
        cb: (fullText: String, blocks: List<TextBlock>) -> Unit
    ) {
        val target = createRegionBitmap(bm, region)
        if (target == null) {
            cb("", emptyList())
            return
        }
        recognizeText(target) { text, blocks ->
            try {
                cb(text, blocks)
            } finally {
                if (target !== bm && !target.isRecycled) target.recycle()
            }
        }
    }

    // ---------------- 倒计时解析 ----------------

    /**
     * 解析倒计时为剩余秒数
     * 支持格式：
     *   "2:30" → 150
     *   "120s" / "120秒" → 120
     *   "1分30秒" → 90
     *   "90" → 90 (fallback 纯数字)
     * @return >=0 解析成功的秒数；-1 完全失败
     */
    fun recognizeCountdown(
        bm: Bitmap, region: android.graphics.Rect? = null,
        cb: (seconds: Int) -> Unit
    ) {
        recognizeText(bm, region) { text, _ ->
            cb(parseCountdown(text))
        }
    }

    companion object {
        /**
         * 解析倒计时为剩余秒数（纯字符串解析，无 Android 依赖，可在 JVM 单元测试中直接调用）
         * 支持格式：
         *   "2:30" → 150
         *   "1:02:05" → 3725
         *   "1分30秒" → 90
         *   "120" / "120s" → 120
         * @return >=0 解析成功的秒数；-1 完全失败
         */
        fun parseCountdown(raw: String): Int {
            val t = raw.trim().replace(" ", "")
            if (t.isEmpty()) return -1
            val timeMmSs = Regex("""(\d{1,2}):(\d{2})""").find(t)
            if (timeMmSs != null) {
                val m = timeMmSs.groupValues[1].toIntOrNull() ?: 0
                val s = timeMmSs.groupValues[2].toIntOrNull() ?: 0
                // 若含有第二个冒号(HH:MM:SS)，解析为时分秒
                val hh = Regex("""(\d{1,2}):(\d{2}):(\d{2})""").find(t)
                if (hh != null) {
                    val h = hh.groupValues[1].toIntOrNull() ?: 0
                    val mm = hh.groupValues[2].toIntOrNull() ?: 0
                    val ss = hh.groupValues[3].toIntOrNull() ?: 0
                    return h * 3600 + mm * 60 + ss
                }
                return m * 60 + s
            }
            val cn = Regex("""(?:(\d+)\s*小\s*时)?\s*(?:(\d+)\s*分)?\s*(?:(\d+)\s*(?:秒|s|S))?""").find(t)
            if (cn != null) {
                val h = cn.groupValues[1].ifBlank { null }?.toIntOrNull() ?: 0
                val m = cn.groupValues[2].ifBlank { null }?.toIntOrNull() ?: 0
                val s = cn.groupValues[3].ifBlank { null }?.toIntOrNull() ?: 0
                if (h > 0 || m > 0 || s > 0) return h * 3600 + m * 60 + s
            }
            val onlyNum = Regex("""(\d+)""").find(t)
            return onlyNum?.groupValues?.get(1)?.toIntOrNull() ?: -1
        }
    }

    // ---------------- 数字识别 ----------------

    fun recognizeNumber(bm: Bitmap, region: android.graphics.Rect? = null, cb: (Int?) -> Unit) {
        recognizeText(bm, region) { text, _ ->
            cb(text.filter { it.isDigit() }.toIntOrNull())
        }
    }

    private fun createRegionBitmap(bm: Bitmap, region: android.graphics.Rect?): Bitmap? {
        if (region == null) return bm
        val left = region.left.coerceIn(0, bm.width)
        val top = region.top.coerceIn(0, bm.height)
        val right = region.right.coerceIn(left, bm.width)
        val bottom = region.bottom.coerceIn(top, bm.height)
        if (right <= left || bottom <= top) return null
        return Bitmap.createBitmap(bm, left, top, right - left, bottom - top)
    }

    // ---------------- 包含/定位文字 ----------------

    fun containsText(bm: Bitmap, target: String, cb: (Boolean) -> Unit) {
        recognizeText(bm) { text, _ -> cb(text.contains(target)) }
    }

/**
 * 在图中定位目标文字，命中时回调其中心坐标
 */
    fun findTextPosition(bm: Bitmap, target: String, cb: (Int, Int) -> Unit) {
        val img = InputImage.fromBitmap(bm, 0)
        recognizer.process(img)
            .addOnSuccessListener { v ->
                for (blk in v.textBlocks) {
                    if (blk.text.contains(target)) {
                        cb(blk.boundingBox?.centerX() ?: 0, blk.boundingBox?.centerY() ?: 0)
                        return@addOnSuccessListener
                    }
                }
                cb(0, 0)
            }
            .addOnFailureListener { cb(0, 0) }
    }

/**
 * 释放底层识别器
 */
    fun shutdown() {
        runCatching { recognizer.close() }
    }

    data class TextBlock(
        val text: String,
        val x: Int, val y: Int,
        val width: Int, val height: Int
    )
}
