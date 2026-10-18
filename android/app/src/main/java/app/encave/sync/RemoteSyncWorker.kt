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
import java.util.concurrent.TimeUnit

/**
 * While a relay or WebDAV folder is set: a round every 15 minutes (the shortest period Android
 * allows) when the device is online, so changes arrive while the app is closed.
 */
class RemoteSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val sync = (applicationContext as EncaveApplication).sync
        sync.awaitStarted()
        if (sync.storage.remoteStorage == null) return Result.success()
        sync.remoteRound()
        return Result.success()
    }

    companion object {
        private const val NAME = "encave.remote-sync"

        fun schedule(context: Context) {
            runCatching {
                val request = PeriodicWorkRequestBuilder<RemoteSyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }

        fun cancel(context: Context) {
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
        }
    }
}
