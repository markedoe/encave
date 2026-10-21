package app.encave.core

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RackTest {
    @Test
    fun capacityPerKind() {
        assertEquals(48, Rack(name = "Grid", rows = 6, columns = 8).capacity)
        assertEquals(96, Rack(name = "Deep", rows = 6, columns = 8, depth = 2).capacity)
        // Honeycomb: rows of 8 and 7 alternate.
        assertEquals(30, Rack(name = "Honeycomb", kind = RackKind.Staggered, rows = 4, columns = 8).capacity)
        assertEquals(48, Rack(name = "Fridge", kind = RackKind.Shelves, rows = 4, columns = 6, depth = 2).capacity)
        assertEquals(60, Rack(name = "Bins", kind = RackKind.Bins, rows = 2, columns = 3, binCapacity = 10).capacity)
        assertEquals(6, Rack(name = "Triangle", rows = 3, columns = 3, rowColumns = listOf(1, 2, 3)).capacity)
    }

    @Test
    fun honeycombOffsetRowsHoldOneFewer() {
        val rack = Rack(name = "H", kind = RackKind.Staggered, rows = 3, columns = 5, depth = 2)
        assertEquals(5, rack.columnsInRow(0))
        assertEquals(4, rack.columnsInRow(1))
        assertEquals(2, rack.positionsPerCell, "a deep honeycomb cell holds bottles one behind the other")
        assertFalse(rack.contains(Slot(rack.id, 1, 4)))
        // At most two deep.
        val deep = Rack(name = "H", kind = RackKind.Staggered, rows = 3, columns = 4, depth = 3).normalized()
        val one = Rack(name = "H", kind = RackKind.Staggered, rows = 3, columns = 4)
        assertEquals(2, deep.depth)
        assertEquals(one.allSlots.size * 2, deep.allSlots.size)
        assertTrue(deep.contains(Slot(deep.id, 1, 0, 1)))
        assertFalse(deep.contains(Slot(deep.id, 1, 0, 2)))
        assertEquals(3, Rack(name = "S", kind = RackKind.Shelves, rows = 1, columns = 1, depth = 3).normalized().depth)
    }

    /** A fridge: top shelf with 6 bottles and 5 on top, then a shelf of one layer of 6. */
    private val fridge = Rack(name = "F", kind = RackKind.Staggered, rows = 1, columns = 6, depth = 2, shelfLayers = listOf(2, 1)).normalized()

    @Test
    fun shelfLayersAreRows() {
        assertEquals(3, fridge.rows)
        assertEquals(listOf(0..1, 2..2), fridge.shelfRows)
        // Row A is the top layer of shelf 1, row B its bottom layer.
        assertEquals(Rack.ShelfPlace(0, 1, 2), fridge.shelfPlace(0))
        assertEquals(Rack.ShelfPlace(0, 0, 2), fridge.shelfPlace(1))
        assertEquals(Rack.ShelfPlace(1, 0, 1), fridge.shelfPlace(2))
        assertEquals(listOf(5, 6, 6), (0..2).map { fridge.columnsInRow(it) })
        assertEquals(listOf(true, false, false), (0..2).map { fridge.isOffset(it) })
        assertEquals((5 + 6 + 6) * 2, fridge.capacity)
        val three = Rack(name = "T", kind = RackKind.Staggered, rows = 3, columns = 7, shelfLayers = listOf(3))
        assertEquals(listOf(7, 6, 7), (0..2).map { three.columnsInRow(it) }, "quincunx: N, N-1, N from the bottom")
    }

    @Test
    fun eachShelfFillsFromItsBottomLayer() {
        val order = fridge.allSlots
        assertEquals(fridge.capacity, order.size)
        assertTrue(order.take(6).all { it.row == 1 && it.depth == 0 })
        assertTrue(order.subList(6, 12).all { it.row == 1 && it.depth == 1 })
        assertTrue(order.subList(12, 17).all { it.row == 0 && it.depth == 0 })
        assertEquals(Slot(fridge.id, 2, 5, 1), order.last())
        val cellar = Cellar(racks = listOf(fridge))
        assertEquals(Slot(fridge.id, 1, 0), cellar.freeSlots(fridge).first())
    }

    @Test
    fun shelfLayersAreBoundedAndOnlyForHoneycombs() {
        val wild = Rack(name = "W", kind = RackKind.Staggered, rows = 1, columns = 3, shelfLayers = listOf(0, 9, 2)).normalized()
        assertEquals(listOf(1, 3, 2), wild.shelfLayers)
        assertEquals(6, wild.rows)
        val many = Rack(name = "M", kind = RackKind.Staggered, rows = 1, columns = 3, shelfLayers = List(50) { 3 }).normalized()
        assertTrue(many.rows <= Rack.MAX_SIZE)
        assertEquals(33, many.shelfLayers?.size)
        assertNull(Rack(name = "G", rows = 2, columns = 2, shelfLayers = listOf(2)).normalized().shelfLayers)
        assertNull(Rack(name = "E", kind = RackKind.Staggered, rows = 2, columns = 2, shelfLayers = emptyList()).normalized().shelfLayers)
        // Decoding applies the same rules: the rows are the layers.
        val json = """{"schemaVersion":2,"racks":[{"id":"${UUID.randomUUID()}","name":"F","kind":"staggered","rows":40,"columns":6,"depth":3,"shelfLayers":[2,1]}]}"""
        val decoded = CellarJson.decode(json).racks.single()
        assertEquals(3, decoded.rows)
        assertEquals(2, decoded.depth)
        assertEquals(listOf(2, 1), decoded.shelfLayers)
        assertEquals(fridge, CellarJson.decode(CellarJson.encode(Cellar(racks = listOf(fridge)))).racks.single())
        val plain = CellarJson.encode(Cellar(racks = listOf(Rack(name = "H", kind = RackKind.Staggered, rows = 2, columns = 2))))
        assertFalse(plain.contains("shelfLayers"))
    }

    @Test
    fun labelsRoundTripWithDepth() {
        val grid = Rack(name = "G", rows = 30, columns = 5)
        assertEquals("B3", grid.label(Slot(grid.id, 1, 2)))
        val fridge = Rack(name = "F", kind = RackKind.Shelves, rows = 4, columns = 6, depth = 2)
        val back = Slot(fridge.id, 1, 2, 1)
        assertEquals("B3-2", fridge.label(back))
        assertEquals(back, fridge.slotForLabel("B3-2"))
        assertEquals(Slot(fridge.id, 1, 2), fridge.slotForLabel("b3"))
        assertNull(fridge.slotForLabel("B3-3"))
    }

    @Test
    fun binsFillOneBinBeforeTheNext() {
        val cellar = Cellar()
        val rack = Rack(name = "Bins", kind = RackKind.Bins, rows = 1, columns = 2, binCapacity = 3)
        cellar.addRack(rack)
        val added = cellar.addBottles(Wine(name = "W"), quantity = 4, slots = cellar.freeSlots(rack).take(4))
        val cells = added.mapNotNull { it.slot?.cell }
        assertEquals(3, cells.count { it.column == 0 })
        assertEquals(1, cellar.bottlesIn(RackCell(rack.id, 0, 1)).size)
        assertNull(cellar.freeSlot(RackCell(rack.id, 0, 0)))
        assertEquals(1, cellar.freeSlot(RackCell(rack.id, 0, 1))?.depth)
        assertEquals(setOf(3, 1), cellar.bottlesByCell(rack).values.map { it.size }.toSet())
    }

    @Test
    fun frontIsFilledBeforeBack() {
        val rack = Rack(name = "F", kind = RackKind.Shelves, rows = 1, columns = 3, depth = 2)
        assertTrue(rack.allSlots.take(3).all { it.depth == 0 })
    }

    @Test
    fun reducingDepthWithBottlesBehindIsRefused() {
        val cellar = Cellar()
        var rack = Rack(name = "F", kind = RackKind.Shelves, rows = 2, columns = 2, depth = 2)
        cellar.addRack(rack)
        cellar.addBottles(Wine(name = "W"), slots = listOf(Slot(rack.id, 0, 0, 1)))
        rack = rack.copy(depth = 1)
        expectError(CellarError.RackNotEmpty) { cellar.updateRack(rack) }
    }

    @Test
    fun oldRacksAndSlotsStillDecode() {
        val rackId = UUID.randomUUID()
        val wineId = UUID.randomUUID()
        val bottleId = UUID.randomUUID()
        val json = """
        {"schemaVersion":2,"racks":[{"id":"$rackId","name":"Old","rows":2,"columns":3,"layout":"diamond"}],
         "wines":["$wineId",{"id":"$wineId","name":"W","grapes":[]}],
         "bottles":["$bottleId",{"id":"$bottleId","wineID":"$wineId","status":"inCellar",
                    "addedAt":700000000,"slot":{"rackID":"$rackId","row":1,"column":2}}]}
        """
        val cellar = CellarJson.decode(json)
        assertEquals(RackKind.Grid, cellar.racks[0].kind)
        assertEquals(6, cellar.racks[0].capacity)
        assertEquals(Slot(rackId, 1, 2, 0), cellar.bottles[bottleId]?.slot)
    }

    @Test
    fun newRacksRoundTripAndLimitsApply() {
        val rack = Rack(name = "T", kind = RackKind.Bins, rows = 2, columns = 2, binCapacity = 8, rowColumns = listOf(1, 2))
        val cellar = Cellar(racks = listOf(rack, Rack(name = "X", rows = 1, columns = 1, depth = 9, kind = RackKind.Shelves)))
        val restored = CellarJson.decode(CellarJson.encode(cellar))
        assertEquals(rack, restored.racks[0])
        assertEquals(Rack.MAX_DEPTH, restored.racks[1].depth)
        // An unknown kind fails, as in Swift, rather than turning the rack into a grid (SYNC.md §1).
        val unknownKind = """{"racks":[{"id":"${UUID.randomUUID()}","name":"Z","rows":1,"columns":1,"kind":"future"}]}"""
        assertTrue(runCatching { CellarJson.decode(unknownKind) }.isFailure)
        // Racks from another device or a file are cut to a size the apps can draw.
        val huge = CellarJson.decode(
            """{"racks":[{"id":"${UUID.randomUUID()}","name":"H","rows":2000000000,"columns":-5,"binCapacity":99999,"rowColumns":[7,100000]}]}""",
        ).racks.single()
        assertEquals(listOf(Rack.MAX_SIZE, 1, Rack.MAX_SIZE), listOf(huge.rows, huge.columns, huge.binCapacity))
        assertEquals(listOf(7, Rack.MAX_SIZE), huge.rowColumns)
    }

    @Test
    fun csvRoundTripKeepsBinAndBackPositions() {
        val original = SampleData.cellar(year = 2026)
        val target = Cellar(racks = original.racks)
        target.importCsv(CellarExport.csv(original))
        assertEquals(original.bottlesInCellar.mapNotNull { it.slot }.toSet(), target.bottlesInCellar.mapNotNull { it.slot }.toSet())
    }

    @Test
    fun csvFillsTheNextPlaceWhenALabelIsTaken() {
        val rack = Rack(name = "Bins", kind = RackKind.Bins, rows = 1, columns = 1, binCapacity = 3)
        val cellar = Cellar(racks = listOf(rack))
        cellar.importCsv("name,rack,slot,quantity\nW,Bins,A1,2\n")
        assertEquals(setOf(0, 1), cellar.bottlesInCellar.mapNotNull { it.slot?.depth }.toSet())
    }

    @Test
    fun sampleCellarShowsEveryKindOfRack() {
        val cellar = SampleData.cellar(year = 2026)
        val racks = cellar.racks
        assertEquals(RackKind.entries.toSet(), racks.map { it.kind }.toSet())
        assertTrue(racks.any { it.depth == 2 && it.kind == RackKind.Grid })
        assertTrue(racks.any { it.rowColumns != null })
        assertTrue(racks.any { it.hasShelfLayers }, "a fridge with bottles in layers")
        racks.forEach { assertTrue(cellar.occupancy(it) > 0, "${it.name} has bottles") }
        assertTrue(cellar.bottlesInCellar.any { (it.slot?.depth ?: 0) > 0 }, "a bottle sits behind another")
    }
}
