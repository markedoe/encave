package app.encave.ui.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.encave.core.SampleData
import java.util.Locale
import org.junit.Assert.assertEquals
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
        assertEquals(
            "Main rack · A1, Main rack · A2, Main rack · A3, " +
                (1..4).joinToString(", ") { "Head-to-tail cabinet · Shelf\u00A01, front, bottom layer, position\u00A0$it" },
            tilleuls.places,
        )
        assertEquals("€266", tilleuls.value)
        assertEquals("Kitchen, Head-to-tail cabinet · Shelf\u00A01, back, top layer, position\u00A02", rows.single { it.wine.startsWith("Rosé") }.places)
    }

    @Test
    fun theFirstPageLeavesRoomForTheTitleAndTheFooter() {
        assertEquals(listOf(emptyList<Int>()), InventoryPdf.paged(emptyList<Int>()))
        val pages = InventoryPdf.paged((1..60).toList())
        assertEquals(listOf(InventoryPdf.ROWS_ON_FIRST_PAGE, InventoryPdf.ROWS_PER_PAGE, 60 - InventoryPdf.ROWS_ON_FIRST_PAGE - InventoryPdf.ROWS_PER_PAGE), pages.map { it.size })
        assertEquals(1, InventoryPdf.paged((1..InventoryPdf.ROWS_ON_FIRST_PAGE).toList()).size)
    }
}
