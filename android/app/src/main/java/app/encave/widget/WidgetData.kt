package app.encave.widget

import android.content.Context
import app.encave.EncaveApplication
import app.encave.core.SampleData
import app.encave.core.WidgetSnapshot
import app.encave.data.CellarRepository
import app.encave.ui.components.sampleLabels
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The widgets' copy of the cellar summary, `files/widget-snapshot.json` in app-private
 * storage, so a widget can be drawn without loading the cellar. Stays on the device.
 */
class WidgetSnapshotStore(private val file: File) {
    private val state = MutableStateFlow<WidgetSnapshot?>(null)
    private var read = false

    /** The saved summary (null if there is none yet or it cannot be read), kept in memory once read. */
    val snapshot: StateFlow<WidgetSnapshot?>
        get() {
            ensureRead()
            return state.asStateFlow()
        }

    @Synchronized
    private fun ensureRead() {
        if (read) return
        state.value = WidgetSnapshot.decode(runCatching { file.takeIf { it.exists() }?.readText(Charsets.UTF_8) }.getOrNull())
        read = true
    }

    /** Saves [snapshot] (atomically) unless the same one is already saved; true when it was written. */
    @Synchronized
    fun write(snapshot: WidgetSnapshot): Boolean {
        ensureRead()
        if (state.value == snapshot) return false
        val text = snapshot.encoded()
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text, Charsets.UTF_8)
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        state.value = snapshot
        return true
    }
}

/**
 * Keeps the widgets' summary up to date: after each change of the cellar, made here
 * or received from another device, it is worked out again after a short pause (so a
 * burst of changes is written once) and the widgets are redrawn only when it changed.
 */
class WidgetPublisher(
    private val repository: CellarRepository,
    private val store: WidgetSnapshotStore,
    private val scope: CoroutineScope,
    private val onChanged: suspend () -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val year: () -> Int = { LocalDate.now().year },
) {
    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch(io) {
            repository.loaded.first { it }
            repository.cellar.debounce(DELAY_MS).collectLatest { cellar ->
                // A cellar that could not be read is not summarized as empty.
                if (repository.loadFailed) return@collectLatest
                val written = runCatching { store.write(WidgetSnapshot.of(cellar, year())) }.getOrDefault(false)
                if (written) runCatching { onChanged() }
            }
        }
    }

    companion object {
        const val DELAY_MS = 800L
    }
}

/** What the widgets read. */
object WidgetData {
    /**
     * The summary for this year. Worked out from the cellar file when there is none yet
     * or it is from last year (the widgets refresh daily, even if the app is not opened).
     * Never redraws the widgets itself.
     */
    suspend fun current(app: EncaveApplication, year: Int = LocalDate.now().year): WidgetSnapshot? {
        val store = app.widgetSnapshots
        val saved = withContext(Dispatchers.IO) { store.snapshot.value }
        if (saved != null && saved.year == year) return saved
        app.repository.loaded.first { it }
        if (app.repository.loadFailed) return saved
        val fresh = WidgetSnapshot.of(app.repository.cellar.value, year)
        withContext(Dispatchers.IO) { runCatching { store.write(fresh) } }
        return fresh
    }

    /** The sample cellar, for the widget picker's previews. */
    fun preview(context: Context): WidgetSnapshot {
        val year = LocalDate.now().year
        return WidgetSnapshot.of(SampleData.cellar(year = year, labels = sampleLabels(context.resources)), year)
    }
}
