package com.farmbot.util

import android.content.Context
import android.graphics.Point
import android.util.DisplayMetrics
import kotlin.math.abs

object CoordinateUtil {

    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Float = 1.0f
    private var baseWidth: Int = 1080

    fun init(context: Context) {
        val metrics: DisplayMetrics = context.resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.density
    }

    fun init(width: Int, height: Int, density: Float = 1.0f) {
        screenWidth = width
        screenHeight = height
        screenDensity = density
    }

    fun setBaseWidth(width: Int) {
        baseWidth = width
    }

    fun getScreenWidth(): Int = screenWidth
    fun getScreenHeight(): Int = screenHeight
    fun getScreenDensity(): Float = screenDensity

    fun scaleX(baseX: Int, fromWidth: Int = baseWidth): Int {
        return (baseX * screenWidth.toFloat() / fromWidth).toInt()
    }

    fun scaleY(baseY: Int, fromHeight: Int = 2400): Int {
        return (baseY * screenHeight.toFloat() / fromHeight).toInt()
    }

    fun scalePoint(x: Int, y: Int, fromWidth: Int = baseWidth, fromHeight: Int = 2400): Point {
        return Point(
            scaleX(x, fromWidth),
            scaleY(y, fromHeight)
        )
    }

    fun scaleRect(
        left: Int, top: Int, right: Int, bottom: Int,
        fromWidth: Int = baseWidth, fromHeight: Int = 2400
    ): android.graphics.Rect {
        return android.graphics.Rect(
            scaleX(left, fromWidth),
            scaleY(top, fromHeight),
            scaleX(right, fromWidth),
            scaleY(bottom, fromHeight)
        )
    }

    fun centerOf(x1: Int, y1: Int, x2: Int, y2: Int): Point {
        return Point((x1 + x2) / 2, (y1 + y2) / 2)
    }

    fun distance(p1: Point, p2: Point): Double {
        val dx = (p1.x - p2.x).toDouble()
        val dy = (p1.y - p2.y).toDouble()
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    fun arePointsClose(p1: Point, p2: Point, thresholdPx: Int = 30): Boolean {
        return distance(p1, p2) < thresholdPx
    }

    fun dpToPx(dp: Int): Int {
        return (dp * screenDensity + 0.5f).toInt()
    }

    fun pxToDp(px: Int): Int {
        return (px / screenDensity + 0.5f).toInt()
    }

    fun relativeX(percentX: Float): Int {
        return (screenWidth * percentX).toInt()
    }

    fun relativeY(percentY: Float): Int {
        return (screenHeight * percentY).toInt()
    }

    fun isLandscape(): Boolean {
        return screenWidth > screenHeight
    }

    fun getOrientation(): Int {
        return if (isLandscape()) 0 else 1
    }

    fun getResolutionLabel(): String {
        return "${screenWidth}x${screenHeight}"
    }
}