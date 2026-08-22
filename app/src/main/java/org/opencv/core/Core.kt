package org.opencv.core

object Core {
    @JvmStatic
    fun minMaxLoc(src: Mat): MinMaxLocResult {
        return MinMaxLocResult()
    }

    @JvmStatic
    fun minMaxLoc(src: Mat, mask: Mat): MinMaxLocResult {
        return MinMaxLocResult()
    }

    @JvmStatic
    fun inRange(src: Mat, lowerb: Scalar, upperb: Scalar, dst: Mat) {
    }

    @JvmStatic
    fun countNonZero(src: Mat): Int {
        return 0
    }

    @JvmStatic
    fun split(m: Mat, mv: MutableList<Mat>) {
        repeat(m.channels()) { mv.add(Mat()) }
    }

    @JvmStatic
    fun mean(src: Mat): Scalar {
        return Scalar(0.0)
    }

    @JvmStatic
    fun zeros(rows: Int, cols: Int, type: Int): Mat {
        return Mat(rows, cols, type)
    }

    @JvmStatic
    fun zeros(size: Size, type: Int): Mat {
        return Mat(size.height.toInt(), size.width.toInt(), type)
    }

    class MinMaxLocResult {
        var minVal: Double = 0.0
        var maxVal: Double = 0.0
        var minLoc: Point = Point()
        var maxLoc: Point = Point()
    }
}
