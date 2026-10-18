package app.encave

import android.app.Application
import app.encave.core.JsonFileCellarStore
import app.encave.data.CellarRepository
import app.encave.data.PhotoStore
import app.encave.data.Preferences
import app.encave.data.ai.AiPreferences
import app.encave.data.search.WebSearchPreferences
import app.encave.sync.SyncManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class EncaveApplication : Application() {
    /** Application-wide scope, so saves are never cancelled with a screen. */
    private val scope = CoroutineScope(SupervisorJob())

    /** `files/cellar.json` in app-private storage (sandboxed, encrypted at rest by Android). */
    val repository: CellarRepository by lazy {
        CellarRepository(JsonFileCellarStore(File(filesDir, "cellar.json")), scope)
    }

    val preferences: Preferences by lazy { Preferences(this) }
    val photos: PhotoStore by lazy { PhotoStore(this) }
    val ai: AiPreferences by lazy { AiPreferences(this) }
    val webSearch: WebSearchPreferences by lazy { WebSearchPreferences(this) }

    /** Pairing and sync with the user's other devices (off until a device is added). */
    val sync: SyncManager by lazy { SyncManager(this, repository, photos, scope) }

    override fun onCreate() {
        super.onCreate()
        sync.start()
        if (app.encave.reminders.Reminders.isEnabled(this)) app.encave.reminders.Reminders.schedule(this)
        // Photos no wine refers to any more (deleted or replaced) are removed at launch.
        scope.launch(Dispatchers.IO) {
            repository.loaded.first { it }
            if (!repository.loadFailed) {
                photos.removeOrphans(repository.cellar.value.wines.values.mapNotNull { it.photoId }.toSet())
            }
        }
    }
}
