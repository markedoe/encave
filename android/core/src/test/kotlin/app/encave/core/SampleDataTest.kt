package app.encave.core

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SampleDataTest {
    /** The same vectors as SampleDataTests.swift: both apps give the sample the same IDs. */
    @Test
    fun idsAreNameBasedAndShared() {
        assertEquals(UUID.fromString("422F32A7-FFA7-5722-A7A5-F3C9D551EC1E"), SampleData.id(SampleData.Kind.Rack, 0))
        assertEquals(UUID.fromString("32C7F6A8-BA2B-58E2-A2F4-9C88521301B0"), SampleData.id(SampleData.Kind.Wine, 0))
        assertEquals(UUID.fromString("62DF80CD-D7CD-5B64-841D-B720F469ABD8"), SampleData.id(SampleData.Kind.Bottle, 46))
        assertEquals(UUID.fromString("7AEB571F-970E-561A-AB7A-4378771FC488"), SampleData.id(SampleData.Kind.Discovery, 1))
        // RFC 9562's example of a version 5 UUID.
        assertEquals(
            UUID.fromString("2ed6657d-e927-568b-95e1-2665a8aea6a2"),
            SampleData.nameBasedUuid(UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"), "www.example.com"),
        )
    }

    @Test
    fun everySampleRecordHasAFixedId() {
        val sample = SampleData.cellar(year = 2026)
        assertTrue(sample.isOnlySample)
        assertTrue(sample.containsSample)
        assertEquals(SampleData.id(SampleData.Kind.Rack, 0), sample.racks.first().id)
        assertEquals(8, sample.racks.size)
        assertEquals(15, sample.wines.size)
        assertEquals(63, sample.bottles.size)
        assertEquals(2, sample.discoveries.size)
        val again = SampleData.cellar(year = 2026)
        assertEquals(sample.bottles.keys, again.bottles.keys)
        assertEquals(sample.wines.keys, again.wines.keys)
        assertFalse(Cellar().isOnlySample)
        assertFalse(Cellar().containsSample)
    }

    @Test
    fun removingTheSampleKeepsTheUsersRecords() {
        val cellar = SampleData.cellar(year = 2026)
        val sampleRack = cellar.racks[0]
        val sampleWine = requireNotNull(cellar.wines.values.firstOrNull { it.name == "Cuvée du Lac" })
        val own = Wine(name = "Own wine")
        cellar.addBottles(own, quantity = 1, slots = listOf(cellar.freeSlots(sampleRack).first()))
        cellar.addBottles(sampleWine, quantity = 1)
        val ownRack = Rack(name = "Own rack", rows = 2, columns = 2)
        cellar.addRack(ownRack)
        assertFalse(cellar.isOnlySample)
        assertTrue(cellar.containsSample)

        val removed = cellar.removingSample()
        assertEquals(2, removed.bottles.size)
        assertEquals(setOf(own.id, sampleWine.id), removed.wines.keys)
        // The sample rack holding the user's bottle stays; the other sample racks go.
        assertEquals(listOf(sampleRack.id, ownRack.id), removed.racks.map { it.id })
        assertTrue(removed.discoveries.isEmpty())
        assertEquals(cellar.settings, removed.settings)

        assertTrue(SampleData.cellar(year = 2026).removingSample().isEmpty)
    }
}
