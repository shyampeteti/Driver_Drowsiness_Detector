package com.example.drowsiness.detection

import kotlin.math.hypot

data class Point2D(val x: Double, val y: Double) {
    fun isValid(): Boolean = x.isFinite() && y.isFinite()
}

object EyeAspectRatioCalculator {
    /** EAR = (|p2-p6| + |p3-p5|) / (2*|p1-p4|). */
    fun calculate(points: List<Point2D>): Double? {
        if (points.size != 6 || points.any { !it.isValid() }) return null
        val horizontal = distance(points[0], points[3])
        if (!horizontal.isFinite() || horizontal <= 1e-9) return null
        val ear = (distance(points[1], points[5]) + distance(points[2], points[4])) / (2.0 * horizontal)
        return ear.takeIf { it.isFinite() && it >= 0.0 }
    }

    fun distance(a: Point2D, b: Point2D): Double = hypot(a.x - b.x, a.y - b.y)
}
