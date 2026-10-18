package app.encave.core

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Ported from CaveKit's FeatureTests: on order, tags, drink-down plan, window source,
// year in wine, the monthly digest, barcodes, bulk actions and other apps' CSV exports.

private fun reference(seconds: Long): Instant = InstantSerializer.fromReferenceSeconds(seconds.toDouble())

class OnOrderTest {
    @Test
    fun orderedBottlesStayOutOfTheCellarUntilReceived() {
        val cellar = Cellar()
        val rack = Rack(name = "Small", rows = 1, columns = 2)
        cellar.addRack(rack)
        val soon = reference(800_000_000)
        val wine = Wine(name = "En primeur", vintage = 2024)
        val ordered = cellar.addBottles(wine, 2, purchase = PurchaseInfo(price = BigDecimal(30), isOrdered = true, expectedAt = soon))
        assertTrue(ordered.all { it.status == Bottle.Status.Ordered && it.expectedAt == soon })
        assertTrue(cellar.bottlesInCellar.isEmpty())
        assertTrue(cellar.groups().isEmpty())
        assertEquals(listOf(wine.id), cellar.orderGroups().map { it.wine.id })
        assertEquals(0, cellar.statistics(2026).bottleCount)
        assertNotNull(cellar.wines[wine.id], "a wine with only ordered bottles is kept")

        expectError(CellarError.NotReceived) {
            cellar.addBottles(wine, slots = listOf(Slot(rack.id, 0, 0)), purchase = PurchaseInfo(isOrdered = true))
        }
        expectError(CellarError.NotReceived) { cellar.drink(ordered[0].id, Tasting()) }

        val arrival = reference(800_100_000)
        cellar.receive(ordered.map { it.id }, listOf(Slot(rack.id, 0, 1)), arrival)
        val received = cellar.bottlesOf(wine.id)
        assertEquals(2, received.size)
        assertTrue(received.all { it.expectedAt == null && it.addedAt == arrival })
        assertEquals(listOf(Slot(rack.id, 0, 1)), received.mapNotNull { it.slot })
        assertTrue(cellar.orderGroups().isEmpty())
        expectError(CellarError.UnknownBottle) { cellar.receive(listOf(received[0].id)) }
    }

    @Test
    fun ordersSortBySoonestArrival() {
        val cellar = Cellar()
        cellar.addBottles(Wine(name = "A late"), purchase = PurchaseInfo(isOrdered = true, expectedAt = reference(2_000)))
        cellar.addBottles(Wine(name = "B early"), purchase = PurchaseInfo(isOrdered = true, expectedAt = reference(1_000)))
        cellar.addBottles(Wine(name = "C unknown"), purchase = PurchaseInfo(isOrdered = true))
        assertEquals(listOf("B early", "A late", "C unknown"), cellar.orderGroups().map { it.wine.name })
    }

    @Test
    fun ordersSurviveCsvAndJson() {
        val cellar = Cellar()
        val expected = reference(800_000_000)
        cellar.addBottles(Wine(name = "Arriving"), purchase = PurchaseInfo(isOrdered = true, expectedAt = expected))
        val csv = CellarExport.csv(cellar, includeHistory = false)
        assertTrue(csv.contains("ordered"))
        val imported = Cellar()
        imported.importCsv(csv)
        val bottle = imported.bottlesOnOrder.first()
        assertEquals(expected.atZone(ZoneOffset.UTC).toLocalDate(), bottle.expectedAt?.atZone(ZoneOffset.UTC)?.toLocalDate())
        val decoded = CellarJson.decode(CellarJson.encode(cellar))
        assertEquals(expected, decoded.bottlesOnOrder.first().expectedAt)
    }
}

class WineTagTest {
    @Test
    fun extraPhotosAreKeptOnce() {
        val wine = Wine(name = "W", extraPhotoIds = listOf("sha256-a", "sha256-b", "sha256-a"))
        assertEquals(listOf("sha256-a", "sha256-b"), wine.normalized().extraPhotoIds)
        assertNull(Wine(name = "W", extraPhotoIds = emptyList()).normalized().extraPhotoIds)
        val cellar = Cellar(listOf(wine))
        assertEquals(listOf("sha256-a", "sha256-b"), cellar.wines[wine.id]?.extraPhotoIds)
    }

    @Test
    fun tagsFilterSearchAndStayOutOfOldFiles() {
        val cellar = Cellar()
        val gift = Wine(name = "Gift wine", tags = listOf(" Gift ", "gift", "Birth year"))
        assertEquals(listOf("Gift", "Birth year"), gift.normalized().tags)
        assertNull(Wine(name = "Plain", tags = listOf("  ")).normalized().tags)
        cellar.addBottles(gift)
        cellar.addBottles(Wine(name = "Plain"))
        assertEquals(listOf("Gift", "Birth year"), cellar.wines[gift.id]?.tags, "stored normalized")
        assertEquals(listOf("Gift wine"), cellar.groups(CellarFilter(tag = "gift"), CellarSort.Name, 2026).map { it.wine.name })
        assertEquals(listOf("Gift wine"), cellar.groups("birth").map { it.wine.name })
        assertEquals(listOf("Birth year", "Gift"), cellar.wineTags)
        val copy = Cellar()
        copy.importCsv(CellarExport.csv(cellar))
        assertEquals(listOf("Gift", "Birth year"), copy.wines.values.first { it.name == "Gift wine" }.tags)
        assertFalse(CellarJson.encode(Cellar(listOf(Wine(name = "Plain")))).contains("tags"))
    }
}

class DrinkDownPlanTest {
    @Test
    fun paceKeepsUpWithTheEarliestWindows() {
        val cellar = Cellar()
        // 6 bottles end this year, 12 next year, 2 have no window, 1 is past.
        cellar.addBottles(Wine(name = "Now", drinkFrom = 2020, drinkUntil = 2026), 6)
        cellar.addBottles(Wine(name = "Next", drinkFrom = 2020, drinkUntil = 2027), 12)
        cellar.addBottles(Wine(name = "Unknown"), 2)
        cellar.addBottles(Wine(name = "Past", drinkFrom = 2010, drinkUntil = 2020))
        cellar.addBottles(Wine(name = "Ordered", drinkUntil = 2026), purchase = PurchaseInfo(isOrdered = true))
        val plan = cellar.drinkDownPlan(2026, 10)
        assertEquals(listOf(6, 12), plan.years.map { it.leaving })
        assertEquals(1, plan.pastPeak)
        assertEquals(2, plan.withoutWindow)
        // This year: (1 + 6) / 3 months. By next year: 19 / 15 months. The larger wins.
        assertEquals(7.0 / 3.0, plan.bottlesPerMonth, 0.0001)
        assertFalse(plan.isEmpty)
        assertTrue(Cellar().drinkDownPlan(2026, 1).isEmpty)
        assertEquals(0.0, Cellar().drinkDownPlan(2026, 1).bottlesPerMonth)
    }
}

class WindowSourceTest {
    @Test
    fun explainsEstimatedAndEnteredWindows() {
        var wine = Wine(name = "Red", vintage = 2020, color = WineColor.Red)
        assertEquals(WindowSource.Missing, wine.windowSource)
        val estimate = wine.estimatedWindow()!!
        wine = wine.copy(drinkFrom = estimate.first, drinkUntil = estimate.last)
        assertEquals(WindowSource.Estimated(3, 12), wine.windowSource)
        val later = wine.movingWindowEnd(2)
        assertEquals(estimate.last + 2, later.drinkUntil)
        assertEquals(WindowSource.Entered, later.windowSource)
        assertEquals(estimate.first, wine.movingWindowEnd(-100).drinkUntil, "never ends before it starts")
    }
}

class YearInWineTest {
    @Test
    fun summarizesTheBottlesOpenedThatYear() {
        fun date(year: Int, month: Int) = LocalDate.of(year, month, 10).atStartOfDay(ZoneOffset.UTC).toInstant()
        val cellar = Cellar()
        val burgundy = Wine(name = "Volnay", vintage = 2018, color = WineColor.Red, region = "Burgundy")
        val loire = Wine(name = "Vouvray", vintage = 2020, color = WineColor.White, region = "Loire")
        val bottles = cellar.addBottles(burgundy, 3) + cellar.addBottles(loire, 1)
        cellar.drink(bottles[0].id, Tasting(date = date(2026, 3), rating = 4, occasion = "Dinner with friends"))
        cellar.drink(bottles[1].id, Tasting(date = date(2026, 3), rating = 5, occasion = "Dinner with friends"))
        cellar.drink(bottles[3].id, Tasting(date = date(2026, 6), rating = 3, occasion = "Aperitif"))
        cellar.drink(bottles[2].id, Tasting(date = date(2025, 12), rating = 5))
        val year = cellar.yearInWine(2026, ZoneOffset.UTC)
        assertEquals(3, year.bottlesOpened)
        assertEquals(2, year.differentWines)
        assertEquals("Volnay 2018" to 5, year.favorite)
        assertEquals("Burgundy", year.topRegion)
        assertEquals(WineColor.Red, year.topColor)
        assertEquals("Dinner with friends", year.topOccasion)
        assertEquals(3, year.busiestMonth)
        assertTrue(cellar.yearInWine(2024, ZoneOffset.UTC).isEmpty)
    }
}

class DrinkingDigestTest {
    @Test
    fun countsBottlesByWhatToDoThisYear() {
        val cellar = Cellar()
        cellar.addBottles(Wine(name = "Soon", vintage = 2015, drinkFrom = 2020, drinkUntil = 2026), 2)
        cellar.addBottles(Wine(name = "Opening", vintage = 2020, drinkFrom = 2026, drinkUntil = 2032), 1)
        cellar.addBottles(Wine(name = "Old", vintage = 2005, drinkFrom = 2008, drinkUntil = 2015), 3)
        cellar.addBottles(Wine(name = "Young", vintage = 2024, drinkFrom = 2030, drinkUntil = 2040), 1)
        val digest = cellar.drinkingDigest(2026)
        assertEquals(2, digest.drinkSoon)
        assertEquals(1, digest.newlyReady)
        assertEquals(3, digest.pastPeak)
        assertEquals("Soon 2015", digest.firstWine)
        assertTrue(Cellar().drinkingDigest(2026).isEmpty)
    }
}

class BarcodeTest {
    @Test
    fun aScannedBarcodeFindsItsWine() {
        val cellar = Cellar()
        var wine = Wine(name = "Chablis", vintage = 2022)
        val first = wine.addingBarcode("3 760123 456789")
        assertTrue(first != wine)
        wine = first
        assertEquals(wine, wine.addingBarcode("3760123456789"), "the same code with spaces is not added twice")
        assertEquals(wine, wine.addingBarcode("12"), "too short to be a barcode")
        cellar.addBottles(wine, 1)
        assertEquals(wine.id, cellar.wine("3760123456789")?.id)
        assertNull(cellar.wine("0000000000000"))
        assertEquals(1, cellar.groups("3760123456789").size)
    }

    @Test
    fun wineFilesWithoutBarcodesAreUnchanged() {
        assertFalse(CellarJson.encode(Cellar(listOf(Wine(name = "Plain")))).contains("barcodes"))
        val decoded = CellarJson.json.decodeFromString(Wine.serializer(), """{"id":"3D759362-EEC4-4657-8014-4757C8FB5FF3","name":"X","barcodes":["123456"]}""")
        assertEquals(listOf("123456"), decoded.barcodes)
    }
}

class BulkActionTest {
    @Test
    fun placingSeveralWinesFillsFreePlacesInOrder() {
        val cellar = Cellar()
        val rack = Rack(name = "Small", rows = 1, columns = 3)
        cellar.addRack(rack)
        val a = Wine(name = "A")
        val b = Wine(name = "B")
        cellar.addBottles(a, 2, listOf(Slot(rack.id, 0, 0)))
        cellar.addBottles(b, 2)
        val placed = cellar.place(setOf(a.id, b.id), rack.id)
        assertEquals(2, placed, "two free places for three waiting bottles")
        assertEquals(1, cellar.bottlesInCellar.count { it.slot == null })
        assertEquals(2, cellar.unplace(setOf(a.id)))
        assertTrue(cellar.bottlesOf(a.id).all { it.slot == null })
    }
}

class OtherAppsImportTest {
    @Test
    fun importsACellarTrackerExport() {
        val csv = """
            iWine,Type,Color,Quantity,Vintage,Wine,Producer,Varietal,Country,Region,Appellation,Location,Bin,Price,Currency,BeginConsume,EndConsume
            123,Red,Red,2,2016,Château Test Pauillac,Château Test,Cabernet Sauvignon,France,Bordeaux,Pauillac,Cave,A3,45.00,EUR,2024,2036
            124,White - Sparkling,White,1,1001,Brut Réserve,Maison X,Chardonnay,France,Champagne,Champagne,,,30,EUR,,
        """.trimIndent()
        val cellar = Cellar()
        val summary = cellar.importCsv(csv)
        assertEquals(2, summary.winesAdded)
        assertEquals(3, summary.bottlesAdded)
        val pauillac = cellar.wines.values.first { it.name == "Château Test Pauillac" }
        assertTrue(pauillac.vintage == 2016 && pauillac.color == WineColor.Red && pauillac.appellation == "Pauillac")
        assertTrue(pauillac.drinkFrom == 2024 && pauillac.drinkUntil == 2036)
        assertEquals(listOf("Cabernet Sauvignon"), pauillac.grapes)
        val brut = cellar.wines.values.first { it.name == "Brut Réserve" }
        assertNull(brut.vintage, "1001 means non-vintage")
        assertEquals(WineColor.Sparkling, brut.color)
    }

    @Test
    fun aVivinoHistoryBecomesDiscoveries() {
        val csv = """
            Winery,Wine name,Vintage,Region,Country,Regional wine style,Average rating,Scan date,Scan/Review Location,Your rating,Your review,Wine type,Drinking Window,Wine price
            Domaine Y,Morgon Côte du Py,2020,Beaujolais,France,,3.9,2026-05-02 20:15:00,Lyon,4.0,Lovely with charcuterie,Red wine,2022 - 2030,24.5
        """.trimIndent()
        val cellar = Cellar()
        val summary = cellar.importCsv(csv)
        assertEquals(0, summary.bottlesAdded)
        val discovery = cellar.discoveries.values.first()
        val wine = cellar.wines.getValue(discovery.wineId)
        assertTrue(wine.name == "Morgon Côte du Py" && wine.producer == "Domaine Y" && wine.color == WineColor.Red)
        assertTrue(wine.drinkFrom == 2022 && wine.drinkUntil == 2030)
        assertTrue(discovery.rating == 4 && discovery.place == "Lyon" && discovery.note == "Lovely with charcuterie")
        assertEquals(0, BigDecimal("24.5").compareTo(discovery.price))
    }

    @Test
    fun frenchHeadersAreRecognized() {
        val cellar = Cellar()
        cellar.importCsv("Nom;Millésime;Couleur;Quantité\nMorgon;2020;Rouge;3")
        assertEquals(3, cellar.bottles.size)
        assertEquals(WineColor.Red, cellar.wines.values.first().color)
    }
}

class ReservationTest {
    @Test
    fun reservedBottlesAreKeptForTheirOccasion() {
        val cellar = Cellar()
        val wine = Wine(name = "Party", vintage = 2018, drinkFrom = 2020, drinkUntil = 2030)
        val bottles = cellar.addBottles(wine, quantity = 3)
        cellar.reserve(listOf(bottles[0].id, bottles[1].id), "  Paul's 40th ")
        assertEquals(listOf("Paul's 40th"), cellar.reservations)
        // Tonight offers only the free bottle.
        assertEquals(listOf(1), cellar.groupsByMaturity(2026).flatMap { it.groups }.map { it.count })
        assertEquals(listOf(2), cellar.groups(CellarFilter(reservation = "paul's 40th"), CellarSort.Name, 2026).map { it.count })
        cellar.reserve(listOf(bottles[0].id), " ")
        assertNull(cellar.bottles[bottles[0].id]?.reservedFor)
        // All reserved: the wine is not suggested at all.
        cellar.reserve(bottles.map { it.id }, "Wedding")
        assertTrue(cellar.groupsByMaturity(2026).isEmpty())
        val copy = Cellar()
        copy.importCsv(CellarExport.csv(cellar))
        assertEquals(listOf("Wedding"), copy.reservations)
    }

    @Test
    fun onlyBottlesInTheCellarCanBeReserved() {
        val cellar = Cellar()
        val bottle = cellar.addBottles(Wine(name = "W"), purchase = PurchaseInfo(isOrdered = true)).single()
        assertThrows(CellarException::class.java) { cellar.reserve(listOf(bottle.id), "Party") }
        assertNull(cellar.bottles[bottle.id]?.reservedFor)
    }

    @Test
    fun reservationRoundTripsAndIsLeftOutWhenNil() {
        val cellar = Cellar()
        val bottles = cellar.addBottles(Wine(name = "W"), quantity = 2)
        cellar.reserve(listOf(bottles[0].id), "Party")
        val json = CellarJson.encode(cellar)
        assertEquals(1, Regex("\"reservedFor\"").findAll(json).count())
        assertEquals("Party", CellarJson.decode(json).bottles[bottles[0].id]?.reservedFor)
    }
}

class SavedSearchTest {
    @Test
    fun aSearchIsSavedRestoredAndReplacedByName() {
        val cellar = Cellar()
        val filter = CellarFilter(query = " Bourgogne ", colors = setOf(WineColor.White, WineColor.Red), maturities = setOf(Maturity.Ready), tag = "Gift")
        cellar.saveSearch(" Ready burgundy ", filter)
        val saved = cellar.settings.savedSearches!!.first()
        assertEquals("Ready burgundy", saved.name)
        assertEquals(listOf(WineColor.Red, WineColor.White), saved.colors)
        assertEquals(CellarFilter(query = "Bourgogne", colors = setOf(WineColor.White, WineColor.Red), maturities = setOf(Maturity.Ready), tag = "Gift"), saved.filter)
        cellar.saveSearch("ready BURGUNDY", CellarFilter(query = "Beaune"))
        assertEquals(1, cellar.settings.savedSearches?.size)
        assertEquals("Beaune", cellar.settings.savedSearches?.first()?.query)
        cellar.deleteSearch(cellar.settings.savedSearches!!.first().id)
        assertNull(cellar.settings.savedSearches)
        // Older files without saved searches still read.
        val old = CellarJson.json.decodeFromString(CellarSettings.serializer(), """{"currency":"CHF"}""")
        assertTrue(old.savedSearches == null && old.currency == "CHF")
    }

    @Test
    fun savedSearchesRoundTripInTheSwiftShape() {
        val cellar = Cellar()
        cellar.saveSearch("Ready", CellarFilter(maturities = setOf(Maturity.Ready, Maturity.DrinkSoon)))
        val json = CellarJson.encode(cellar)
        // Swift needs every non-optional field, empty arrays included, and sorted values.
        assertTrue(json.contains("\"colors\" : [\n\n") || Regex("\"colors\"\\s*:\\s*\\[\\s*]").containsMatchIn(json), json)
        assertTrue(Regex("\"maturities\"\\s*:\\s*\\[\\s*\"drinkSoon\",\\s*\"ready\"\\s*]").containsMatchIn(json), json)
        assertTrue(Regex("\"query\"\\s*:\\s*\"\"").containsMatchIn(json), json)
        assertEquals(cellar.settings, CellarJson.decode(json).settings)
    }
}
