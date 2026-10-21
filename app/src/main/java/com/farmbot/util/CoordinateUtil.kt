/**
 * 坐标换算工具
 */

package com.farmbot.util

import android.content.Context
import android.graphics.Point
import android.util.DisplayMetrics
import kotlin.math.abs

/**
 * 坐标换算工具
 *
 * 以基准宽度为标尺做分辨率无关的缩放：scaleX / scaleY / scalePoint / scaleRect，
 * 另有中心点、两点距离、间距判断与 dp / px 互转。
 */
object CoordinateUtil {

    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Float = 1.0f
    private var baseWidth: Int = 1080

/**
 * 用 Context 初始化屏幕尺寸与密度
 */
    fun init(context: Context) {
        val metrics: DisplayMetrics = context.resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.density
    }

/**
 * 用显式尺寸与密度初始化
 */
    fun init(width: Int, height: Int, density: Float = 1.0f) {
        screenWidth = width
        screenHeight = height
        screenDensity = density
    }

/**
 * 设置缩放基准宽度（模板坐标按此宽度设计）
 */
    fun setBaseWidth(width: Int) {
        baseWidth = width
    }

/**
 * 屏幕宽度
 */
    fun getScreenWidth(): Int = screenWidth
/**
 * 屏幕高度
 */
    fun getScreenHeight(): Int = screenHeight
/**
 * 屏幕密度
 */
    fun getScreenDensity(): Float = screenDensity

/**
 * 按基准宽度缩放横坐标
 */
    fun scaleX(baseX: Int, fromWidth: Int = baseWidth): Int {
        return (baseX * screenWidth.toFloat() / fromWidth).toInt()
    }

/**
 * 按基准高度缩放纵坐标
 */
    fun scaleY(baseY: Int, fromHeight: Int = 2400): Int {
        return (baseY * screenHeight.toFloat() / fromHeight).toInt()
    }

/**
 * 按基准缩放一个点
 */
    fun scalePoint(x: Int, y: Int, fromWidth: Int = baseWidth, fromHeight: Int = 2400): Point {
        return Point(
            scaleX(x, fromWidth),
            scaleY(y, fromHeight)
        )
    }

/**
 * 按基准缩放一个矩形
 */
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

/**
 * 取两点的中点
 */
    fun centerOf(x1: Int, y1: Int, x2: Int, y2: Int): Point {
        return Point((x1 + x2) / 2, (y1 + y2) / 2)
    }

/**
 * 两点距离
 */
    fun distance(p1: Point, p2: Point): Double {
        val dx = (p1.x - p2.x).toDouble()
        val dy = (p1.y - p2.y).toDouble()
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

/**
 * 两点是否在阈值内接近
 */
    fun arePointsClose(p1: Point, p2: Point, thresholdPx: Int = 30): Boolean {
        return distance(p1, p2) < thresholdPx
    }

/**
 * dp 转 px
 */
    fun dpToPx(dp: Int): Int {
        return (dp * screenDensity + 0.5f).toInt()
    }

/**
 * px 转 dp
 */
    fun pxToDp(px: Int): Int {
        return (px / screenDensity + 0.5f).toInt()
    }

/**
 * 按屏幕宽度百分比求横坐标
 */
    fun relativeX(percentX: Float): Int {
        return (screenWidth * percentX).toInt()
    }

/**
 * 按屏幕高度百分比求纵坐标
 */
    fun relativeY(percentY: Float): Int {
        return (screenHeight * percentY).toInt()
    }

/**
 * 当前是否为横屏
 */
    fun isLandscape(): Boolean {
        return screenWidth > screenHeight
    }

/**
 * 当前屏幕方向
 */
    fun getOrientation(): Int {
        return if (isLandscape()) 0 else 1
    }

/**
 * 分辨率可读标签（如 1080x2400）
 */
    fun getResolutionLabel(): String {
        return "${screenWidth}x${screenHeight}"
    }
}