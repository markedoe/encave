package app.encave.core

import app.encave.core.sync.SyncJson
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
    fun shelvesNumberTheirEmptyPlacesOthersUseCodes() {
        val grid = Rack(name = "G", rows = 3, columns = 4)
        assertEquals("B3", grid.emptyCaption(RackCell(grid.id, 1, 2)))
        val honeycomb = Rack(name = "H", kind = RackKind.Staggered, rows = 3, columns = 5)
        assertEquals("C1", honeycomb.emptyCaption(RackCell(honeycomb.id, 2, 0)))
        val shelves = Rack(name = "S", kind = RackKind.Shelves, rows = 4, columns = 6, depth = 2)
        assertTrue(shelves.numbersPlacesOnShelves)
        assertEquals("6", shelves.emptyCaption(RackCell(shelves.id, 2, 5)))
        val fridge = Rack(name = "F", kind = RackKind.Staggered, rows = 3, columns = 6, depth = 2, shelfLayers = listOf(2, 1))
        assertEquals("4", fridge.emptyCaption(RackCell(fridge.id, 2, 3)))
        assertFalse(Rack(name = "B", kind = RackKind.Bins, rows = 2, columns = 2).numbersPlacesOnShelves)
        // Rows beyond Z: grids keep their two-letter codes.
        val tall = Rack(name = "T", rows = 30, columns = 2)
        assertEquals("AB2", tall.emptyCaption(RackCell(tall.id, 27, 1)))
        // Shelves of different lengths still number from the left.
        val custom = Rack(name = "C", kind = RackKind.Shelves, rows = 2, columns = 4, rowColumns = listOf(2, 4))
        assertEquals("4", custom.emptyCaption(RackCell(custom.id, 1, 3)))
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
        // Zones: breaks between kept shelves only, sorted, once.
        val zoned = Rack(name = "Z", kind = RackKind.Staggered, rows = 1, columns = 6, shelfLayers = listOf(1, 2, 1, 1),
            zoneBreaks = listOf(3, 0, 2, 2, 9)).normalized()
        assertEquals(listOf(2, 3), zoned.zoneBreaks)
        assertEquals(listOf(0, 0, 1, 2), (0..3).map { zoned.zoneOfShelf(it) })
        assertEquals(3, zoned.zoneCount)
        assertNull(Rack(name = "N", kind = RackKind.Staggered, rows = 1, columns = 6, shelfLayers = listOf(1, 1), zoneBreaks = listOf(0)).normalized().zoneBreaks)
        assertNull(Rack(name = "C", kind = RackKind.Staggered, rows = 2, columns = 6, zoneBreaks = listOf(1)).normalized().zoneBreaks)
        assertEquals(zoned, CellarJson.decode(CellarJson.encode(Cellar(racks = listOf(zoned)))).racks.single())
        // The sample fridge: two zones, shelves 1–4 then 5–10, as the editor's preset.
        val sample = SampleData.cellar(year = 2026).racks.first { it.hasShelfLayers }
        assertEquals(10, sample.shelfLayers?.size)
        assertEquals(listOf(4), sample.zoneBreaks)
        assertEquals(SampleData.TWO_ZONE_FRIDGE_ROWS.size, sample.rows)
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

    /** Shared with the Swift RackTests: a fridge of five shelves of two layers. */
    @Test
    fun duplicatingAShelfMovesTheBottlesBelowIt() {
        val cellar = Cellar()
        val fridge = Rack(name = "F", kind = RackKind.Staggered, rows = 10, columns = 6, depth = 2, shelfLayers = List(5) { 2 }).normalized()
        cellar.addRack(fridge)
        // Shelf 3, bottom layer (its last row), place 3, back.
        val bottle = cellar.addBottles(Wine(name = "W"), slots = listOf(Slot(fridge.id, 5, 2, 1)))[0]
        // Shelf 1 duplicated: a new shelf 2, the old shelves 2 to 5 one lower.
        val edited = fridge.copy(rows = 12, shelfLayers = List(6) { 2 }).normalized()
        val map = Rack.shelfRowMap(fridge, edited, listOf(0, null, 1, 2, 3, 4))
        assertEquals(7, map[5])
        assertTrue(cellar.displacedBottles(edited, map).isEmpty())
        cellar.updateRack(edited, map)
        val slot = cellar.bottles[bottle.id]?.slot
        assertEquals(Slot(fridge.id, 7, 2, 1), slot)
        assertEquals(3, edited.shelfPlace(slot!!.row)?.shelf)
        assertEquals(0, edited.shelfPlace(slot.row)?.layer)
    }

    @Test
    fun removingAShelfOrLayerDisplacesItsBottles() {
        val cellar = Cellar()
        val fridge = Rack(name = "F", kind = RackKind.Staggered, rows = 6, columns = 6, depth = 2, shelfLayers = listOf(2, 2, 2)).normalized()
        cellar.addRack(fridge)
        val bottles = cellar.addBottles(
            Wine(name = "W"), quantity = 3,
            slots = listOf(
                Slot(fridge.id, 2, 0), // shelf 2, top layer
                Slot(fridge.id, 3, 1), // shelf 2, bottom layer
                Slot(fridge.id, 5, 4), // shelf 3, bottom layer
            ),
        )
        // Shelf 2 removed, shelf 3 down to one layer of 4.
        val edited = fridge.copy(rows = 3, rowColumns = listOf(5, 6, 4), shelfLayers = listOf(2, 1)).normalized()
        val map = Rack.shelfRowMap(fridge, edited, listOf(0, 2))
        assertTrue(map.containsKey(2) && map[2] == null)
        assertTrue(map.containsKey(4) && map[4] == null, "the top layer of shelf 3 is gone")
        assertEquals(bottles.map { it.id }, cellar.displacedBottles(edited, map).map { it.id }, "place 5 no longer fits a layer of 4")
        expectError(CellarError.RackNotEmpty) { cellar.updateRack(edited, map) }
        cellar.updateRack(edited, map, takingOut = true)
        assertTrue(bottles.all { cellar.bottles[it.id]?.slot == null && cellar.bottles[it.id]?.status == Bottle.Status.InCellar })
        assertEquals(3, cellar.racks[0].rows)
    }

    @Test
    fun shrinkingCanTakeBottlesOut() {
        val cellar = Cellar()
        var rack = Rack(name = "G", rows = 3, columns = 3)
        cellar.addRack(rack)
        val kept = cellar.addBottles(Wine(name = "W"), slots = listOf(Slot(rack.id, 0, 0)))[0]
        val out = cellar.addBottles(Wine(name = "W"), slots = listOf(Slot(rack.id, 2, 1)))[0]
        rack = rack.copy(rows = 2)
        assertEquals(listOf(out.id), cellar.displacedBottles(rack).map { it.id })
        cellar.updateRack(rack, takingOut = true)
        assertNull(cellar.bottles[out.id]?.slot)
        assertEquals(Slot(rack.id, 0, 0), cellar.bottles[kept.id]?.slot)
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

    @Test
    fun headToTailBacksOnlyOnTwoDeepFridgesWithLayers() {
        val fridge = Rack(
            name = "F", kind = RackKind.Staggered, rows = 2, columns = 4, depth = 2, shelfLayers = listOf(2, 2),
            backReversed = true,
        ).normalized()
        assertTrue(fridge.hasDepthBands && fridge.isBackReversed && fridge.backReversed == true)
        // Nowhere else: one deep, a continuous honeycomb, a grid; false is left out.
        assertNull(Rack(name = "1", kind = RackKind.Staggered, rows = 2, columns = 4, shelfLayers = listOf(2), backReversed = true).normalized().backReversed)
        assertNull(Rack(name = "H", kind = RackKind.Staggered, rows = 2, columns = 4, depth = 2, backReversed = true).normalized().backReversed)
        assertNull(Rack(name = "G", rows = 2, columns = 4, depth = 2, backReversed = true).normalized().backReversed)
        assertNull(
            Rack(name = "F", kind = RackKind.Staggered, rows = 2, columns = 4, depth = 2, shelfLayers = listOf(2), backReversed = false)
                .normalized().backReversed,
        )

        val encoded = SyncJson.encodeCellar(Cellar(racks = listOf(fridge))).decodeToString()
        assertTrue(encoded.contains("\"backReversed\":true"), encoded)
        assertEquals(fridge, SyncJson.decodeCellar(encoded.toByteArray()).racks.single())
        val plain = SyncJson.encodeCellar(Cellar(racks = listOf(fridge.copy(backReversed = null)))).decodeToString()
        assertFalse(plain.contains("backReversed"), plain)

        // Older racks have no flag; a flag where it does not apply is dropped when read.
        val id = UUID.randomUUID()
        val old = """{"schemaVersion":2,"racks":[{"id":"$id","name":"Old","kind":"staggered","rows":2,"columns":4,"depth":2,"shelfLayers":[2]}]}"""
        assertNull(SyncJson.decodeCellar(old.toByteArray()).racks.single().backReversed)
        val grid = """{"schemaVersion":2,"racks":[{"id":"$id","name":"G","kind":"grid","rows":2,"columns":4,"depth":2,"backReversed":true}]}"""
        val decoded = SyncJson.decodeCellar(grid.toByteArray()).racks.single()
        assertNull(decoded.backReversed)
        assertFalse(decoded.isBackReversed)
    }

    @Test
    fun headToTailBackLayersShiftHalfAPlace() {
        // Each neck behind sits between two bases in front: the back band moves half a place right.
        val fridge = Rack(name = "F", kind = RackKind.Staggered, rows = 2, columns = 4, depth = 2, shelfLayers = listOf(2), backReversed = true)
        assertEquals(0f, fridge.bandShift(0))
        assertEquals(0.5f, fridge.bandShift(1))
        assertEquals(0f, fridge.bandShift(null))
        // Not head-to-tail, one deep, a continuous honeycomb, a grid: never shifted.
        val others = listOf(
            Rack(name = "B", kind = RackKind.Staggered, rows = 2, columns = 4, depth = 2, shelfLayers = listOf(2)),
            Rack(name = "1", kind = RackKind.Staggered, rows = 2, columns = 4, shelfLayers = listOf(2), backReversed = true),
            Rack(name = "H", kind = RackKind.Staggered, rows = 2, columns = 4, depth = 2, backReversed = true),
            Rack(name = "G", rows = 2, columns = 4, depth = 2, backReversed = true),
            Rack(name = "S", kind = RackKind.Shelves, rows = 2, columns = 4, depth = 2),
        )
        for (rack in others) {
            assertEquals(0f, rack.bandShift(0), rack.name)
            assertEquals(0f, rack.bandShift(1), rack.name)
        }
    }

    @Test
    fun sampleHasAHeadToTailFridgeWithBottlesFrontAndBack() {
        val cellar = SampleData.cellar(year = 2026)
        val fridge = cellar.racks.firstOrNull { it.isBackReversed }
        // Each shelf its own layout: 4 + 3, a single row of 7, 6 + 5.
        assertEquals(listOf(2, 1, 2), fridge?.shelfLayers)
        assertEquals(listOf(3, 4, 7, 5, 6), fridge?.let { rack -> (0 until rack.rows).map { rack.columnsInRow(it) } })
        assertEquals(Rack.ShelfPlace(1, 0, 1), fridge?.shelfPlace(2))
        val depths = cellar.bottlesInCellar.filter { it.slot?.rackId == fridge?.id }.mapNotNull { it.slot?.depth }.toSet()
        assertEquals(setOf(0, 1), depths)
    }
}
