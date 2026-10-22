package app.encave.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PercentagesTest {
    @Test
    fun sharesAddUpTo100() {
        // 33.3 % each: rounded down to 33, the point left goes to the first.
        assertEquals(listOf(34, 33, 33), CellarStatistics.percentages(listOf(1, 1, 1)))
        // 14.3, 28.6, 57.1: the largest remainder (28.6) gets the point.
        assertEquals(listOf(14, 29, 57), CellarStatistics.percentages(listOf(1, 2, 4)))
        assertEquals(listOf(50, 50), CellarStatistics.percentages(listOf(3, 3)))
        assertEquals(100, CellarStatistics.percentages(listOf(7, 5, 3, 2, 1)).sum())
    }

    @Test
    fun nothingGivesZeros() {
        assertEquals(listOf(0, 0), CellarStatistics.percentages(listOf(0, 0)))
        assertEquals(emptyList<Int>(), CellarStatistics.percentages(emptyList()))
    }
}
