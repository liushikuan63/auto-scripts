package org.opencv.imgcodecs

import org.opencv.core.Mat
import org.opencv.core.MatOfByte

object Imgcodecs {
    const val IMREAD_COLOR = 1
    const val IMREAD_GRAYSCALE = 0

    @JvmStatic
    fun imdecode(buf: MatOfByte, flags: Int): Mat {
        return Mat()
    }

    @JvmStatic
    fun imread(filename: String, flags: Int): Mat {
        return Mat()
    }
}