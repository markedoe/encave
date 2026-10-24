package app.encave.reminders

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.encave.core.Cellar
import app.encave.core.PurchaseInfo
import app.encave.core.Wine
import app.encave.core.WineColor
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemindersTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 1)

    private val cellar = Cellar().apply {
        addBottles(Wine(name = "Soon", vintage = 2018, color = WineColor.Red, drinkFrom = 2020, drinkUntil = 2026), quantity = 2)
        addBottles(
            Wine(name = "Primeur", vintage = 2025, color = WineColor.Red), quantity = 6,
            purchase = PurchaseInfo(isOrdered = true, expectedAt = today.atTime(9, 0).toInstant(ZoneOffset.UTC)),
        )
        addBottles(
            Wine(name = "Later", vintage = 2025, color = WineColor.White), quantity = 1,
            purchase = PurchaseInfo(isOrdered = true, expectedAt = today.plusDays(3).atTime(9, 0).toInstant(ZoneOffset.UTC)),
        )
    }

    @Test
    fun theFirstCheckWaitsTheRealTimeAcrossAClockChange() {
        val paris = ZoneId.of("Europe/Paris")
        // 29 March 2026: the clocks go from 02:00 to 03:00 in Paris.
        val now = LocalDate.of(2026, 3, 29).atTime(1, 30).atZone(paris)
        assertEquals(Duration.ofMinutes(390), Reminders.initialDelay(now, LocalTime.of(9, 0)))
        // 25 October 2026: from 03:00 back to 02:00.
        val autumn = LocalDate.of(2026, 10, 25).atTime(1, 30).atZone(paris)
        assertEquals(Duration.ofMinutes(510), Reminders.initialDelay(autumn, LocalTime.of(9, 0)))
        // Past the time: tomorrow.
        assertEquals(Duration.ofHours(23), Reminders.initialDelay(LocalDate.of(2026, 6, 1).atTime(11, 0).atZone(paris)))
    }

    @Test
    fun monthlyDigestOncePerMonth() {
        val due = Reminders.due(cellar, today, zone, lastMonth = "2026-10", delivered = emptySet())
        assertEquals(2, due.digest?.drinkSoon)
        assertEquals("Soon 2018", due.digest?.firstWine)
        assertNull(Reminders.due(cellar, today, zone, lastMonth = "2026-11", delivered = emptySet()).digest)
    }

    @Test
    fun deliveriesOnTheExpectedDayOnly() {
        val due = Reminders.due(cellar, today, zone, lastMonth = "2026-11", delivered = emptySet())
        assertEquals(listOf("Primeur 2025" to 6), due.deliveries.map { it.name to it.count })
        val key = "${due.deliveries.single().wineId}@$today"
        assertTrue(Reminders.due(cellar, today, zone, "2026-11", setOf(key)).deliveries.isEmpty())
        assertTrue(Reminders.due(cellar, today.plusDays(1), zone, "2026-11", emptySet()).deliveries.isEmpty())
    }

    @Test
    fun deliveriesOfOneWineOnSeveralDaysAreCountedPerDay() {
        val wine = Wine(name = "Split", vintage = 2024, color = WineColor.Red)
        val split = Cellar().apply {
            addBottles(wine, quantity = 2, purchase = PurchaseInfo(isOrdered = true, expectedAt = today.atTime(9, 0).toInstant(ZoneOffset.UTC)))
            addBottles(wine, quantity = 4, purchase = PurchaseInfo(isOrdered = true, expectedAt = today.plusDays(10).atTime(9, 0).toInstant(ZoneOffset.UTC)))
        }
        assertEquals(listOf(2), Reminders.due(split, today, zone, "2026-11", emptySet()).deliveries.map { it.count })
        assertEquals(listOf(4), Reminders.due(split, today.plusDays(10), zone, "2026-11", emptySet()).deliveries.map { it.count })
    }

    @Test
    fun checkPostsOnePerKindAndRemembers() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        Reminders.setEnabled(context, true, today.minusMonths(1))
        Reminders.check(context, cellar, today, zone)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = shadowOf(manager).allNotifications
        assertEquals(2, posted.size)
        assertNotNull(manager.getNotificationChannel(Reminders.CHANNEL_MONTHLY))
        assertNotNull(manager.getNotificationChannel(Reminders.CHANNEL_DELIVERIES))
        assertEquals(setOf(Reminders.CHANNEL_MONTHLY, Reminders.CHANNEL_DELIVERIES), posted.map { it.channelId }.toSet())
        // A second check the same day posts nothing new.
        manager.cancelAll()
        Reminders.check(context, cellar, today, zone)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}
