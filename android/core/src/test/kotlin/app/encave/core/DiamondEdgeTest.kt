package app.encave.core

import app.encave.core.sync.CellarMerge
import app.encave.core.sync.SyncState
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Bottles in the edge triangles of a diamond rack: kept by every version, counted, and moved with
 * their place when the triangles are turned on or off. Mirrors CaveKit's DiamondEdgeTests.swift.
 */
class DiamondEdgeTest {
    private val id = UUID.randomUUID()

    private fun rack(edges: Set<DiamondEdge>, rows: Int = 3, columns: Int = 3) =
        diamondRack(id = id, name = "Diamonds", rows = rows, columns = columns, binCapacity = 10, edges = edges)

    /** The rack as a version without triangles reads it: whole diamonds. */
    private fun older(rack: Rack) = rack.copy(edgeTrianglesValue = null, triangleCapacityValue = null)

    private val topLeft = setOf(DiamondEdge.Top, DiamondEdge.Left)

    @Test
    fun aTriangleHoldsFewerBottles() {
        val rack = rack(topLeft)
        val top = RackCell(id, 0, 1)
        assertEquals(DiamondEdge.Top, rack.triangleEdge(top))
        assertEquals(5, rack.positions(top))
        val cellar = Cellar()
        cellar.addRack(rack)
        val wine = Wine(name = "Clos")
        val slots = mutableListOf<Slot>()
        while (true) {
            val slot = cellar.freeSlot(top) ?: break
            slots += slot
            cellar.addBottles(wine, slots = listOf(slot))
        }
        assertEquals(listOf(0, 1, 2, 3, 4), slots.map { it.depth })
        // 3 rows of 3 diamonds, 3 top triangles and 1 left one (row B of diamonds).
        assertEquals(9 * 10 + 4 * 5, rack.capacity)
        assertEquals(rack.capacity, cellar.capacity(rack))
    }

    /** An older version sees a whole bin and fills it: every version keeps those bottles after a merge, and counts them. */
    @Test
    fun bottlesAnOlderVersionPutInATriangleStay() {
        val rack = rack(topLeft)
        val wine = Wine(name = "Clos")
        val slots = (0 until 8).map { Slot(id, 0, 2, it) }
        assertTrue(slots.all { older(rack).hasPosition(it) })
        for (version in listOf(rack, older(rack))) {
            val theirs = Cellar(listOf(wine), slots.map { Bottle(wineId = wine.id, slot = it) }, listOf(version))
            val merged = CellarMerge.merge(Cellar(racks = listOf(version)), SyncState(), theirs, SyncState()).cellar
            assertEquals(slots.toSet(), merged.bottlesInCellar.mapNotNull { it.slot }.toSet())
            assertTrue(merged.displacedBottles(version).isEmpty())
        }
        val cellar = Cellar(listOf(wine), slots.map { Bottle(wineId = wine.id, slot = it) }, listOf(rack))
        assertEquals(rack.capacity + 3, cellar.capacity(rack))
        assertNull(cellar.freeSlot(RackCell(id, 0, 2)))
        assertTrue(rack.allSlots.all { older(rack).contains(it) })
    }

    /** Turning on the top and left: every bottle keeps its diamond; Undo brings them back. */
    @Test
    fun turningTrianglesOnMovesBottlesWithTheirDiamond() {
        val before = rack(emptySet())
        val cellar = Cellar()
        cellar.addRack(before)
        val slots = listOf(Slot(id, 0, 0, 0), Slot(id, 1, 2, 3), Slot(id, 2, 1, 9))
        cellar.addBottles(Wine(name = "Clos"), quantity = slots.size, slots = slots)
        val after = rack(topLeft)
        val map = Rack.diamondCellMap(before, after)
        assertTrue(cellar.displacedBottles(after, cellMap = map).isEmpty())
        cellar.updateRack(after, cellMap = map)
        val places = cellar.bottlesInCellar.mapNotNull { b -> b.slot?.let { after.diamondPlace(it.cell) } }.toSet()
        assertEquals(setOf(DiamondPlace.Diamond(0, 0), DiamondPlace.Diamond(1, 2), DiamondPlace.Diamond(2, 1)), places)
        assertEquals(setOf(0, 3, 9), cellar.bottlesInCellar.mapNotNull { it.slot?.depth }.toSet())
        cellar.undoRackUpdate(before, emptyMap(), emptyList(), map)
        assertEquals(slots.toSet(), cellar.bottlesInCellar.mapNotNull { it.slot }.toSet())
    }

    /** Turning a side off while its triangles hold bottles: refused until agreed, then never lost, with Undo. */
    @Test
    fun turningASideOffAsksFirstAndNeverLosesBottles() {
        val before = rack(topLeft)
        val cellar = Cellar()
        cellar.addRack(before)
        val inTriangle = Slot(id, 0, 1, 2)
        val inLeft = Slot(id, 2, 0, 0)
        val inDiamond = Slot(id, 2, 1, 0)
        cellar.addBottles(Wine(name = "Clos"), quantity = 3, slots = listOf(inTriangle, inLeft, inDiamond))
        val after = rack(setOf(DiamondEdge.Left))
        val map = Rack.diamondCellMap(before, after)
        val displaced = cellar.displacedBottles(after, cellMap = map)
        assertEquals(listOf(inTriangle), displaced.mapNotNull { it.slot })
        assertThrows(CellarException::class.java) { cellar.updateRack(after, cellMap = map) }
        cellar.updateRack(after, takingOut = true, cellMap = map)
        assertEquals(3, cellar.bottlesInCellar.size)
        assertEquals(1, cellar.bottlesInCellar.count { it.slot == null })
        val places = cellar.bottlesInCellar.mapNotNull { b -> b.slot?.let { after.diamondPlace(it.cell) } }.toSet()
        assertEquals(setOf(DiamondPlace.Triangle(DiamondEdge.Left, 0), DiamondPlace.Diamond(1, 0)), places)
        cellar.undoRackUpdate(before, emptyMap(), displaced, map)
        assertEquals(setOf(inTriangle, inLeft, inDiamond), cellar.bottlesInCellar.mapNotNull { it.slot }.toSet())
    }

    /** An older version's own edit (a row added) leaves the key but not the shape: whole diamonds, nothing moves. */
    @Test
    fun aRackReshapedByAnOlderVersionKeepsItsBottles() {
        val base = rack(topLeft)
        val rack = base.copy(rows = base.rows + 1, rowColumns = base.rowColumns.orEmpty() + 3).normalized()
        assertNull(rack.diamondLayout)
        assertEquals(10, rack.positions(RackCell(id, 0, 0)))
        assertEquals(rack.cellCount * 10, rack.capacity)
        assertTrue(Rack.diamondCellMap(rack, rack).isEmpty())
    }

    @Test
    fun offsetRowsKeepTheirLookWhenTheTopIsOn() {
        val plain = rack(emptySet())
        val topped = rack(setOf(DiamondEdge.Top))
        assertEquals((0 until 3).map { plain.isOffset(it) }, (1 until 4).map { topped.isOffset(it) })
        assertTrue(topped.isOffset(0))
    }
}
