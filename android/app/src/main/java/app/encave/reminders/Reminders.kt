package app.encave.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.encave.EncaveApplication
import app.encave.MainActivity
import app.encave.R
import app.encave.core.Cellar
import app.encave.core.DrinkingDigest
import app.encave.core.drinkingDigest
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * A monthly reminder, from the first of the month, of the bottles to drink this year,
 * those whose window opens and those past their peak, and one on the day bottles on
 * order are expected. Worked out on the device by a daily check: no push server,
 * nothing leaves it.
 */
object Reminders {
    const val CHANNEL_MONTHLY = "monthly"
    const val CHANNEL_DELIVERIES = "deliveries"
    private const val WORK = "encave.reminders"
    private const val PREFS = "reminders"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_MONTH = "last_month"
    private const val KEY_DELIVERED = "delivered"
    private const val MONTHLY_ID = 1
    /** At most this many delivery reminders a day. */
    private const val MAX_DELIVERIES = 20

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    /** Notifications are allowed: the permission is granted and they are on for the app. */
    fun canNotify(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** The first monthly reminder comes on the first of next month. */
    fun setEnabled(context: Context, enabled: Boolean, today: LocalDate = LocalDate.now()) {
        prefs(context).edit {
            putBoolean(KEY_ENABLED, enabled)
            if (enabled) putString(KEY_LAST_MONTH, YearMonth.from(today).toString())
        }
        if (enabled) schedule(context) else cancel(context)
    }

    /** A daily check, around 10:00. Kept if already scheduled. */
    fun schedule(context: Context) {
        runCatching {
            createChannels(context)
            val now = LocalDateTime.now()
            var next = now.toLocalDate().atTime(LocalTime.of(10, 0))
            if (!next.isAfter(now)) next = next.plusDays(1)
            val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }

    private fun cancel(context: Context) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK) }
    }

    /** One channel per kind, so each can be turned off in the system settings. */
    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MONTHLY, context.getString(R.string.reminders_channel_monthly), NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DELIVERIES, context.getString(R.string.reminders_channel_deliveries), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** What is due today: the monthly digest once a month, and deliveries expected today. */
    data class Due(val digest: DrinkingDigest?, val deliveries: List<Delivery>)

    data class Delivery(val wineId: String, val name: String, val count: Int)

    /** Pure decision, for tests: [lastMonth] is the month of the last digest, [delivered] the keys already notified. */
    fun due(cellar: Cellar, today: LocalDate, zone: ZoneId, lastMonth: String?, delivered: Set<String>): Due {
        val month = YearMonth.from(today).toString()
        val digest = if (lastMonth != month) cellar.drinkingDigest(today.year).takeIf { !it.isEmpty } else null
        // Per wine and expected day: only the bottles of the wine expected today are counted.
        val deliveries = cellar.orderGroups().mapNotNull { group ->
            val count = group.bottles.count { it.expectedAt?.atZone(zone)?.toLocalDate() == today }
            if (count == 0) return@mapNotNull null
            if (deliveryKey(group.wine.id.toString(), today) in delivered) return@mapNotNull null
            Delivery(group.wine.id.toString(), group.wine.displayName, count)
        }.take(MAX_DELIVERIES)
        return Due(digest, deliveries)
    }

    private fun deliveryKey(wineId: String, day: LocalDate) = "$wineId@$day"

    fun body(resources: Resources, digest: DrinkingDigest): String {
        val parts = buildList {
            if (digest.drinkSoon > 0) add(resources.getString(R.string.reminders_drink_soon, digest.drinkSoon))
            if (digest.newlyReady > 0) add(resources.getString(R.string.reminders_now_ready, digest.newlyReady))
            if (digest.pastPeak > 0) add(resources.getString(R.string.insights_past_peak, digest.pastPeak))
        }
        var text = parts.joinToString(" · ")
        digest.firstWine?.let { text += "\n" + resources.getString(R.string.reminders_start_with, it) }
        return text
    }

    /** Runs the daily check: posts what is due and remembers it. */
    fun check(context: Context, cellar: Cellar, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()) {
        if (!isEnabled(context)) return
        val prefs = prefs(context)
        val delivered = prefs.getStringSet(KEY_DELIVERED, emptySet()).orEmpty()
        val due = due(cellar, today, zone, prefs.getString(KEY_LAST_MONTH, null), delivered)
        if (!canNotify(context)) return
        createChannels(context)
        val manager = NotificationManagerCompat.from(context)
        val resources = context.resources
        due.digest?.let { digest ->
            val text = body(resources, digest)
            val notification = NotificationCompat.Builder(context, CHANNEL_MONTHLY)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(resources.getString(R.string.reminders_monthly_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open(context, MainActivity.LINK_TONIGHT, MONTHLY_ID))
                .setAutoCancel(true)
                .build()
            post(manager, MONTHLY_ID, notification)
        }
        due.deliveries.forEach { delivery ->
            val text = resources.getQuantityString(R.plurals.reminders_delivery_body, delivery.count, delivery.name, delivery.count)
            val id = delivery.wineId.hashCode()
            val notification = NotificationCompat.Builder(context, CHANNEL_DELIVERIES)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(resources.getString(R.string.reminders_delivery_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open(context, MainActivity.wineLink(delivery.wineId), id))
                .setAutoCancel(true)
                .build()
            post(manager, id, notification)
        }
        prefs.edit {
            putString(KEY_LAST_MONTH, YearMonth.from(today).toString())
            // Only today's keys matter from now on.
            putStringSet(KEY_DELIVERED, delivered.filter { it.endsWith("@$today") }.toSet() + due.deliveries.map { deliveryKey(it.wineId, today) })
        }
    }

    /** The permission is checked before; it can still be revoked in between. */
    private fun post(manager: NotificationManagerCompat, id: Int, notification: android.app.Notification) {
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun open(context: Context, link: String, requestCode: Int): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

/** The daily reminder check, run by WorkManager while reminders are on. */
class ReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as EncaveApplication
        app.repository.loaded.first { it }
        if (!app.repository.loadFailed) Reminders.check(app, app.repository.cellar.value)
        return Result.success()
    }
}
