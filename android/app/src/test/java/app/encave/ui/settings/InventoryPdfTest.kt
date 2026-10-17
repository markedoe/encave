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
@Config(sdk = [34])
class InventoryPdfTest {
    private val resources = ApplicationProvider.getApplicationContext<Application>().resources
    private val cellar = SampleData.cellar(year = 2026)

    @Test
    fun oneRowPerWineInTheCellarWithPlacesAndValue() {
        val rows = InventoryPdf.rows(cellar, resources, Locale.US)
        // Bottles on order are not in the cellar yet.
        assertEquals(cellar.groups().size, rows.size)
        val tilleuls = rows.single { it.wine == "Clos des Tilleuls 2017" }
        assertEquals(3, tilleuls.bottles)
        assertEquals("Domaine Arnaud", tilleuls.producer)
        assertEquals("Pauillac, France", tilleuls.region)
        assertEquals("Main rack · A1, Main rack · A2, Main rack · A3", tilleuls.places)
        assertEquals("€114", tilleuls.value)
        assertEquals("Kitchen", rows.single { it.wine.startsWith("Rosé") }.places)
    }
}
