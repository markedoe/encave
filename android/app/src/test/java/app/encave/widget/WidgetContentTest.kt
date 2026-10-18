package app.encave.widget

import android.content.Context
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescription
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import app.encave.core.Cellar
import app.encave.core.PurchaseInfo
import app.encave.core.Rack
import app.encave.core.Slot
import app.encave.core.Wine
import app.encave.core.WidgetSnapshot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The widgets' content in English, from a small cellar. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class WidgetContentTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val year = 2026

    private val snapshot: WidgetSnapshot by lazy {
        val cellar = Cellar()
        val rack = Rack(name = "Main", rows = 2, columns = 2)
        cellar.addRack(rack)
        cellar.addBottles(Wine(name = "Clos Test", vintage = 2018, drinkFrom = 2020, drinkUntil = year), 2, listOf(Slot(rack.id, 0, 0)))
        cellar.addBottles(Wine(name = "Later", drinkFrom = 2020, drinkUntil = 2032))
        cellar.addBottles(Wine(name = "Primeur", drinkFrom = 2030, drinkUntil = 2040), 3, purchase = PurchaseInfo(isOrdered = true))
        WidgetSnapshot.of(cellar, year)
    }

    @Test
    fun tonightListsTheBottlesWithMaturityInWords() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(TonightWidget.LARGE)
        provideComposable { EncaveGlanceTheme(dynamicColor = false) { TonightContent(snapshot) } }
        onNode(hasText("Tonight")).assertExists()
        onNode(hasText("Clos Test 2018")).assertExists()
        onNode(hasText("Drink soon")).assertExists()
        onNode(hasText("Later")).assertExists()
        onNode(hasContentDescription("Clos Test 2018, Drink soon, Main · A1, 2 bottles")).assertExists()
    }

    @Test
    fun smallTonightShowsTheFirstBottle() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(TonightWidget.SMALL)
        provideComposable { EncaveGlanceTheme(dynamicColor = false) { TonightContent(snapshot) } }
        onNode(hasText("Clos Test 2018")).assertExists()
        onNode(hasText("Later")).assertDoesNotExist()
    }

    @Test
    fun cellarCounts() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(TonightWidget.SMALL)
        provideComposable { EncaveGlanceTheme(dynamicColor = false) { CellarContent(snapshot) } }
        onNode(hasText("3 bottles")).assertExists()
        onNode(hasText("Drink soon: 2")).assertExists()
        onNode(hasText("Ready: 1")).assertExists()
        onNode(hasText("On order: 3")).assertExists()
    }

    @Test
    @Config(qualifiers = "fr")
    fun cellarCountsInFrench() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(TonightWidget.SMALL)
        provideComposable { EncaveGlanceTheme(dynamicColor = false) { CellarContent(snapshot) } }
        onNode(hasText("Cave")).assertExists()
        onNode(hasText("3 bouteilles")).assertExists()
        onNode(hasText("À boire bientôt : 2")).assertExists()
        onNode(hasText("En commande : 3")).assertExists()
    }

    @Test
    fun emptyCellarInvitesToAddBottles() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(TonightWidget.SMALL)
        provideComposable { EncaveGlanceTheme(dynamicColor = false) { CellarContent(WidgetSnapshot.of(Cellar(), year)) } }
        onNode(hasText("Add bottles in Encave")).assertExists()
    }
}
