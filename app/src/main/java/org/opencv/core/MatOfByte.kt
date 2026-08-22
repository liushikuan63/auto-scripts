package org.opencv.core

class MatOfByte(bytes: ByteArray) {
    private val data: ByteArray = bytes.copyOf()

    fun toArray(): ByteArray = data
}
