package org.opencv.core

object CvType {
    const val CV_8UC1 = 0
    const val CV_8UC2 = 8
    const val CV_8UC3 = 16
    const val CV_8UC4 = 24
    const val CV_32FC1 = 5
    const val CV_32FC3 = 21

    /** 从 type 值提取通道数（CV_8UC[n] 的通道编码在高 bits） */
    @JvmStatic
    fun channels(type: Int): Int = (type shr 3) + 1
}