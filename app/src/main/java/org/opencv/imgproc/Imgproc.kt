package org.opencv.imgproc

import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.core.Size

object Imgproc {
    const val TM_CCOEFF_NORMED = 5
    const val COLOR_BGR2HSV = 40
    const val COLOR_RGB2HSV = 41
    const val COLOR_RGBA2RGB = 3
    const val COLOR_BGR2RGB = 4
    const val COLOR_RGB2GRAY = 7
    const val COLOR_RGBA2GRAY = 11

    const val RETR_EXTERNAL = 0
    const val CHAIN_APPROX_SIMPLE = 2

    const val MORPH_ELLIPSE = 2
    const val MORPH_CLOSE = 3
    const val MORPH_OPEN = 2

    const val INTER_LINEAR = 1

    @JvmStatic
    fun matchTemplate(image: Mat, templ: Mat, result: Mat, method: Int) {
    }

    @JvmStatic
    fun matchTemplate(image: Mat, templ: Mat, result: Mat, method: Int, mask: Mat) {
    }

    @JvmStatic
    fun resize(src: Mat, dst: Mat, dsize: Size): Mat {
        val result = Mat(dsize.height.toInt(), dsize.width.toInt(), src.type())
        dst.apply { }
        return result
    }

    @JvmStatic
    fun resize(src: Mat, dst: Mat, dsize: Size, fx: Double, fy: Double, interpolation: Int): Mat {
        val result = Mat(dsize.height.toInt(), dsize.width.toInt(), src.type())
        return result
    }

    @JvmStatic
    fun cvtColor(src: Mat, dst: Mat, code: Int) {
    }

    @JvmStatic
    fun calcHist(images: List<Mat>, channels: MatOfInt, mask: Mat, hist: Mat, histSize: MatOfInt, ranges: FloatArray) {
    }

    @JvmStatic
    fun getStructuringElement(shape: Int, ksize: Size): Mat {
        return Mat(ksize.height.toInt(), ksize.width.toInt(), 0)
    }

    @JvmStatic
    fun morphologyEx(src: Mat, dst: Mat, op: Int, kernel: Mat) {
    }

    @JvmStatic
    fun findContours(
        image: Mat,
        contours: MutableList<Mat>,
        hierarchy: Mat,
        mode: Int,
        method: Int
    ) {
    }

    @JvmStatic
    fun contourArea(contour: Mat): Double {
        return 0.0
    }

    class Moments {
        var m00: Double = 0.0
        var m10: Double = 0.0
        var m01: Double = 0.0
    }

    @JvmStatic
    fun moments(arr: Mat): Moments {
        return Moments()
    }
}
