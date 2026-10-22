package app.encave.reminders

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import app.encave.core.Cellar
import app.encave.core.DrinkingDigest
import app.encave.core.PurchaseInfo
import app.encave.core.Wine
import app.encave.core.WineColor
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** When reminders are due, across time zones, months and years, and what they say in English and French. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderScheduleTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val paris: ZoneId = ZoneId.of("Europe/Paris")
    private val today = LocalDate.of(2026, 11, 1)
    private lateinit var context: Application
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancelAll()
    }

    private fun ordered(cellar: Cellar, name: String, quantity: Int, expected: java.time.Instant) {
        cellar.addBottles(
            Wine(name = name, vintage = 2025, color = WineColor.Red), quantity = quantity,
            purchase = PurchaseInfo(isOrdered = true, expectedAt = expected),
        )
    }

    @Test
    fun theExpectedDayIsTheDayWhereTheDeviceIs() {
        // 23:30 UTC on 31 October is already 1 November in Paris.
        val cellar = Cellar()
        ordered(cellar, "Late", 1, LocalDate.of(2026, 10, 31).atTime(23, 30).toInstant(ZoneOffset.UTC))
        assertEquals(listOf("Late 2025"), Reminders.due(cellar, today, paris, "2026-11", emptySet()).deliveries.map { it.name })
        assertTrue(Reminders.due(cellar, today, utc, "2026-11", emptySet()).deliveries.isEmpty())
        assertEquals(listOf("Late 2025"), Reminders.due(cellar, today.minusDays(1), utc, "2026-11", emptySet()).deliveries.map { it.name })
    }

    @Test
    fun aNewMonthWithNothingToSaySendsNoDigest() {
        val cellar = Cellar().apply {
            addBottles(Wine(name = "Ready", vintage = 2019, drinkFrom = 2022, drinkUntil = 2030))
            addBottles(Wine(name = "Young", vintage = 2024, drinkFrom = 2029, drinkUntil = 2040))
            addBottles(Wine(name = "No window", vintage = 2020))
        }
        assertNull(Reminders.due(cellar, today, utc, "2026-10", emptySet()).digest)
        assertNull(Reminders.due(cellar, today, utc, null, emptySet()).digest)
    }

    @Test
    fun januaryCountsTheNewYear() {
        // Its window ends in 2026: last year to drink it in December, past its peak in January.
        val cellar = Cellar().apply { addBottles(Wine(name = "Soon", vintage = 2018, drinkFrom = 2020, drinkUntil = 2026), quantity = 3) }
        val december = Reminders.due(cellar, LocalDate.of(2026, 12, 1), utc, "2026-11", emptySet()).digest
        assertEquals(DrinkingDigest(drinkSoon = 3, firstWine = "Soon 2018"), december)
        val january = Reminders.due(cellar, LocalDate.of(2027, 1, 1), utc, "2026-12", emptySet()).digest
        assertEquals(DrinkingDigest(pastPeak = 3, firstWine = "Soon 2018"), january)
        // The same month of another year is a new month.
        assertEquals(3, Reminders.due(cellar, LocalDate.of(2027, 12, 1), utc, "2026-12", emptySet()).digest?.pastPeak)
    }

    @Test
    fun atMostTwentyDeliveriesADaySoonestFirst() {
        val cellar = Cellar()
        val morning = today.atTime(8, 0).toInstant(ZoneOffset.UTC)
        for (index in 1..25) ordered(cellar, "Wine %02d".format(index), 1, morning.plusSeconds(index * 60L))
        val deliveries = Reminders.due(cellar, today, utc, "2026-11", emptySet()).deliveries
        assertEquals(20, deliveries.size)
        assertEquals((1..20).map { "Wine %02d 2025".format(it) }, deliveries.map { it.name })
    }

    @Test
    fun turnedOffOrNotAllowedPostsNothingAndRemembersNothing() {
        val cellar = Cellar()
        ordered(cellar, "Primeur", 2, today.atTime(9, 0).toInstant(ZoneOffset.UTC))
        cellar.addBottles(Wine(name = "Soon", vintage = 2018, drinkFrom = 2020, drinkUntil = 2026))

        Reminders.setEnabled(context, false, today.minusMonths(1))
        Reminders.check(context, cellar, today, utc)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())

        // On, but notifications are not allowed: nothing is posted, nor marked as sent.
        Reminders.setEnabled(context, true, today.minusMonths(1))
        shadowOf(context).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        Reminders.check(context, cellar, today, utc)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())

        // Allowed later the same day: both come then.
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        Reminders.check(context, cellar, today, utc)
        assertEquals(2, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun theNextDayOnlyNewDeliveriesArePosted() {
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val cellar = Cellar()
        ordered(cellar, "Monday", 1, today.atTime(9, 0).toInstant(ZoneOffset.UTC))
        ordered(cellar, "Tuesday", 1, today.plusDays(1).atTime(9, 0).toInstant(ZoneOffset.UTC))
        Reminders.setEnabled(context, true, today)
        Reminders.check(context, cellar, today, utc)
        assertEquals(listOf("Monday 2025"), texts().map { it.substringBefore(":") })
        manager.cancelAll()
        Reminders.check(context, cellar, today.plusDays(1), utc)
        assertEquals(listOf("Tuesday 2025"), texts().map { it.substringBefore(":") })
        manager.cancelAll()
        Reminders.check(context, cellar, today.plusDays(1), utc)
        assertTrue(texts().isEmpty())
    }

    private fun texts(): List<String> =
        shadowOf(manager).allNotifications.map { it.extras.getCharSequence(Notification.EXTRA_TEXT).toString() }

    private fun resources(language: String) = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) },
    ).resources

    @Test
    fun theDigestReadsInEnglishAndFrench() {
        val digest = DrinkingDigest(drinkSoon = 2, newlyReady = 1, pastPeak = 3, firstWine = "Château Palmer 2015")
        assertEquals(
            "To drink this year: 2 · Now ready: 1 · Past their peak: 3\nStart with Château Palmer 2015.",
            Reminders.body(resources("en"), digest),
        )
        assertEquals(
            "À boire cette année : 2 · Désormais prêtes : 1 · Après leur apogée : 3\nCommencez par Château Palmer 2015.",
            Reminders.body(resources("fr"), digest),
        )
        // Only the counts that are not zero; no wine to name, no last line.
        assertEquals("Past their peak: 1", Reminders.body(resources("en"), DrinkingDigest(pastPeak = 1)))
    }

    @Test
    fun theDeliveryReadsInEnglishAndFrench() {
        val en = resources("en")
        val fr = resources("fr")
        val body = app.encave.R.plurals.reminders_delivery_body
        assertEquals("Rully 2020: 1 bottle. Mark it received when it arrives.", en.getQuantityString(body, 1, "Rully 2020", 1))
        assertEquals("Rully 2020: 6 bottles. Mark them received when they arrive.", en.getQuantityString(body, 6, "Rully 2020", 6))
        assertEquals("Rully 2020 : 1 bouteille. Marquez-la comme reçue à son arrivée.", fr.getQuantityString(body, 1, "Rully 2020", 1))
        assertEquals("Rully 2020 : 6 bouteilles. Marquez-les comme reçues à leur arrivée.", fr.getQuantityString(body, 6, "Rully 2020", 6))
    }
}
