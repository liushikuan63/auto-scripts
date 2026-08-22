package com.farmbot.vision

import android.util.Log
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

object TemplateMatcher {

    private const val TAG = "TemplateMatcher"

    data class Match(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val confidence: Double
    )

    private const val METHOD = Imgproc.TM_CCOEFF_NORMED

    fun match(
        source: Mat,
        template: Mat,
        threshold: Double
    ): List<Match> {
        if (source.empty() || template.empty()) {
            return emptyList()
        }

        if (template.cols() > source.cols() || template.rows() > source.rows()) {
            return emptyList()
        }

        val resultCols = source.cols() - template.cols() + 1
        val resultRows = source.rows() - template.rows() + 1
        val result = Mat(resultRows, resultCols, CvType.CV_32FC1)

        Imgproc.matchTemplate(source, template, result, METHOD)

        val matches = mutableListOf<Match>()
        val step = maxOf(1, (resultCols * resultRows / 50000.0).toInt())

        for (y in 0 until resultRows step step) {
            for (x in 0 until resultCols step step) {
                val confidence = result.get(y, x)[0]
                if (confidence >= threshold) {
                    matches.add(
                        Match(
                            x = x,
                            y = y,
                            width = template.cols(),
                            height = template.rows(),
                            confidence = confidence.toDouble()
                        )
                    )
                }
            }
        }

        result.release()
        return matches
    }

    fun matchSingle(
        source: Mat,
        template: Mat,
        threshold: Double
    ): Match? {
        if (source.empty() || template.empty()) return null

        if (template.cols() > source.cols() || template.rows() > source.rows()) return null

        val resultCols = source.cols() - template.cols() + 1
        val resultRows = source.rows() - template.rows() + 1
        val result = Mat(resultRows, resultCols, CvType.CV_32FC1)

        Imgproc.matchTemplate(source, template, result, METHOD)

        val minMax = Core.minMaxLoc(result)
        result.release()

        return if (minMax.maxVal >= threshold) {
            Match(
                x = minMax.maxLoc.x.toInt(),
                y = minMax.maxLoc.y.toInt(),
                width = template.cols(),
                height = template.rows(),
                confidence = minMax.maxVal
            )
        } else {
            null
        }
    }

    fun matchMultiScale(
        source: Mat,
        template: Mat,
        threshold: Double,
        minScale: Double = 0.5,
        maxScale: Double = 1.0,
        scaleStep: Double = 0.1
    ): List<Match> {
        if (source.empty() || template.empty()) return emptyList()

        val allMatches = mutableListOf<Match>()
        var scale = minScale

        while (scale <= maxScale) {
            val scaledTemplate = if (scale == 1.0) {
                template
            } else {
                val newWidth = (template.cols() * scale).toInt()
                val newHeight = (template.rows() * scale).toInt()
                if (newWidth < 8 || newHeight < 8) break
                Imgproc.resize(template, Mat(), Size(newWidth.toDouble(), newHeight.toDouble()))
            }

            val matches = match(source, scaledTemplate, threshold)
            for (m in matches) {
                allMatches.add(
                    Match(
                        x = (m.x / scale).toInt(),
                        y = (m.y / scale).toInt(),
                        width = (m.width / scale).toInt(),
                        height = (m.height / scale).toInt(),
                        confidence = m.confidence
                    )
                )
            }

            if (scale != 1.0) {
                scaledTemplate.release()
            }

            scale += scaleStep
        }

        return applyNMS(allMatches)
    }

    private fun applyNMS(detections: List<Match>): List<Match> {
        if (detections.isEmpty()) return emptyList()

        val sorted = detections.sortedByDescending { it.confidence }
        val keep = mutableListOf<Match>()
        val overlapThreshold = 0.4

        for (i in sorted.indices) {
            var shouldKeep = true
            for (k in keep) {
                if (calculateIoU(sorted[i], k) > overlapThreshold) {
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

    private fun calculateIoU(a: Match, b: Match): Double {
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