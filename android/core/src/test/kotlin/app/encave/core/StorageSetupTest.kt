package app.encave.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StorageSetupTest {
    @Test
    fun gridRack() {
        val rack = StorageSettings(StorageType.Rack, rows = 6, columns = 8, depth = 2).rack(name = "Rack")
        assertEquals(RackKind.Grid, rack.kind)
        assertEquals(listOf(6, 8, 2, 96), listOf(rack.rows, rack.columns, rack.depth, rack.capacity))
        assertNull(rack.shelfLayers)
        assertNull(rack.backReversed)
        assertEquals(StorageType.Rack, rack.storageType)
        assertEquals(6, rack.storageCount)
    }

    @Test
    fun honeycombRackIsContinuous() {
        val settings = StorageSettings(StorageType.Rack, layout = RackLayout.Honeycomb, rows = 5, columns = 8, depth = 3)
        val rack = settings.rack(name = "Honeycomb")
        assertEquals(RackKind.Staggered, rack.kind)
        assertNull(rack.shelfLayers)
        assertEquals(2, rack.depth, "a honeycomb holds two bottles deep at most")
        assertEquals(StorageType.Rack, rack.storageType)
        assertEquals(RackLayout.Honeycomb, StorageSettings.of(rack).layout)
    }

    @Test
    fun diamondBinsRack() {
        val rack = StorageSettings(StorageType.Rack, layout = RackLayout.Diamond, rows = 2, columns = 3, bottlesPerBin = 10)
            .rack(name = "Diamonds")
        assertEquals(RackKind.Bins, rack.kind)
        assertEquals(10, rack.binCapacity)
        assertEquals(60, rack.capacity)
        // Diamond bins stay a rack once saved, counted in bins, and edit as they were made.
        assertEquals(StorageType.Rack, rack.storageType)
        assertEquals(6, rack.storageCount)
        assertEquals(
            StorageSettings(StorageType.Rack, layout = RackLayout.Diamond, rows = 2, columns = 3, bottlesPerBin = 10),
            StorageSettings.of(rack),
        )
        assertEquals(StorageType.Rack, StorageSettings(StorageType.Rack, layout = RackLayout.Diamond).rack(name = "").storageType)
    }

    @Test
    fun cratesStayCrates() {
        for (bins in 1..StorageSettings.MAX_COUNT) {
            val rack = StorageSettings(StorageType.Crates, bins = bins).rack(name = "Crates")
            assertEquals(StorageType.Crates, rack.storageType, "$bins bins")
            assertEquals(bins, rack.storageCount, "$bins bins")
        }
    }

    @Test
    fun fridgeWithOneRowPerShelfIsShelves() {
        val settings = StorageSettings(StorageType.WineFridge, shelves = 4, bottlesPerShelf = 7, layersPerShelf = 1, depth = 2)
        val rack = settings.rack(name = "Fridge")
        assertEquals(RackKind.Shelves, rack.kind)
        assertEquals(listOf(4, 7, 2), listOf(rack.rows, rack.columns, rack.depth))
        assertNull(rack.backReversed, "head-to-tail needs layers")
        assertEquals(StorageType.WineFridge, rack.storageType)
        assertEquals(4, rack.storageCount)
        assertEquals(settings.copy(headToTail = false), StorageSettings.of(rack))
    }

    @Test
    fun fridgeInLayersIsAHoneycombOnShelves() {
        val settings = StorageSettings(StorageType.WineFridge, shelves = 4, bottlesPerShelf = 6, layersPerShelf = 2, depth = 2)
        val rack = settings.rack(name = "Fridge")
        assertEquals(RackKind.Staggered, rack.kind)
        assertEquals(listOf(2, 2, 2, 2), rack.shelfLayers)
        assertEquals(8, rack.rows)
        // Each shelf: 5 on top resting in the gaps of 6, two deep.
        assertEquals(listOf(5, 6, 5, 6, 5, 6, 5, 6), (0 until rack.rows).map { rack.columnsInRow(it) })
        assertEquals(88, rack.capacity)
        assertTrue(rack.isBackReversed)
        assertEquals(StorageType.WineFridge, rack.storageType)
        assertEquals(4, rack.storageCount)
        assertEquals(settings, StorageSettings.of(rack))
    }

    @Test
    fun threeLayersFollowTheQuincunx() {
        val rack = StorageSettings(StorageType.WineFridge, shelves = 1, bottlesPerShelf = 6, layersPerShelf = 3, depth = 1)
            .rack(name = "Fridge")
        assertEquals(listOf(6, 5, 6), (0 until rack.rows).map { rack.columnsInRow(it) })
    }

    @Test
    fun headToTailOnlyTwoDeepWithLayers() {
        var settings = StorageSettings(StorageType.WineFridge, headToTail = true, layersPerShelf = 2, depth = 1)
        assertFalse(settings.offersHeadToTail)
        assertNull(settings.rack(name = "").backReversed)
        settings = settings.copy(layersPerShelf = 1, depth = 2)
        assertFalse(settings.offersHeadToTail)
        assertNull(settings.rack(name = "").backReversed)
        settings = settings.copy(layersPerShelf = 2, depth = 2)
        assertTrue(settings.offersHeadToTail)
        assertEquals(true, settings.rack(name = "").backReversed)
        assertNull(settings.copy(headToTail = false).rack(name = "").backReversed)
    }

    @Test
    fun cratesFillRowsOfFour() {
        val settings = StorageSettings(StorageType.Crates, bins = 10, bottlesPerBin = 6)
        val rack = settings.rack(name = "Crates")
        assertEquals(RackKind.Bins, rack.kind)
        assertEquals(listOf(3, 4), listOf(rack.rows, rack.columns))
        assertEquals(listOf(4, 4, 2), rack.rowColumns)
        assertEquals(10, rack.cellCount)
        assertEquals(60, rack.capacity)
        assertEquals(StorageType.Crates, rack.storageType)
        assertEquals(10, rack.storageCount)
        assertNull(settings.copy(bins = 8).rack(name = "").rowColumns)
        assertEquals(2, settings.copy(bins = 8).rack(name = "").rows)
        assertEquals(listOf(1, 3), settings.copy(bins = 3).rack(name = "").let { listOf(it.rows, it.columns) })
    }

    @Test
    fun edgeValuesAreBounded() {
        val crates = StorageSettings(StorageType.Crates, bins = 0, bottlesPerBin = 0).rack(name = "")
        assertEquals(1, crates.cellCount)
        assertEquals(1, crates.binCapacity)
        assertEquals(StorageSettings.MAX_COUNT, StorageSettings(StorageType.Crates, bins = 1000).rack(name = "").cellCount)
        val fridge = StorageSettings(StorageType.WineFridge, shelves = 500, layersPerShelf = 9, depth = 5, bottlesPerShelf = -3)
            .rack(name = "")
        assertEquals(StorageSettings.MAX_SHELVES, fridge.shelfLayers?.size)
        assertTrue(fridge.shelfLayers!!.all { it == 3 })
        assertTrue(fridge.rows <= Rack.MAX_SIZE)
        assertEquals(2, fridge.depth)
        assertEquals(1, fridge.columns)
        val grid = StorageSettings(StorageType.Rack, rows = 0, columns = 99, depth = 3).rack(name = "")
        assertEquals(listOf(1, StorageSettings.MAX_COUNT, 2), listOf(grid.rows, grid.columns, grid.depth))
    }

    @Test
    fun defaultsPerType() {
        assertTrue(StorageSettings(StorageType.WineFridge).rack(name = "").isBackReversed)
        assertEquals(12, StorageSettings(StorageType.Crates).rack(name = "").binCapacity)
        assertEquals(48, StorageSettings(StorageType.Rack).rack(name = "").capacity)
    }

    @Test
    fun uniqueNames() {
        assertEquals("Rack", StorageSettings.uniqueName("Rack", emptyList()))
        assertEquals("Rack 3", StorageSettings.uniqueName("Rack", listOf("rack", "Rack 2")))
        assertEquals("Crates", StorageSettings.uniqueName("Crates", listOf("Rack")))
    }
}
