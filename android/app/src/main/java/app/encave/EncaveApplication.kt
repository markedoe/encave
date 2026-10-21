package app.encave

import android.app.Application
import app.encave.core.JsonFileCellarStore
import app.encave.core.WidgetSnapshot
import app.encave.data.CellarRepository
import app.encave.data.DocumentStore
import app.encave.data.PhotoStore
import app.encave.data.SyncFiles
import app.encave.data.Preferences
import app.encave.data.ai.AiPreferences
import app.encave.data.search.WebSearchPreferences
import app.encave.sync.SyncManager
import app.encave.widget.WidgetPublisher
import app.encave.widget.WidgetSnapshotStore
import app.encave.widget.Widgets
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
    val documents: DocumentStore by lazy { DocumentStore(this) }
    val ai: AiPreferences by lazy { AiPreferences(this) }
    val webSearch: WebSearchPreferences by lazy { WebSearchPreferences(this) }

    /** Pairing and sync with the user's other devices (off until a device is added). */
    val sync: SyncManager by lazy { SyncManager(this, repository, SyncFiles(photos, documents), preferences, scope) }

    /** The home screen widgets' summary of the cellar (`files/widget-snapshot.json`). */
    val widgetSnapshots: WidgetSnapshotStore by lazy { WidgetSnapshotStore(File(filesDir, WidgetSnapshot.FILE_NAME)) }

    override fun onCreate() {
        super.onCreate()
        sync.start()
        // Widgets follow every change, made here or synced from another device.
        WidgetPublisher(repository, widgetSnapshots, scope, onChanged = { Widgets.update(this) }).start()
        scope.launch { Widgets.publishPreviews(this@EncaveApplication) }
        if (app.encave.reminders.Reminders.isEnabled(this)) app.encave.reminders.Reminders.schedule(this)
        // Photos and documents nothing has referred to for a while (deleted or replaced) are
        // removed at launch. Not while a cellar file that could not be read is kept: its files
        // may be needed.
        scope.launch(Dispatchers.IO) {
            documents.removeTemporaryCopies()
            repository.loaded.first { it }
            if (!repository.loadFailed && repository.keptAside().isEmpty()) {
                photos.removeOrphans(repository.cellar.value.photoIds)
                documents.removeOrphans(repository.cellar.value.attachmentIds)
            }
        }
    }
}
