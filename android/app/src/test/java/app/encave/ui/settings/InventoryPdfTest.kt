package app.encave.ui.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.encave.core.SampleData
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InventoryPdfTest {
    private val resources = ApplicationProvider.getApplicationContext<Application>().resources
    private val cellar = SampleData.cellar(year = 2026)

    @Test
    fun oneRowPerWineInTheCellarWithPlacesAndValue() {
        val rows = InventoryPdf.rows(cellar, resources, Locale.US)
        // Bottles on order are not in the cellar yet.
        assertEquals(cellar.groups().size, rows.size)
        val tilleuls = rows.single { it.wine == "Clos des Tilleuls 2017" }
        // 3 in the main rack, 4 in front on the head-to-tail cabinet's first shelf.
        assertEquals(7, tilleuls.bottles)
        assertEquals("Domaine Arnaud", tilleuls.producer)
        assertEquals("Pauillac, France", tilleuls.region)
        // One line per storage, the storage named once.
        assertEquals(
            "Main rack: A1, A2, A3\n" +
                "Head-to-tail cabinet: " + (1..4).joinToString("; ") { "Shelf 1, front, bottom layer, position $it" },
            tilleuls.places,
        )
        assertEquals("€266", tilleuls.value)
        assertEquals("Head-to-tail cabinet: Shelf 1, back, top layer, position 2\nKitchen", rows.single { it.wine.startsWith("Rosé") }.places)
    }

    @Test
    fun theSamePlaceIsGivenOnceWithItsCount() {
        val bins = cellar.racks.first { it.kind == app.encave.core.RackKind.Bins }
        val inBins = cellar.bottlesInCellar.filter { it.slot?.rackId == bins.id }
        val cell = inBins.groupBy { it.slot!!.cell }.values.first { it.size > 1 }
        val places = InventoryPdf.places(cellar, cell, resources)
        assertEquals(1, places.size)
        assertTrue(places.single(), places.single().startsWith(bins.name + ": ") && places.single().endsWith(" ×${cell.size}"))
    }

    @Test
    fun aTallRowTakesMoreRoomOnThePage() {
        // Rows twice as tall: half as many on a page.
        val pages = InventoryPdf.paged((1..30).toList()) { 52f }
        assertEquals(listOf(InventoryPdf.ROWS_ON_FIRST_PAGE / 2, InventoryPdf.ROWS_PER_PAGE / 2, 30 - InventoryPdf.ROWS_ON_FIRST_PAGE / 2 - InventoryPdf.ROWS_PER_PAGE / 2), pages.map { it.size })
    }

    @Test
    fun theFirstPageLeavesRoomForTheTitleAndTheFooter() {
        assertEquals(listOf(emptyList<Int>()), InventoryPdf.paged(emptyList<Int>()))
        val pages = InventoryPdf.paged((1..60).toList())
        assertEquals(listOf(InventoryPdf.ROWS_ON_FIRST_PAGE, InventoryPdf.ROWS_PER_PAGE, 60 - InventoryPdf.ROWS_ON_FIRST_PAGE - InventoryPdf.ROWS_PER_PAGE), pages.map { it.size })
        assertEquals(1, InventoryPdf.paged((1..InventoryPdf.ROWS_ON_FIRST_PAGE).toList()).size)
    }
}
