package app.encave.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import app.encave.MainActivity
import app.encave.R
import app.encave.core.sync.CellarChangeSummary
import app.encave.core.sync.Membership
import app.encave.reminders.Reminders
import java.util.UUID

/**
 * The optional notification of changes made on another device ("From Marc's iPhone: 2 bottles
 * opened."), posted when a sync brings some while the app is not in the foreground. A local
 * notification only: nothing leaves the device. One notification at most: a new one replaces
 * the previous one, with the changes of both, until it is dismissed or the app is opened.
 */
object ChangeNotifications {
    const val CHANNEL = "sync_changes"
    private const val TAG = "sync-changes"
    private const val ID = 1
    private const val PREFS = "sync_changes"

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.sync_changes_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** "From Marc's iPhone", or "From your other devices" when several or not known. */
    fun title(resources: Resources, summary: CellarChangeSummary, membership: Membership?): String =
        deviceName(summary, membership)?.let { resources.getString(R.string.sync_changes_from, it) }
            ?: resources.getString(R.string.sync_changes_from_others)

    /** The short message shown while the app is open: "Updated from Marc's iPhone". */
    fun updated(resources: Resources, summary: CellarChangeSummary, membership: Membership?): String =
        deviceName(summary, membership)?.let { resources.getString(R.string.sync_updated_from, it) }
            ?: resources.getString(R.string.sync_updated_from_others)

    /** The name of the one device that made the changes, if known. */
    fun deviceName(summary: CellarChangeSummary, membership: Membership?): String? =
        summary.singleDevice?.let { membership?.members?.get(it)?.name }?.takeIf { it.isNotBlank() }

    /** "2 bottles opened, 3 bottles added." */
    fun body(resources: Resources, summary: CellarChangeSummary): String {
        val parts = buildList {
            if (summary.opened > 0) add(resources.getQuantityString(R.plurals.sync_changes_opened, summary.opened, summary.opened))
            if (summary.added > 0) add(resources.getQuantityString(R.plurals.bottles_added, summary.added, summary.added))
            if (summary.removed > 0) add(resources.getQuantityString(R.plurals.sync_changes_removed, summary.removed, summary.removed))
            if (summary.winesAdded > 0) add(resources.getQuantityString(R.plurals.sync_changes_wines, summary.winesAdded, summary.winesAdded))
            if (summary.other > 0) add(resources.getQuantityString(R.plurals.sync_changes_other, summary.other, summary.other))
        }
        return parts.joinToString(", ") + "."
    }

    /** Where tapping it leads: the Journal if bottles were only opened, the Cellar otherwise. */
    fun link(summary: CellarChangeSummary): String = if (summary.onlyOpened) MainActivity.LINK_JOURNAL else MainActivity.LINK_CELLAR

    /** Posts (or updates) the notification with [summary] added to what it already told. */
    fun post(context: Context, summary: CellarChangeSummary, membership: Membership?) {
        if (summary.isEmpty || !Reminders.canNotify(context)) return
        // Dismissed meanwhile: start afresh.
        val total = (if (isShown(context)) pending(context) else CellarChangeSummary()) + summary
        savePending(context, total)
        createChannel(context)
        val resources = context.resources
        val text = body(resources, total)
        val intent = Intent(Intent.ACTION_VIEW, link(total).toUri(), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title(resources, total, membership))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(PendingIntent.getActivity(context, ID, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            // Updates do not ring again.
            .setOnlyAlertOnce(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(TAG, ID, notification)
        } catch (_: SecurityException) {
            // The permission was revoked in between.
        }
    }

    /** The app is open: the changes are seen, the next notification starts afresh. */
    fun clear(context: Context) {
        val prefs = prefs(context)
        if (prefs.all.isEmpty()) return
        prefs.edit { clear() }
        runCatching { NotificationManagerCompat.from(context).cancel(TAG, ID) }
    }

    private fun isShown(context: Context): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java)?.activeNotifications?.any { it.tag == TAG && it.id == ID } == true
    }.getOrDefault(false)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** What the notification shown now tells, so a new one keeps it. */
    internal fun pending(context: Context): CellarChangeSummary {
        val prefs = prefs(context)
        return CellarChangeSummary(
            opened = prefs.getInt(KEY_OPENED, 0),
            added = prefs.getInt(KEY_ADDED, 0),
            removed = prefs.getInt(KEY_REMOVED, 0),
            winesAdded = prefs.getInt(KEY_WINES, 0),
            other = prefs.getInt(KEY_OTHER, 0),
            devices = prefs.getStringSet(KEY_DEVICES, emptySet()).orEmpty().mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }.toSet(),
            unknownDevice = prefs.getBoolean(KEY_UNKNOWN, false),
        )
    }

    private fun savePending(context: Context, summary: CellarChangeSummary) = prefs(context).edit {
        putInt(KEY_OPENED, summary.opened)
        putInt(KEY_ADDED, summary.added)
        putInt(KEY_REMOVED, summary.removed)
        putInt(KEY_WINES, summary.winesAdded)
        putInt(KEY_OTHER, summary.other)
        putStringSet(KEY_DEVICES, summary.devices.map { it.toString() }.toSet())
        putBoolean(KEY_UNKNOWN, summary.unknownDevice)
    }

    private const val KEY_OPENED = "opened"
    private const val KEY_ADDED = "added"
    private const val KEY_REMOVED = "removed"
    private const val KEY_WINES = "wines"
    private const val KEY_OTHER = "other"
    private const val KEY_DEVICES = "devices"
    private const val KEY_UNKNOWN = "unknown"
}
