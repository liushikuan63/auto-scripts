package org.opencv.core

open class Mat {
    private var isReleased = false
    private var colsCount = 0
    private var rowsCount = 0
    private var typeVal = CvType.CV_8UC1

    constructor() {}

    constructor(rows: Int, cols: Int, type: Int) {
        this.rowsCount = rows
        this.colsCount = cols
        this.typeVal = type
    }

    constructor(rows: Int, cols: Int, type: Int, data: ByteArray) {
        this.rowsCount = rows
        this.colsCount = cols
        this.typeVal = type
    }

    constructor(roi: Mat, rect: Rect) {
        this.rowsCount = rect.height
        this.colsCount = rect.width
        this.typeVal = roi.typeVal
    }

    constructor(rows: Int, cols: Int, type: Int, s: Scalar) {
        this.rowsCount = rows
        this.colsCount = cols
        this.typeVal = type
    }

    fun cols(): Int = colsCount
    fun rows(): Int = rowsCount
    fun channels(): Int = when {
        CvType.channels(typeVal) > 0 -> CvType.channels(typeVal)
        else -> 1
    }
    fun type(): Int = typeVal

    fun size(): Size = Size(colsCount.toDouble(), rowsCount.toDouble())

    fun empty(): Boolean = colsCount == 0 || rowsCount == 0 || isReleased

    fun release() {
        isReleased = true
    }

    fun clone(): Mat {
        val m = Mat(rowsCount, colsCount, typeVal)
        m.isReleased = false
        return m
    }

    fun setTo(value: Scalar): Mat {
        return this
    }

    fun setTo(value: Scalar, mask: Mat): Mat {
        return this
    }

    fun get(row: Int, col: Int): DoubleArray {
        return doubleArrayOf(0.0)
    }

    fun get(row: Int, col: Int, data: ByteArray) {
    }

    fun put(row: Int, col: Int, data: ByteArray) {
    }

    fun put(row: Int, col: Int, data: DoubleArray) {
    }

    companion object {
        @JvmStatic
        fun zeros(rows: Int, cols: Int, type: Int): Mat {
            return Mat(rows, cols, type)
        }

        @JvmStatic
        fun zeros(size: Size, type: Int): Mat {
            return Mat(size.height.toInt(), size.width.toInt(), type)
        }
    }
}