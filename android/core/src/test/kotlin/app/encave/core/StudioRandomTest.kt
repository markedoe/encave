package app.encave.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StudioRandomTest {
    /** The same vector is in StudioRandomTests.swift: a wine's backdrop is the same on both platforms. */
    @Test
    fun sameNumbersOnEveryPlatform() {
        val random = StudioRandom("8F0E3C1A-0000-4000-8000-000000000001")
        listOf(0.086439285462, 0.119454450003, 0.49856282913).forEach { assertEquals(it, random.next(), 1e-9) }
        assertEquals(0.086439285462, StudioRandom("8f0e3c1a-0000-4000-8000-000000000001").next(), 1e-9)
    }
}
