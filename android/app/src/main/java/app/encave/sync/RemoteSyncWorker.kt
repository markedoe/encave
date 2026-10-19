package app.encave.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.encave.EncaveApplication
import app.encave.data.SyncFrequency
import java.util.concurrent.TimeUnit

/**
 * While a relay or WebDAV folder is set: a round every 15 minutes (the shortest period Android
 * allows; longer if the chosen frequency is) when the device is online, so changes arrive while
 * the app is closed. None when the user syncs only with Sync now. Changes from other devices
 * may then be told in a notification ([ChangeNotifications]), posted by the round.
 */
class RemoteSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val sync = (applicationContext as EncaveApplication).sync
        sync.awaitStarted()
        if (sync.storage.remoteStorage == null || sync.frequency == SyncFrequency.Manual) return Result.success()
        sync.remoteRound()
        return Result.success()
    }

    companion object {
        private const val NAME = "encave.remote-sync"

        /** The shortest period WorkManager allows. */
        const val MIN_INTERVAL_MINUTES = 15

        /** The period of the background work for a frequency; null when there is none. */
        fun intervalMinutes(frequency: SyncFrequency): Int? = frequency.minutes?.let { maxOf(it, MIN_INTERVAL_MINUTES) }

        /** Schedules the work for [frequency], or cancels it when the user syncs only with Sync now. */
        fun schedule(context: Context, frequency: SyncFrequency) {
            val minutes = intervalMinutes(frequency) ?: return cancel(context)
            runCatching {
                val request = PeriodicWorkRequestBuilder<RemoteSyncWorker>(minutes.toLong(), TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
                // Updates the period if it changed, keeping when the work runs next.
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
            }
        }

        fun cancel(context: Context) {
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
        }
    }
}
