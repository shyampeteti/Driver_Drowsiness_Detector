package com.example.drowsiness.detection

import org.junit.Assert.*
import org.junit.Test

class EyeAspectRatioCalculatorTest {
    @Test fun computesKnownEar() {
        val points = listOf(
            Point2D(0.0, 0.0), Point2D(2.0, 2.0), Point2D(6.0, 2.0),
            Point2D(10.0, 0.0), Point2D(6.0, -2.0), Point2D(2.0, -2.0)
        )
        assertEquals(0.4, EyeAspectRatioCalculator.calculate(points)!!, 1e-9)
    }

    @Test fun rejectsWrongPointCountAndNonFiniteCoordinates() {
        assertNull(EyeAspectRatioCalculator.calculate(listOf(Point2D(0.0, 0.0))))
        assertNull(EyeAspectRatioCalculator.calculate(listOf(
            Point2D(0.0, 0.0), Point2D(Double.NaN, 1.0), Point2D(2.0, 1.0),
            Point2D(3.0, 0.0), Point2D(2.0, -1.0), Point2D(1.0, -1.0)
        )))
    }

    @Test fun rejectsZeroHorizontalDistance() {
        val points = listOf(
            Point2D(1.0, 0.0), Point2D(1.0, 2.0), Point2D(1.0, 2.0),
            Point2D(1.0, 0.0), Point2D(1.0, -2.0), Point2D(1.0, -2.0)
        )
        assertNull(EyeAspectRatioCalculator.calculate(points))
    }
}
