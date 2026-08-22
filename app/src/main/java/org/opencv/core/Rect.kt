package org.opencv.core

class Rect {
    var x: Int = 0
    var y: Int = 0
    var width: Int = 0
    var height: Int = 0

    constructor() {}
    constructor(x: Int, y: Int, width: Int, height: Int) {
        this.x = x
        this.y = y
        this.width = width
        this.height = height
    }
}