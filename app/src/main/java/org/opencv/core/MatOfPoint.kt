package org.opencv.core

class MatOfPoint(vararg points: Point) {
    private val pts: Array<out Point> = points
    fun toList(): List<Point> = pts.toList()
}
