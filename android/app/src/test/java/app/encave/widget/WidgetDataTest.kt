package app.encave.widget

import androidx.test.core.app.ApplicationProvider
import app.encave.EncaveApplication
import app.encave.core.Cellar
import app.encave.core.Wine
import app.encave.core.WidgetSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What a widget reads when the app is not running. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetDataTest {
    private val app get() = ApplicationProvider.getApplicationContext<EncaveApplication>()

    @Test
    fun aSummaryFromLastYearIsWorkedOutAgain() = runBlocking {
        val cellar = Cellar().apply { addBottles(Wine(name = "Ends this year", drinkFrom = 2020, drinkUntil = 2026)) }
        app.repository.replace(cellar)
        app.widgetSnapshots.write(WidgetSnapshot.of(cellar, 2025))

        val fresh = WidgetData.current(app, year = 2026)!!
        assertEquals(2026, fresh.year)
        assertEquals(1, fresh.drinkSoon)
        assertEquals(fresh, app.widgetSnapshots.snapshot.value)
        // This year's summary is read as it is.
        assertSame(app.widgetSnapshots.snapshot.value, WidgetData.current(app, year = 2026))
    }
}
