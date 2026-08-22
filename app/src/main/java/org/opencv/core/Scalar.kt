package org.opencv.core

class Scalar {
    var `val`: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0, 0.0)

    constructor() {}
    constructor(v0: Double) {
        `val` = doubleArrayOf(v0)
    }
    constructor(v0: Double, v1: Double) {
        `val` = doubleArrayOf(v0, v1)
    }
    constructor(v0: Double, v1: Double, v2: Double) {
        `val` = doubleArrayOf(v0, v1, v2)
    }
    constructor(v0: Double, v1: Double, v2: Double, v3: Double) {
        `val` = doubleArrayOf(v0, v1, v2, v3)
    }
}