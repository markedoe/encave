package app.encave.core

import java.math.BigDecimal
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

private fun expectImportError(error: ImportError, block: () -> Unit) {
    assertEquals(error, assertThrows<ImportException> { block() }.error)
}

class FilterAndSortTest {
    private val cellar = SampleData.cellar(year = 2026)

    @Test
    fun colorFilterKeepsOnlyThatStyle() {
        val reds = cellar.groups(CellarFilter(colors = setOf(WineColor.Red)), CellarSort.Name, 2026)
        assertFalse(reds.isEmpty())
        assertTrue(reds.all { it.wine.color == WineColor.Red })
    }

    @Test
    fun maturityFilter() {
        val ready = cellar.groups(CellarFilter(maturities = setOf(Maturity.DrinkSoon)), CellarSort.Name, 2026)
        assertFalse(ready.isEmpty())
        assertTrue(ready.all { Maturity.of(it.wine, 2026) == Maturity.DrinkSoon })
    }

    @Test
    fun rackFilterKeepsOnlyBottlesInThatRack() {
        val rack = cellar.racks.first()
        val groups = cellar.groups(CellarFilter(rackId = rack.id), CellarSort.Name, 2026)
        assertTrue(groups.flatMap { it.bottles }.all { it.slot?.rackId == rack.id })
    }

    @Test
    fun searchIgnoresAccents() {
        assertFalse(cellar.groups("Rose de Provence").isEmpty())
        assertFalse(cellar.groups("cuvee").isEmpty())
    }

    @ParameterizedTest
    @EnumSource(CellarSort::class)
    fun everySortKeepsAllGroups(sort: CellarSort) {
        assertEquals(cellar.groups().size, cellar.groups(CellarFilter(), sort, 2026).size)
    }

    @Test
    fun vintageSortIsAscendingWithNonVintageLast() {
        val vintages = cellar.groups(CellarFilter(), CellarSort.Vintage, 2026).map { it.wine.vintage }
        val known = vintages.filterNotNull()
        assertEquals(known.sorted(), known)
        assertNull(vintages.last())
    }

    @Test
    fun readinessSortPutsMostUrgentFirst() {
        val order = cellar.groups(CellarFilter(), CellarSort.Readiness, 2026).map { Maturity.of(it.wine, 2026) }
        assertEquals(order.sorted(), order)
    }

    @Test
    fun nameSortIsCaseInsensitive() {
        val cellar = Cellar()
        listOf("beta", "Alpha", "gamma").forEach { cellar.addBottles(Wine(name = it)) }
        assertEquals(listOf("Alpha", "beta", "gamma"), cellar.groups().map { it.wine.name })
    }
}

class CommandTest {
    @Test
    fun purchaseInfoIsAppliedToEveryBottle() {
        val cellar = Cellar(settings = CellarSettings(currency = "CHF"))
        val added = cellar.addBottles(Wine(name = "W"), quantity = 2, purchase = PurchaseInfo(price = BigDecimal(20), place = "Shop"))
        assertTrue(added.all { it.purchasePrice == BigDecimal(20) && it.currency == "CHF" && it.purchasePlace == "Shop" })
    }

    @Test
    fun swapExchangesTwoSlots() {
        val cellar = Cellar()
        val rack = Rack(name = "A", rows = 1, columns = 2)
        cellar.addRack(rack)
        val a = Slot(rack.id, 0, 0)
        val b = Slot(rack.id, 0, 1)
        val first = cellar.addBottles(Wine(name = "W"), slots = listOf(a))[0]
        cellar.swap(a, b)
        assertEquals(b, cellar.bottles[first.id]?.slot)
        assertNull(cellar.bottleAt(a))
    }

    @Test
    fun shrinkingARackWithBottlesOutsideIsRefused() {
        val cellar = Cellar()
        var rack = Rack(name = "A", rows = 3, columns = 3)
        cellar.addRack(rack)
        cellar.addBottles(Wine(name = "W"), slots = listOf(Slot(rack.id, 2, 2)))
        rack = rack.copy(rows = 2)
        expectError(CellarError.RackNotEmpty) { cellar.updateRack(rack) }
        rack = rack.copy(rows = 3, kind = RackKind.Shelves)
        cellar.updateRack(rack)
        assertEquals(RackKind.Shelves, cellar.racks[0].kind)
    }

    @Test
    fun deletingLastBottleRemovesTheWine() {
        val cellar = Cellar()
        val bottle = cellar.addBottles(Wine(name = "W"))[0]
        cellar.deleteBottle(bottle.id)
        assertTrue(cellar.wines.isEmpty())
    }

    @Test
    fun goneBottlesLeaveTheCellarButNotTheJournal() {
        val cellar = Cellar()
        val bottle = cellar.addBottles(Wine(name = "W"))[0]
        cellar.markGone(bottle.id)
        assertTrue(cellar.bottlesInCellar.isEmpty())
        assertTrue(cellar.consumedBottles.isEmpty())
    }

    @Test
    fun reorderingRacks() {
        val cellar = Cellar(racks = listOf("A", "B", "C").map { Rack(name = it, rows = 1, columns = 1) })
        cellar.moveRacks(setOf(0), 3)
        assertEquals(listOf("B", "C", "A"), cellar.racks.map { it.name })
        cellar.moveRacks(setOf(2), 0)
        assertEquals(listOf("A", "B", "C"), cellar.racks.map { it.name })
    }

    @Test
    fun deletingAWineRemovesItsBottles() {
        val cellar = Cellar()
        val wine = Wine(name = "W")
        cellar.addBottles(wine, quantity = 3)
        cellar.deleteWine(wine.id)
        assertTrue(cellar.bottles.isEmpty())
        assertTrue(cellar.wines.isEmpty())
    }

    @Test
    fun updatingATastingKeepsTheBottleConsumed() {
        val cellar = Cellar()
        val bottle = cellar.addBottles(Wine(name = "W"))[0]
        expectError(CellarError.UnknownBottle) { cellar.updateTasting(bottle.id, Tasting(rating = 3)) }
        cellar.drink(bottle.id, Tasting(rating = 3))
        cellar.updateTasting(bottle.id, Tasting(rating = 0, nose = "Cherry"))
        val tasting = cellar.bottles.getValue(bottle.id).tasting!!
        assertEquals(1, tasting.rating)
        assertTrue(tasting.isDetailed)
    }
}

class StatisticsTest {
    @Test
    fun countsAndValue() {
        val cellar = SampleData.cellar(year = 2026)
        val stats = cellar.statistics(2026)
        assertEquals(cellar.bottlesInCellar.size, stats.bottleCount)
        assertEquals(2, stats.consumedCount)
        assertEquals(4.5, stats.averageRating)
        assertTrue((stats.valueByCurrency["EUR"] ?: BigDecimal.ZERO) > BigDecimal.ZERO)
        assertEquals(12, stats.consumptionByMonth.size)
        assertEquals(stats.bottleCount, stats.byColor.sumOf { it.count })
    }

    /** Equal counts in the styles' order, unknown last, whatever order the bottles came in (as in CaveKit). */
    @Test
    fun stylesWithEqualCountsKeepTheStylesOrder() {
        val cellar = Cellar()
        cellar.addBottles(Wine(name = "Two reds A", color = WineColor.Red), 1)
        for (color in listOf(WineColor.Orange, null, WineColor.Sparkling, WineColor.White, WineColor.Red)) {
            cellar.addBottles(Wine(name = "One ${color?.raw}", color = color), 1)
        }
        assertEquals(
            listOf(WineColor.Red to 2, WineColor.White to 1, WineColor.Sparkling to 1, WineColor.Orange to 1, null to 1),
            cellar.statistics(2026).byColor.map { it.color to it.count },
        )
    }

    @Test
    fun decadesAreOldestFirstWithNonVintageLast() {
        val stats = SampleData.cellar(year = 2026).statistics(2026)
        val labels = stats.byVintageDecade.map { it.label }
        assertEquals(CellarStatistics.NON_VINTAGE, labels.last())
        assertEquals(labels.dropLast(1).sorted(), labels.dropLast(1))
        assertEquals(2, stats.consumptionByMonth.sumOf { it.count })
    }
}

class PairingTest {
    @Test
    fun fishPrefersWhites() {
        val best = SampleData.cellar(year = 2026).pairings(DishCategory.Fish, 2026).first()
        assertEquals(WineColor.White, best.group.wine.color)
    }

    @Test
    fun redMeatPrefersReadyReds() {
        val best = SampleData.cellar(year = 2026).pairings(DishCategory.RedMeat, 2026).first()
        assertEquals(WineColor.Red, best.group.wine.color)
        assertTrue(PairingReason.ReadyToDrink in best.reasons)
    }

    @Test
    fun reservedBottlesAreNotSuggested() {
        val cellar = SampleData.cellar(year = 2026)
        val best = cellar.pairings(DishCategory.Fish, 2026).first()
        cellar.reserve(best.group.bottles.map { it.id }, "Wedding")
        assertTrue(cellar.pairings(DishCategory.Fish, 2026).none { it.group.wine.id == best.group.wine.id })
    }

    @Test
    fun scoresAreBoundedAndSorted() {
        val cellar = SampleData.cellar(year = 2026)
        for (dish in DishCategory.entries) {
            val scores = cellar.pairings(dish, 2026).map { it.score }
            assertTrue(scores.all { it in 0..100 })
            assertEquals(scores.sortedDescending(), scores)
        }
    }
}

class ImportExportTest {
    @Test
    fun jsonBackupRoundTrips() {
        val cellar = SampleData.cellar(year = 2026)
        val restored = CellarExport.backupFromJson(CellarExport.json(cellar))
        assertEquals(cellar.bottles.size, restored.cellar.bottles.size)
        assertEquals(cellar.racks, restored.cellar.racks)
    }

    @Test
    fun rejectsForeignJson() {
        expectImportError(ImportError.NotAnEncaveBackup) { CellarExport.backupFromJson("{}") }
        expectImportError(ImportError.NotAnEncaveBackup) {
            CellarExport.backupFromJson("""{"format":"other","version":2,"exportedAt":"2026-01-01T00:00:00Z","cellar":{}}""")
        }
    }

    @Test
    fun csvRoundTripKeepsWinesBottlesAndSlots() {
        val original = SampleData.cellar(year = 2026)
        val csv = CellarExport.csv(original)
        val target = Cellar(racks = original.racks)
        val summary = target.importCsv(csv)
        assertEquals(original.bottles.size, summary.bottlesAdded)
        // The CSV lists bottles: wines only known from discoveries are not in it.
        assertEquals(original.bottles.values.map { it.wineId }.toSet().size, target.wines.size)
        assertEquals(original.bottlesInCellar.size, target.bottlesInCellar.size)
        assertEquals(original.consumedBottles.size, target.consumedBottles.size)
        assertEquals(original.bottlesInCellar.mapNotNull { it.slot }.toSet(), target.bottlesInCellar.mapNotNull { it.slot }.toSet())
    }

    @Test
    fun csvHandlesQuotesSemicolonsAndQuantity() {
        val csv = "Name;Vintage;Quantity;Note\n\"Château \"\"X\"\", grand\";2015;3;\"multi\nline\"\n"
        val cellar = Cellar()
        val summary = cellar.importCsv(csv)
        assertEquals(3, summary.bottlesAdded)
        assertEquals("Château \"X\", grand", cellar.wines.values.first().name)
    }

    @Test
    fun csvHandlesCrLfAndByteOrderMark() {
        val rows = CsvParser.parse("﻿name,vintage\r\nA,2015\r\n\r\nB,\r\n")
        assertEquals(listOf(listOf("name", "vintage"), listOf("A", "2015"), listOf("B", "")), rows)
    }

    @Test
    fun csvWithoutNameColumnFails() {
        expectImportError(ImportError.MissingNameColumn) { Cellar().importCsv("producer\nX\n") }
        expectImportError(ImportError.EmptyFile) { Cellar().importCsv("") }
    }

    @Test
    fun csvEscapesSeparatorsAndQuotes() {
        assertEquals("plain", CellarExport.escape("plain"))
        assertEquals("\"a;b\"", CellarExport.escape("a;b"))
        assertEquals("\"say \"\"hi\"\"\"", CellarExport.escape("say \"hi\""))
    }

    @Test
    fun csvUnknownRackBecomesStorageNote() {
        val cellar = Cellar()
        cellar.importCsv("name,rack,slot,price\nW,Garage,B2,\"12,50\"\n")
        val bottle = cellar.bottles.values.single()
        assertEquals("Garage · B2", bottle.storageNote)
        assertEquals(BigDecimal("12.5"), bottle.purchasePrice)
    }

    @Test
    fun slotLabelsParse() {
        val rack = Rack(name = "A", rows = 30, columns = 5)
        assertEquals(Slot(rack.id, 1, 2), rack.slotForLabel("B3"))
        assertEquals(Slot(rack.id, 26, 0), rack.slotForLabel("AA1"))
        assertNull(rack.slotForLabel("Z9"))
    }

    @Test
    fun mergeAddsOnlyMissingRecords() {
        val cellar = SampleData.cellar(year = 2026)
        val summary = cellar.merge(cellar.copy())
        assertEquals(0, summary.bottlesAdded)
        // The sample's IDs are fixed: another cellar with the same content, new IDs.
        val other = SampleData.cellar(year = 2026).withNewIds()
        val added = cellar.merge(other)
        assertEquals(other.bottles.size, added.bottlesAdded)
        val slots = cellar.bottlesInCellar.mapNotNull { it.slot }
        assertEquals(slots.size, slots.toSet().size, "merged bottles never share a slot")
    }
}

/** The same records under new IDs. */
internal fun Cellar.withNewIds(): Cellar {
    val racks = racks.associate { it.id to UUID.randomUUID() }
    val wines = wines.keys.associateWith { UUID.randomUUID() }
    return Cellar(
        wines = this.wines.values.map { it.copy(id = wines.getValue(it.id)) },
        bottles = bottles.values.map { bottle ->
            bottle.copy(
                id = UUID.randomUUID(), wineId = wines.getValue(bottle.wineId),
                slot = bottle.slot?.let { it.copy(rackId = racks.getValue(it.rackId)) },
            )
        },
        racks = this.racks.map { it.copy(id = racks.getValue(it.id)) },
        settings = settings,
        discoveries = discoveries.values.map { it.copy(id = UUID.randomUUID(), wineId = wines.getValue(it.wineId)) },
    )
}

class CompatibilityTest {
    /** Files written by version 0.1 (schema 1) must still load. */
    @Test
    fun decodesSchemaOne() {
        val wineId = UUID.randomUUID()
        val bottleId = UUID.randomUUID()
        val rackId = UUID.randomUUID()
        val json = """
        {"schemaVersion":1,"racks":[{"id":"$rackId","name":"A","rows":2,"columns":2}],
         "wines":["$wineId",{"id":"$wineId","name":"Old","grapes":[]}],
         "bottles":["$bottleId",{"id":"$bottleId","wineID":"$wineId","status":"inCellar","addedAt":700000000}]}
        """
        val cellar = CellarJson.decode(json)
        assertEquals(RackKind.Grid, cellar.racks[0].kind)
        assertEquals(750, cellar.bottles[bottleId]?.sizeML)
        assertEquals("EUR", cellar.settings.currency)
        assertEquals(Cellar.CURRENT_SCHEMA_VERSION, cellar.schemaVersion)
        // 700000000 seconds after 2001-01-01, Foundation's reference date.
        assertEquals(java.time.Instant.parse("2023-03-08T20:26:40Z"), cellar.bottles[bottleId]?.addedAt)
    }
}
