package app.encave.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SampleDataTest {
    @Test
    fun removingSampleKeepsOwnBottlesAndRacks() {
        val labels = SampleData.Labels(mainRack = "Casier principal")
        val cellar = SampleData.cellar(year = 2026, labels = labels)
        assertTrue(cellar.containsSample)

        // The user's own rack and bottles, one of them in a sample rack.
        val own = Rack(name = "Garage", rows = 2, columns = 2)
        cellar.addRack(own)
        val main = cellar.racks.first { it.name == "Casier principal" }
        cellar.addBottles(Wine(name = "Mon vin", producer = "Moi", color = WineColor.Red), slots = listOf(cellar.freeSlots(main).first()))
        cellar.addBottles(Wine(name = "Autre", producer = "Moi", color = WineColor.White), slots = listOf(Slot(own.id, 0, 0)))

        val cleaned = cellar.removingSample(listOf(labels))
        assertFalse(cleaned.containsSample)
        assertEquals(setOf("Mon vin", "Autre"), cleaned.wines.values.map { it.name }.toSet())
        assertEquals(2, cleaned.bottles.size)
        assertTrue(cleaned.discoveries.isEmpty())
        // The sample rack holding the user's bottle stays, with it; the empty ones go.
        assertEquals(setOf("Casier principal", "Garage"), cleaned.racks.map { it.name }.toSet())
        assertTrue(cleaned.bottles.values.all { it.slot != null })
        // The original is untouched.
        assertTrue(cellar.containsSample)
    }

    @Test
    fun ownRackWithASampleNameButAnotherLayoutStays() {
        val rack = Rack(name = "Main rack", rows = 3, columns = 3)
        val cellar = Cellar(racks = listOf(rack))
        assertFalse(cellar.containsSample)
        assertEquals(listOf(rack.id), cellar.removingSample().racks.map { it.id })
    }
}
