package org.opencv.core

class MatOfInt(vararg values: Int) {
    private val data = values

    fun toArray(): IntArray = data
}