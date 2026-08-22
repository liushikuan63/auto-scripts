package org.opencv.android

import android.graphics.Bitmap
import org.opencv.core.Mat

object Utils {
    @JvmStatic
    fun matToBitmap(mat: Mat, bitmap: Bitmap) {
    }

    @JvmStatic
    fun bitmapToMat(bitmap: Bitmap): Mat {
        return Mat()
    }

    @JvmStatic
    fun bitmapToMat(bitmap: Bitmap, mat: Mat) {
    }
}