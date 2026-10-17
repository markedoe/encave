package app.encave.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.encave.EncaveApplication
import app.encave.R
import app.encave.core.Bottle
import app.encave.core.Cellar
import app.encave.core.CellarError
import app.encave.core.CellarException
import app.encave.core.CellarExport
import app.encave.core.CellarFilter
import app.encave.core.CellarSort
import app.encave.core.WineGroup
import app.encave.core.ImportError
import app.encave.core.ImportException
import app.encave.core.ImportSummary
import app.encave.core.PurchaseInfo
import app.encave.core.Rack
import app.encave.core.SampleData
import app.encave.core.Slot
import app.encave.core.Tasting
import app.encave.core.Wine
import app.encave.core.importCsv
import app.encave.core.merge
import app.encave.data.CellarRepository
import app.encave.data.PhotoStore
import app.encave.data.Preferences
import app.encave.data.ai.AiError
import app.encave.data.ai.AiPreferences
import app.encave.data.ai.AiService
import app.encave.data.ai.LabelAnalysis
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Text resolved in the UI, so the view model never holds a Context. */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plural(@PluralsRes val id: Int, val count: Int) : UiText
}

/** A snackbar message, with an optional undo. */
data class UiMessage(val text: UiText, val undo: (() -> Unit)? = null)

/**
 * The cellar and every command the screens can run. Commands go through the
 * repository, which saves before publishing; errors become snackbar messages.
 */
class AppViewModel(
    private val repository: CellarRepository,
    val preferences: Preferences,
    val photos: PhotoStore,
    val ai: AiPreferences,
) : ViewModel() {
    val cellar: StateFlow<Cellar> = repository.cellar
    val loaded: StateFlow<Boolean> = repository.loaded

    private val messageChannel = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = messageChannel.receiveAsFlow()

    val year: Int get() = LocalDate.now().year

    /** Cellar list search, filters and sort; kept while moving between sections. */
    val filter = MutableStateFlow(CellarFilter())
    val sort = MutableStateFlow(CellarSort.Name)

    val groups: StateFlow<List<WineGroup>> = combine(cellar, filter, sort) { cellar, filter, sort ->
        cellar.groups(filter, sort, year)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            repository.loaded.collect { if (it && repository.loadFailed) post(UiText.Res(R.string.msg_load_failed)) }
        }
    }

    private fun post(text: UiText, undo: (() -> Unit)? = null) {
        messageChannel.trySend(UiMessage(text, undo))
    }

    private fun errorText(error: Throwable): UiText = UiText.Res(
        when (error) {
            is CellarException -> when (error.error) {
                CellarError.SlotTaken -> R.string.err_slot_taken
                CellarError.SlotOutOfRange -> R.string.err_slot_range
                CellarError.RackNotEmpty -> R.string.err_rack_not_empty
                CellarError.AlreadyConsumed -> R.string.err_already_consumed
                else -> R.string.err_generic
            }
            is ImportException -> when (error.error) {
                ImportError.NotAnEncaveBackup -> R.string.err_not_backup
                ImportError.EmptyFile -> R.string.err_empty_file
                ImportError.MissingNameColumn -> R.string.err_missing_name
            }
            is java.io.IOException -> R.string.msg_save_failed
            else -> R.string.err_generic
        },
    )

    /** Runs a change; reports failures and, on success, [onSuccess]. */
    private fun <T> change(onSuccess: (T) -> Unit = {}, block: (Cellar) -> T) {
        viewModelScope.launch {
            repository.update(block).onSuccess(onSuccess).onFailure { post(errorText(it)) }
        }
    }

    private fun nameOf(cellar: Cellar, bottleId: UUID): String {
        val wineId = cellar.bottles[bottleId]?.wineId
        return wineId?.let { cellar.wines[it]?.displayName } ?: ""
    }

    // region Bottles

    /** [photo] is a prepared JPEG for a new wine; it is saved before the bottles are added. */
    fun addBottles(wine: Wine, quantity: Int, slots: List<Slot>, purchase: PurchaseInfo, photo: ByteArray? = null) {
        viewModelScope.launch {
            val photoId = photo?.let { withContext(Dispatchers.IO) { photos.save(it) } }
            val record = if (photoId != null) wine.copy(photoId = photoId) else wine
            repository.update { it.addBottles(record, quantity, slots, purchase) }
                .onSuccess { post(UiText.Plural(R.plurals.bottles_added, it.size)) }
                .onFailure {
                    photos.delete(photoId)
                    post(errorText(it))
                }
        }
    }

    fun drink(bottleId: UUID, tasting: Tasting) {
        val previous = cellar.value.bottles[bottleId]?.slot
        change<String>({ name -> post(UiText.Res(R.string.msg_drunk, listOf(name))) { undoDrink(bottleId, previous) } }) {
            it.drink(bottleId, tasting)
            nameOf(it, bottleId)
        }
    }

    fun undoDrink(bottleId: UUID, previousSlot: Slot?) = change<Unit> { it.undoDrink(bottleId, previousSlot) }

    fun putBack(bottleId: UUID) = change<String>({ post(UiText.Res(R.string.msg_put_back, listOf(it))) }) {
        it.undoDrink(bottleId, null)
        nameOf(it, bottleId)
    }

    fun markGone(bottleId: UUID) {
        val previous = cellar.value.bottles[bottleId]?.slot
        change<String>({ name -> post(UiText.Res(R.string.msg_gone, listOf(name))) { undoDrink(bottleId, previous) } }) {
            it.markGone(bottleId)
            nameOf(it, bottleId)
        }
    }

    fun deleteBottle(bottleId: UUID) {
        val snapshot = cellar.value.bottles[bottleId] ?: return
        val wine = cellar.value.wines[snapshot.wineId]
        change<Unit>({
            // Undo re-adds the records by id; a slot taken in the meantime is dropped.
            post(UiText.Res(R.string.msg_bottle_deleted)) {
                change<Unit> { c -> c.merge(Cellar(listOfNotNull(wine), listOf(snapshot))) }
            }
        }) { it.deleteBottle(bottleId) }
    }

    fun move(bottleId: UUID, slot: Slot?) = change<String?>({ label -> label?.let { post(UiText.Res(R.string.msg_moved, listOf(it))) } }) {
        it.move(bottleId, slot)
        slot?.let { s -> it.rack(s.rackId)?.let { rack -> "${rack.name} · ${rack.label(s)}" } }
    }

    fun swap(a: Slot, b: Slot) = change<Unit> { it.swap(a, b) }

    fun updateTasting(bottleId: UUID, tasting: Tasting) =
        change<Unit>({ post(UiText.Res(R.string.msg_saved)) }) { it.updateTasting(bottleId, tasting) }

    // endregion

    // region Wines

    /** [photo] replaces the wine's photo when set; [removePhoto] clears it. */
    fun updateWine(wine: Wine, photo: ByteArray? = null, removePhoto: Boolean = false) {
        viewModelScope.launch {
            val previous = cellar.value.wines[wine.id]?.photoId
            val newId = photo?.let { withContext(Dispatchers.IO) { photos.save(it) } }
            val record = when {
                newId != null -> wine.copy(photoId = newId)
                removePhoto -> wine.copy(photoId = null)
                else -> wine
            }
            repository.update { it.updateWine(record) }
                .onSuccess {
                    if (record.photoId != previous) photos.delete(previous)
                    post(UiText.Res(R.string.msg_saved))
                }
                .onFailure {
                    photos.delete(newId)
                    post(errorText(it))
                }
        }
    }

    fun deleteWine(wineId: UUID) {
        val before = cellar.value
        val wine = before.wines[wineId] ?: return
        val bottles = before.bottles.values.filter { it.wineId == wineId }
        change<Unit>({
            post(UiText.Res(R.string.msg_wine_deleted, listOf(wine.displayName))) {
                change<Unit> { c -> c.merge(Cellar(listOf(wine), bottles)) }
            }
        }) { it.deleteWine(wineId) }
    }

    // endregion

    // region Photos and AI label recognition

    /** Resized JPEG of a picked or captured image, or null if it cannot be read. */
    suspend fun preparePhoto(uri: Uri): ByteArray? = withContext(Dispatchers.IO) { photos.prepare(uri) }

    /** Sends only the label photo to the provider set in Settings. */
    suspend fun analyzeLabel(jpeg: ByteArray): Result<LabelAnalysis> {
        val settings = ai.settings.value
        val key = withContext(Dispatchers.IO) { ai.apiKey }
        return runCatching { AiService.analyzeLabel(jpeg, settings, key) }
    }

    fun aiErrorText(error: Throwable): UiText = when (error) {
        AiError.NotConfigured -> UiText.Res(R.string.ai_err_not_configured)
        AiError.MissingKey -> UiText.Res(R.string.ai_err_missing_key)
        AiError.Refused -> UiText.Res(R.string.ai_err_refused)
        AiError.InvalidResponse -> UiText.Res(R.string.ai_err_invalid)
        is AiError.Server -> if (error.status == 0) UiText.Res(R.string.ai_err_network) else UiText.Res(R.string.ai_err_server, listOf(error.status))
        else -> UiText.Res(R.string.err_generic)
    }

    fun showMessage(text: UiText) = post(text)

    // endregion

    // region Racks

    fun addRack(rack: Rack) = change<Unit> { it.addRack(rack) }

    /** Calls [onDone] only when the rack was saved (shrinking with bottles outside is refused). */
    fun updateRack(rack: Rack, onDone: () -> Unit) = change<Unit>({ onDone() }) { it.updateRack(rack) }

    fun removeRack(rackId: UUID) = change<Unit> { it.removeRack(rackId) }

    fun moveRack(from: Int, to: Int) = change<Unit> {
        // `to` is the final index; moveRacks takes SwiftUI's "insert before" offset.
        it.moveRacks(setOf(from), if (to > from) to + 1 else to)
    }

    // endregion

    // region Settings and data

    fun setCurrency(code: String) = change<Unit> { it.settings = it.settings.copy(currency = code) }

    fun setDynamicColor(enabled: Boolean) = preferences.setDynamicColor(enabled)

    fun loadSample(labels: SampleData.Labels) {
        viewModelScope.launch {
            val sample = SampleData.cellar(year, labels).also { it.settings = cellar.value.settings }
            repository.replace(sample)
                .onSuccess { post(UiText.Res(R.string.msg_sample_loaded)) }
                .onFailure { post(errorText(it)) }
        }
    }

    fun eraseAll() {
        viewModelScope.launch {
            repository.replace(Cellar())
                .onSuccess { post(UiText.Res(R.string.msg_erased)) }
                .onFailure { post(errorText(it)) }
        }
    }

    fun exportJson(resolver: ContentResolver, uri: Uri) = export(resolver, uri) { CellarExport.json(it) }

    fun exportCsv(resolver: ContentResolver, uri: Uri) = export(resolver, uri) { CellarExport.csv(it) }

    private fun export(resolver: ContentResolver, uri: Uri, render: (Cellar) -> String) {
        val snapshot = cellar.value
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = render(snapshot)
                    requireNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
            }
            post(UiText.Res(if (result.isSuccess) R.string.msg_exported else R.string.msg_export_failed))
        }
    }

    /** Imports an Encave JSON backup (merged by id) or a CSV file (one row per bottle). */
    fun import(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { requireNotNull(resolver.openInputStream(uri)).use { it.readBytes().toString(Charsets.UTF_8) } }
            }.getOrElse {
                post(UiText.Res(R.string.err_generic))
                return@launch
            }
            val isJson = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t').startsWith("{")
            val backup = if (isJson) {
                runCatching { CellarExport.backupFromJson(text) }.getOrElse {
                    post(errorText(it))
                    return@launch
                }
            } else {
                null
            }
            repository.update { cellar -> if (backup != null) cellar.merge(backup.cellar) else cellar.importCsv(text) }
                .onSuccess { post(importText(it)) }
                .onFailure { post(errorText(it)) }
        }
    }

    private fun importText(summary: ImportSummary): UiText = if (summary.rowsSkipped > 0) {
        UiText.Res(R.string.msg_imported_skipped, listOf(summary.winesAdded, summary.bottlesAdded, summary.rowsSkipped))
    } else {
        UiText.Res(R.string.msg_imported, listOf(summary.winesAdded, summary.bottlesAdded))
    }

    // endregion

    companion object {
        fun factory(app: EncaveApplication): ViewModelProvider.Factory = viewModelFactory {
            initializer { AppViewModel(app.repository, app.preferences, app.photos, app.ai) }
        }
    }
}
