package org.opencv.core

class MatOfPoint2f(vararg points: Point) {
    private val pts: Array<out Point> = points
    fun toList(): List<Point> = pts.toList()
}
