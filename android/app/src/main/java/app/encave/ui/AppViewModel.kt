package app.encave.ui

import app.encave.BuildConfig
import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.encave.EncaveApplication
import app.encave.R
import app.encave.core.Attachment
import app.encave.core.AttachmentTarget
import app.encave.core.Bottle
import app.encave.core.Cellar
import app.encave.core.CellarError
import app.encave.core.CellarException
import app.encave.core.CellarExport
import app.encave.core.CellarFilter
import app.encave.core.CellarSort
import app.encave.core.CellarVersionFolder
import app.encave.core.Discovery
import app.encave.core.FileKind
import app.encave.core.addDiscovery
import app.encave.core.deleteDiscovery
import app.encave.core.markBought
import app.encave.core.updateDiscovery
import app.encave.core.WineGroup
import app.encave.core.ImportError
import app.encave.core.ImportException
import app.encave.core.ImportSummary
import app.encave.core.PurchaseInfo
import app.encave.core.Rack
import app.encave.core.RackCell
import app.encave.ui.components.displayLabel
import app.encave.ui.components.volumeText
import app.encave.core.SampleData
import app.encave.core.containsSample
import app.encave.core.removingSample
import app.encave.core.Slot
import app.encave.core.Tasting
import app.encave.core.Wine
import app.encave.core.CsvText
import app.encave.core.importCsv
import app.encave.core.merge
import app.encave.core.CellarVersion
import app.encave.core.RestoreMode
import app.encave.core.restoring
import app.encave.core.sync.PhotoId
import app.encave.core.sync.LocalNetworkException
import app.encave.ui.search.localNetworkErrorText
import app.encave.data.BackupManager
import app.encave.data.CellarRepository
import app.encave.data.RestoreSource
import app.encave.data.DocumentStore
import app.encave.data.PendingDocument
import app.encave.data.PhotoStore
import app.encave.data.Preferences
import app.encave.data.ai.AiAllModelsFailed
import app.encave.ui.components.resolve
import app.encave.data.ai.AiConnection
import app.encave.data.ai.AiError
import app.encave.data.ai.AiModelChoice
import app.encave.data.ai.AiReading
import app.encave.data.ai.AiPreferences
import app.encave.data.ai.AiService
import app.encave.data.ai.LabelAnalysis
import app.encave.core.WineIdentity
import app.encave.core.ExpectedProfile
import app.encave.data.ai.completed
import app.encave.data.ai.completedFromWeb
import app.encave.data.ai.completing
import app.encave.data.ai.sameDetails
import app.encave.sync.ChangeNotifications
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.encave.core.DiaryFilter

/** Text resolved in the UI, so the view model never holds a Context. */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plural(@PluralsRes val id: Int, val count: Int) : UiText

    /** A text built from resources when shown (a place name, which depends on the language). */
    class Built(val build: (android.content.res.Resources) -> String) : UiText
}

/** A connection's own name, or its provider's ("Other server" in the user's language). */
fun AiConnection.title(resources: android.content.res.Resources): String =
    name.trim().ifEmpty { provider.brand ?: resources.getString(R.string.ai_local) }

/**
 * A snackbar message, with an optional undo; [persistent] ones stay until dismissed, [long]
 * ones (the undo of a large change) stay longer than the others. [actionLabel] names the
 * action when it is not Undo (Open settings).
 */
data class UiMessage(
    val text: UiText,
    val undo: (() -> Unit)? = null,
    val persistent: Boolean = false,
    val actionLabel: UiText? = null,
    val long: Boolean = false,
    /** Set when a later message may take this one's place while it is waiting or shown. */
    val slot: MessageSlot? = null,
    /** Told when the Undo can no longer run: the message went without it. */
    val undoGone: (() -> Unit)? = null,
)

/**
 * Lets a later message replace one still waiting or on screen (the AI completing a wine
 * just added: one snackbar, whose Undo takes both back). Used on the main thread.
 */
class MessageSlot {
    private val state = MutableStateFlow(false)

    /** True once replaced: the snackbar goes away, its Undo with it. */
    val replaced: StateFlow<Boolean> = state

    /** Set by the screen once the snackbar is gone. */
    var finished = false

    /** Replaces the message if it is still waiting or shown; false once it is gone. */
    fun replace(): Boolean {
        if (finished) return false
        state.value = true
        return true
    }
}

/** The AI reading a label photo for a form, kept by the view model so it survives rotation. */
sealed interface LabelReading {
    data object Reading : LabelReading
    /** [model] of [connection] read the label (null when not known). */
    data class Done(val analysis: LabelAnalysis, val model: AiModelChoice? = null, val connection: AiConnection? = null) : LabelReading
    data class Failed(val error: Throwable) : LabelReading
}

/** A haptic confirmation played by the UI: an action done, or refused. */
enum class Haptic { Confirm, Reject }

/**
 * The cellar and every command the screens can run. Commands go through the
 * repository, which saves before publishing; errors become snackbar messages.
 */
class AppViewModel(
    private val repository: CellarRepository,
    val preferences: Preferences,
    val photos: PhotoStore,
    val documents: DocumentStore,
    val ai: AiPreferences,
    /** Back up now, previous versions and the weekly archive. */
    val backups: BackupManager,
    /** True while other devices sync with this one: erasing reaches them, the sample is not offered. */
    sharedWithDevices: Flow<Boolean> = flowOf(false),
    /** "Updated from Marc's iPhone": changes from other devices taken in while the app is open. */
    syncUpdates: Flow<UiText> = emptyFlow(),
) : ViewModel() {
    val sharedWithDevices: StateFlow<Boolean> = sharedWithDevices.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val cellar: StateFlow<Cellar> = repository.cellar
    val loaded: StateFlow<Boolean> = repository.loaded

    private val messageChannel = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = messageChannel.receiveAsFlow()

    // Not kept for later: a haptic only makes sense while the app is shown.
    private val hapticFlow = MutableSharedFlow<Haptic>(extraBufferCapacity = 8)

    /**
     * Confirm when bottles are added, drunk, moved, placed or received, a discovery is saved,
     * documents attached or a backup restored (as on Apple); Reject when a change is refused.
     */
    val haptics: SharedFlow<Haptic> = hapticFlow

    val year: Int get() = LocalDate.now().year

    /** Cellar list search, filters and sort; kept while moving between sections. */
    val filter = MutableStateFlow(CellarFilter())
    val sort = MutableStateFlow(CellarSort.Name)

    /** What the Journal shows; back to everything when a new entry would be hidden. */
    val journalFilter = MutableStateFlow(DiaryFilter.All)

    /** The Journal's tag chip; cleared, like the filter, when a new entry would be hidden. */
    val journalTag = MutableStateFlow<String?>(null)

    /** The cellar list, filtered and sorted off the main thread (a large cellar takes time). */
    val groups: StateFlow<List<WineGroup>> = combine(cellar, filter, sort) { cellar, filter, sort ->
        cellar.groups(filter, sort, year)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            repository.loaded.first { it }
            val kept = repository.setAsideName
            when {
                kept != null -> messageChannel.trySend(UiMessage(UiText.Res(R.string.msg_load_set_aside, listOf(kept)), persistent = true))
                repository.loadFailed -> messageChannel.trySend(UiMessage(UiText.Res(R.string.msg_load_failed), persistent = true))
            }
        }
        viewModelScope.launch { syncUpdates.collect { post(it) } }
    }

    private fun post(text: UiText, long: Boolean = false, undo: (() -> Unit)? = null) {
        messageChannel.trySend(UiMessage(text, undo, long = long))
    }

    private fun confirm() {
        hapticFlow.tryEmit(Haptic.Confirm)
    }

    /** Reports a change that failed (a place taken, a full disk…). */
    private fun fail(error: Throwable) {
        hapticFlow.tryEmit(Haptic.Reject)
        post(errorText(error))
    }

    private fun errorText(error: Throwable): UiText = UiText.Res(
        when (error) {
            is CellarException -> when (error.error) {
                CellarError.SlotTaken -> R.string.err_slot_taken
                CellarError.SlotOutOfRange -> R.string.err_slot_range
                CellarError.RackNotEmpty -> R.string.err_rack_not_empty
                CellarError.AlreadyConsumed -> R.string.err_already_consumed
                CellarError.NotReceived -> R.string.err_not_received
                CellarError.NotInCellar -> R.string.err_already_consumed
                else -> R.string.err_generic
            }
            is ImportException -> when (error.error) {
                ImportError.NotAnEncaveBackup -> R.string.err_not_backup
                ImportError.NewerVersion -> R.string.err_newer_version
                ImportError.UnsupportedArchive -> R.string.err_unsupported_archive
                ImportError.DamagedArchive -> R.string.err_damaged_archive
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
            repository.update(block).onSuccess(onSuccess).onFailure { fail(it) }
        }
    }

    private fun nameOf(cellar: Cellar, bottleId: UUID): String {
        val wineId = cellar.bottles[bottleId]?.wineId
        return wineId?.let { cellar.wines[it]?.displayName } ?: ""
    }

    // region Bottles

    /**
     * Adds bottles, with Undo. [photo] is a prepared JPEG (the label), saved first as the
     * wine's photo. [onDone] tells the screen whether they were added: on failure (a place
     * taken meanwhile by a sync, a full disk) it stays open with everything entered. Files
     * written for it are then removed, so a retry stores them once and none is left over.
     */
    fun addBottles(
        wine: Wine,
        quantity: Int,
        slots: List<Slot>,
        purchase: PurchaseInfo,
        photo: ByteArray? = null,
        boughtDiscoveryId: UUID? = null,
        /** Documents picked while adding: stored and attached to the wine, for all its bottles. */
        pendingDocuments: List<PendingDocument> = emptyList(),
        onDone: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            val newPhotos = mutableListOf<String>()
            val photoId = photo?.let {
                withContext(Dispatchers.IO) {
                    val existed = photos.contains(it)
                    photos.save(it)?.also { id -> if (!existed) newPhotos += id }
                }
            }
            val committed = withContext(Dispatchers.IO) { pendingDocuments.mapNotNull { documents.commitTracked(it) } }
            val attachments = committed.map { it.attachment }
            val newDocuments = committed.filter { it.isNew }.map { it.attachment.id }
            var record = if (photoId != null) wine.copy(photoId = photoId) else wine
            if (attachments.isNotEmpty()) record = record.copy(attachments = record.attachments.orEmpty() + attachments)
            val before = cellar.value
            val previousWine = before.wines[wine.id]
            val previousDiscovery = boughtDiscoveryId?.let { before.discoveries[it] }
            repository.update { cellar ->
                cellar.addBottles(record, quantity, slots, purchase).also {
                    if (boughtDiscoveryId != null && cellar.discoveries[boughtDiscoveryId] != null) cellar.markBought(boughtDiscoveryId)
                }
            }
                .onSuccess { added ->
                    withContext(Dispatchers.IO) { pendingDocuments.forEach(documents::discard) }
                    confirm()
                    // The AI was still reading the label: its answer completes the saved wine.
                    if (readingJobs[record.id] != null) savedWhileReading[record.id] = record
                    val ids = added.map { it.id }
                    val text = UiText.Res(if (purchase.isOrdered) R.string.msg_on_order else R.string.msg_added, listOf(added.size, record.displayName))
                    val revert = { c: Cellar -> revertAdd(c, ids, previousWine, previousDiscovery) }
                    val slot = MessageSlot()
                    lastAdd = AddBanner(record.id, added.size, purchase.isOrdered, slot, revert)
                    messageChannel.trySend(UiMessage(text, { change<Unit>(block = revert) }, slot = slot))
                    // Added without the documents that could not be stored (a full disk…).
                    if (attachments.size < pendingDocuments.size) post(UiText.Res(R.string.err_document_unreadable))
                    // A new wine, completed from the web when asked in Settings: after saving, so adding never waits for it.
                    if (previousWine == null && ai.webAutomatic.value && ai.configuration.value.webSearchOrder.isNotEmpty()) {
                        completeFromWeb(record.id, automatic = true)
                    }
                    onDone(true)
                }
                .onFailure {
                    removeUnused(newPhotos, newDocuments)
                    fail(it)
                    onDone(false)
                }
        }
    }

    /**
     * Removes files written for a save that failed. One the cellar refers to meanwhile (the
     * same photo or document, received from another device) is kept.
     */
    private suspend fun removeUnused(photoIds: List<String>, documentIds: List<String>) {
        if (photoIds.isEmpty() && documentIds.isEmpty()) return
        val now = cellar.value
        val usedPhotos = now.photoIds
        val usedDocuments = now.attachmentIds
        withContext(Dispatchers.IO) {
            photoIds.filterNot { it in usedPhotos }.forEach(photos::delete)
            documentIds.filterNot { it in usedDocuments }.forEach(documents::delete)
        }
    }

    /** The snackbar of the last add, so the AI completing that wine while it is shown takes its place. */
    private class AddBanner(val wineId: UUID, val count: Int, val ordered: Boolean, val slot: MessageSlot, val revert: (Cellar) -> Unit)

    private var lastAdd: AddBanner? = null

    /**
     * Takes back bottles just added. A new wine left without bottles goes with them; a wine
     * that was there gets back its record as it was (barcode, photo, documents added).
     */
    private fun revertAdd(c: Cellar, bottleIds: List<UUID>, previousWine: Wine?, previousDiscovery: Discovery?) {
        bottleIds.forEach { c.deleteBottle(it) }
        if (previousWine != null) {
            if (c.wines[previousWine.id] != null) c.updateWine(previousWine) else c.merge(Cellar(listOf(previousWine)))
        }
        if (previousDiscovery != null && c.discoveries[previousDiscovery.id] != null) c.updateDiscovery(previousDiscovery)
    }

    fun drink(bottleId: UUID, tasting: Tasting) {
        val previous = cellar.value.bottles[bottleId]?.slot
        if (!journalFilter.value.showsTastings) journalFilter.value = DiaryFilter.All
        // A tag only picks wines tasted elsewhere.
        journalTag.value = null
        change<String>({ name ->
            confirm()
            post(UiText.Res(R.string.msg_drunk, listOf(name))) { undoDrink(bottleId, previous) }
        }) {
            it.drink(bottleId, tasting)
            nameOf(it, bottleId)
        }
    }

    fun undoDrink(bottleId: UUID, previousSlot: Slot?) = change<Unit> { it.undoDrink(bottleId, previousSlot) }

    /** Back in the cellar, its tasting removed; Undo brings the tasting back. */
    fun putBack(bottleId: UUID) {
        val before = cellar.value.bottles[bottleId] ?: return
        change<String>({ post(UiText.Res(R.string.msg_put_back, listOf(it))) { restore(listOf(before)) } }) {
            it.undoDrink(bottleId, null)
            nameOf(it, bottleId)
        }
    }

    /** Given away, sold or broken. Undo puts the record back as it was, in its place if still free. */
    fun markGone(bottleId: UUID) {
        val before = listOfNotNull(cellar.value.bottles[bottleId])
        change<String>({ name -> post(UiText.Res(R.string.msg_gone, listOf(name))) { restore(before) } }) {
            it.markGone(bottleId)
            nameOf(it, bottleId)
        }
    }

    /** Bottles on order arrive: they join the cellar without a place. Undo puts the orders back. */
    fun receive(bottleIds: List<UUID>) {
        val before = bottleIds.mapNotNull { cellar.value.bottles[it] }
        if (before.isEmpty()) return
        change<Unit>({
            confirm()
            post(UiText.Res(R.string.msg_received, listOf(before.size))) { restore(before) }
        }) {
            it.receive(before.map(Bottle::id))
        }
    }

    /** An order that will not come. Undo puts it back on order. */
    fun cancelOrder(bottleId: UUID) {
        val before = cellar.value.bottles[bottleId] ?: return
        change<Unit>({ post(UiText.Res(R.string.msg_order_cancelled)) { restore(listOf(before)) } }) { it.markGone(bottleId) }
    }

    /**
     * Puts bottle records back as they were (undo of a change to several bottles). Bottles
     * without a place first, so the places they held are free again; a place taken in the
     * meantime is left empty.
     */
    private fun restore(bottles: List<Bottle>) = change<Unit> { c ->
        bottles.sortedBy { it.slot != null }.forEach { bottle ->
            if (c.bottles[bottle.id] != null) {
                runCatching { c.updateBottle(bottle) }.onFailure { c.updateBottle(bottle.copy(slot = null)) }
            }
        }
    }

    /** Keeps bottles for an occasion, or frees them (null or blank), with Undo. */
    fun reserve(bottleIds: List<UUID>, occasion: String?) {
        val before = bottleIds.mapNotNull { cellar.value.bottles[it] }
        // Nothing to change (an empty name for a bottle never kept): no message.
        if (before.isEmpty() || !cellar.value.changesReservation(bottleIds, occasion)) return
        val name = occasion?.trim().orEmpty()
        change<Unit>({
            val text = if (name.isEmpty()) UiText.Res(R.string.msg_reservation_removed) else UiText.Res(R.string.reserved_for, listOf(name))
            post(text) { change<Unit> { c -> before.forEach { b -> c.bottles[b.id]?.let { c.updateBottle(it.copy(reservedFor = b.reservedFor)) } } } }
        }) { it.reserve(before.map(Bottle::id), occasion) }
    }

    /**
     * A price found for another bottle size: the wine's bottles in the cellar of size [old] get
     * the size [new], and the price is set, with one Undo for both (which puts back the sizes
     * still [new], and the price unless another one was set). As on Apple.
     */
    fun changeSize(wineId: UUID, old: Int, new: Int, price: BigDecimal, currency: String) {
        val before = cellar.value.wines[wineId] ?: return
        change<List<UUID>>({ changed ->
            post(UiText.Res(R.string.msg_size_changed, listOf(volumeText(new)))) {
                change<Unit> { c ->
                    changed.forEach { id -> c.bottles[id]?.takeIf { it.sizeML == new }?.let { c.updateBottle(it.copy(sizeML = old)) } }
                    val wine = c.wines[wineId] ?: return@change
                    if (wine.currentPrice?.compareTo(price) == 0 && wine.currentPriceCurrency == currency) {
                        c.updateWine(
                            wine.copy(
                                currentPrice = before.currentPrice,
                                currentPriceCurrency = before.currentPriceCurrency,
                                currentPriceDate = before.currentPriceDate,
                            ),
                        )
                    }
                }
            }
        }) { c ->
            val changed = c.changeSize(wineId, old, new)
            c.wines[wineId]?.let {
                c.updateWine(it.copy(currentPrice = price, currentPriceCurrency = currency, currentPriceDate = app.encave.core.ReferenceDates.now()))
            }
            changed
        }
    }

    fun saveSearch(name: String, filter: CellarFilter) = change<Unit> { it.saveSearch(name, filter) }

    /** A saved search, with Undo: it comes back in its place among the filters. */
    fun deleteSearch(id: UUID) {
        val search = cellar.value.settings.savedSearches.orEmpty().firstOrNull { it.id == id } ?: return
        change<Unit>({
            post(UiText.Res(R.string.msg_search_deleted, listOf(search.name))) { change<Unit> { it.restoreSearch(search) } }
        }) { it.deleteSearch(id) }
    }

    // region Several wines or bottles at once (one Undo for all)

    private fun bottlesOfWines(wineIds: Set<UUID>) = cellar.value.bottles.values.filter { it.wineId in wineIds }

    fun placeWines(wineIds: Set<UUID>, rackId: UUID) {
        val before = bottlesOfWines(wineIds)
        change<Int>({ placed ->
            if (placed == 0) post(UiText.Res(R.string.msg_no_free_place))
            else {
                confirm()
                post(UiText.Res(R.string.msg_bottles_placed, listOf(placed))) { restore(before) }
            }
        }) { it.place(wineIds, rackId) }
    }

    fun unplaceWines(wineIds: Set<UUID>) {
        val before = bottlesOfWines(wineIds)
        change<Int>({ moved ->
            if (moved > 0) post(UiText.Res(R.string.msg_bottles_taken_out, listOf(moved))) { restore(before) }
        }) { it.unplace(wineIds) }
    }

    fun deleteWines(wineIds: Set<UUID>) {
        val before = cellar.value
        val wines = wineIds.mapNotNull { before.wines[it] }
        val bottles = before.bottles.values.filter { it.wineId in wineIds }
        val discoveries = before.discoveries.values.filter { it.wineId in wineIds }
        change<Unit>({
            post(UiText.Res(R.string.msg_wines_deleted, listOf(wines.size))) {
                change<Unit> { c -> c.merge(Cellar(wines, bottles, discoveries = discoveries)) }
            }
        }) { c -> wineIds.forEach { c.deleteWine(it) } }
    }

    /** Bottles not found where they should be: they stay in the cellar without a place. */
    fun takeOut(bottleIds: Set<UUID>) {
        val before = bottleIds.mapNotNull { cellar.value.bottles[it] }
        change<Unit>({ post(UiText.Res(R.string.msg_bottles_taken_out, listOf(before.size))) { restore(before) } }) { c ->
            before.forEach { c.move(it.id, null) }
        }
    }

    /** Bottles no longer in the cellar (given away, drunk elsewhere, broken). */
    fun markGone(bottleIds: Set<UUID>) {
        val before = bottleIds.mapNotNull { cellar.value.bottles[it] }
        change<Unit>({ post(UiText.Res(R.string.msg_bottles_gone, listOf(before.size))) { restore(before) } }) { c ->
            before.forEach { c.markGone(it.id) }
        }
    }

    // endregion

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

    /**
     * Moves a bottle to a place, or out of the racks with [storageNote] saying where it is.
     * Undo puts it back where it was (the inverse move, so later changes are kept).
     */
    fun move(bottleId: UUID, slot: Slot?, storageNote: String? = null) {
        val before = cellar.value.bottles[bottleId] ?: return
        change<Rack?>({ rack ->
            val text = if (slot != null && rack != null) {
                UiText.Built { r -> r.getString(R.string.msg_moved, rack.name, rack.displayLabel(slot, r)) }
            } else {
                UiText.Res(R.string.msg_taken_out_one)
            }
            confirm()
            post(text) { moveBack(before) }
        }) { c ->
            c.move(bottleId, slot)
            if (slot == null) c.bottles[bottleId]?.let { c.updateBottle(it.copy(storageNote = storageNote?.trim()?.ifEmpty { null })) }
            slot?.let { c.rack(it.rackId) }
        }
    }

    /** Back to the place (and note) a bottle had before a move. */
    private fun moveBack(before: Bottle) = change<Unit> { c ->
        val now = c.bottles[before.id] ?: return@change
        if (now.status != Bottle.Status.InCellar) return@change
        c.move(before.id, before.slot)
        c.bottles[before.id]?.let { c.updateBottle(it.copy(storageNote = before.storageNote)) }
    }

    /**
     * Swaps the contents of two places. Undo swaps them back while both are still where the swap
     * put them, so a bottle put in one of the places since never moves.
     */
    fun swap(a: Slot, b: Slot) {
        val first = cellar.value.bottleAt(a)?.id
        val second = cellar.value.bottleAt(b)?.id
        change<Unit>({ confirm(); post(UiText.Res(R.string.msg_swapped)) { change<Unit> { it.undoSwap(a, b, first, second) } } }) { it.swap(a, b) }
    }

    fun updateTasting(bottleId: UUID, tasting: Tasting) =
        change<Unit>({ post(UiText.Res(R.string.msg_saved)) }) { it.updateTasting(bottleId, tasting) }

    // endregion

    // region Wines

    // Photo files are never deleted right away: an Undo, or another device that has not
    // synced yet, may refer to them again. Those no wine refers to are removed after a
    // while (PhotoStore.removeOrphans, at launch).

    /**
     * Shows [jpeg] for the wine instead of its label photo (a picture from the internet),
     * or the label photo again when null. The label photo itself is never changed.
     */
    fun setCover(wineId: java.util.UUID, jpeg: ByteArray?) {
        viewModelScope.launch {
            if (cellar.value.wines[wineId] == null) return@launch
            val newId = jpeg?.let { withContext(Dispatchers.IO) { photos.save(it) } }
            if (jpeg != null && newId == null) {
                post(UiText.Res(R.string.err_generic))
                return@launch
            }
            repository.update { c -> c.wines[wineId]?.let { c.updateWine(it.copy(coverPhotoId = newId)) } }
                .onFailure { fail(it) }
        }
    }

    /** [photo] replaces the wine's photo when set; [removePhoto] clears it. */
    fun updateWine(
        wine: Wine,
        photo: ByteArray? = null,
        removePhoto: Boolean = false,
        quiet: Boolean = false,
        fromForm: Boolean = false,
        /** The form did not change what to expect: the wine's own is kept (asked meanwhile, or synced). */
        keepsExpected: Boolean = false,
    ) {
        viewModelScope.launch {
            val newId = photo?.let { withContext(Dispatchers.IO) { photos.save(it) } }
            val record = when {
                newId != null -> wine.copy(photoId = newId)
                removePhoto -> wine.copy(photoId = null)
                else -> wine
            }
            // The form does not show documents (nor, for the wine form, critic scores, edited on
            // the wine page): those of the wine as it is now are kept (added meanwhile on another
            // device, for example), so saving the form never brings back an older list.
            repository.update { c ->
                val live = c.wines[record.id]
                c.updateWine(
                    record.copy(
                        attachments = live?.attachments ?: record.attachments,
                        criticScores = if (fromForm && live != null) live.criticScores else record.criticScores,
                        // Pages of the last web completion, not edited in the form.
                        webSources = if (fromForm && live != null) live.webSources else record.webSources,
                        expectedProfile = if (fromForm && keepsExpected && live != null) live.expectedProfile else record.expectedProfile,
                    ),
                )
            }
                .onSuccess { if (!quiet) post(UiText.Res(R.string.msg_saved)) }
                .onFailure { fail(it) }
        }
    }

    /** Adds a photo (back label, receipt…) to a wine, resized on the device. */
    fun addExtraPhoto(wineId: UUID, uri: Uri, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) { photos.prepare(uri)?.let { photos.save(it) } }
            if (id == null) {
                post(UiText.Res(R.string.err_generic))
                onDone()
                return@launch
            }
            repository.update { cellar ->
                val wine = cellar.wines[wineId] ?: return@update
                // The same picture twice has the same ID: it is already there.
                if (id !in wine.extraPhotoIds.orEmpty()) cellar.updateWine(wine.copy(extraPhotoIds = wine.extraPhotoIds.orEmpty() + id))
            }.onFailure { fail(it) }
            onDone()
        }
    }

    /** Removes a photo from the wine, with Undo (the file stays for a while). */
    fun removeExtraPhoto(wineId: UUID, id: String) {
        val index = cellar.value.wines[wineId]?.extraPhotoIds.orEmpty().indexOf(id)
        if (index < 0) return
        change<Unit>({
            post(UiText.Res(R.string.msg_photo_removed)) {
                change<Unit> { c ->
                    val wine = c.wines[wineId] ?: return@change
                    val extras = wine.extraPhotoIds.orEmpty()
                    if (id !in extras) c.updateWine(wine.copy(extraPhotoIds = extras.toMutableList().apply { add(minOf(index, size), id) }))
                }
            }
        }) { cellar ->
            val wine = cellar.wines[wineId] ?: return@change
            cellar.updateWine(wine.copy(extraPhotoIds = wine.extraPhotoIds.orEmpty().filter { it != id }.ifEmpty { null }))
        }
    }

    fun deleteWine(wineId: UUID) {
        val before = cellar.value
        val wine = before.wines[wineId] ?: return
        val bottles = before.bottles.values.filter { it.wineId == wineId }
        val discoveries = before.discoveries.values.filter { it.wineId == wineId }
        change<Unit>({
            post(UiText.Res(R.string.msg_wine_deleted, listOf(wine.displayName))) {
                change<Unit> { c -> c.merge(Cellar(listOf(wine), bottles, discoveries = discoveries)) }
            }
        }) { it.deleteWine(wineId) }
    }

    // endregion

    // region Discoveries

    fun addDiscovery(discovery: Discovery, wine: Wine, photo: ByteArray?) {
        viewModelScope.launch {
            val photoId = photo?.let { withContext(Dispatchers.IO) { photos.save(it) } }
            val record = if (photoId != null) wine.copy(photoId = photoId) else wine
            repository.update { it.addDiscovery(discovery, record) }
                .onSuccess {
                    val filter = journalFilter.value
                    if (filter == DiaryFilter.Cellar || (filter == DiaryFilter.ToBuy && !discovery.wantsToBuy)) journalFilter.value = DiaryFilter.All
                    val tag = journalTag.value
                    if (tag != null && discovery.tags.none { it.equals(tag, ignoreCase = true) }) journalTag.value = null
                    confirm()
                    post(UiText.Res(if (discovery.wantsToBuy) R.string.discovery_added_to_list else R.string.discovery_saved))
                }
                .onFailure { fail(it) }
        }
    }

    fun updateDiscovery(discovery: Discovery, wine: Wine, photo: ByteArray?, removePhoto: Boolean) {
        viewModelScope.launch {
            val newId = photo?.let { withContext(Dispatchers.IO) { photos.save(it) } }
            val record = when {
                newId != null -> wine.copy(photoId = newId)
                removePhoto -> wine.copy(photoId = null)
                else -> wine
            }
            repository.update {
                // Critic scores are edited on the wine page: the live ones are kept, as for the wine form.
                val live = it.wines[record.id]
                it.updateWine(if (live != null) record.copy(attachments = live.attachments, criticScores = live.criticScores) else record)
                it.updateDiscovery(discovery)
            }.onSuccess { post(UiText.Res(R.string.msg_saved)) }
                .onFailure { fail(it) }
        }
    }

    /** Puts a wine tasted elsewhere on the shopping list or takes it off, with Undo. */
    fun toggleToBuy(id: UUID) {
        val before = cellar.value.discoveries[id] ?: return
        val wants = !before.wantsToBuy
        change<Unit>({
            post(UiText.Res(if (wants) R.string.discovery_added_to_list else R.string.discovery_removed_from_list)) {
                change<Unit> { c ->
                    c.discoveries[id]?.let { c.updateDiscovery(it.copy(wantsToBuy = before.wantsToBuy, boughtAt = before.boughtAt)) }
                }
            }
        }) { cellar ->
            val discovery = cellar.discoveries[id] ?: return@change
            cellar.updateDiscovery(discovery.copy(wantsToBuy = wants, boughtAt = if (wants) null else discovery.boughtAt))
        }
    }

    fun deleteDiscovery(id: UUID) {
        val discovery = cellar.value.discoveries[id] ?: return
        val wine = cellar.value.wines[discovery.wineId]
        change<Unit>({
            post(UiText.Res(R.string.discovery_deleted)) {
                change<Unit> { c -> if (wine != null) c.addDiscovery(discovery, wine) }
            }
        }) { it.deleteDiscovery(id) }
    }

    // endregion

    // region Photos and AI label recognition

    /** Resized JPEG of a picked or captured image, or null if it cannot be read. */
    suspend fun preparePhoto(uri: Uri): ByteArray? = withContext(Dispatchers.IO) { photos.prepare(uri) }

    private val readings = MutableStateFlow<Map<UUID, LabelReading>>(emptyMap())

    /** The AI readings of label photos, by form (the wine's id). */
    val labelReadings: StateFlow<Map<UUID, LabelReading>> = readings
    private val readingJobs = mutableMapOf<UUID, kotlinx.coroutines.Job>()

    /** Wines added while the AI was still reading their label: completed with its answer. */
    private val savedWhileReading = mutableMapOf<UUID, Wine>()

    /**
     * Reads a label photo with the AI for the form of wine [formId]. It runs in the view
     * model: rotating or folding the device does not stop it, and if the bottles are added
     * meanwhile, the answer completes the saved wine (with Undo), as on Apple.
     */
    fun startLabelAnalysis(formId: UUID, jpeg: ByteArray, only: String? = null) {
        readingJobs.remove(formId)?.cancel()
        readings.value = readings.value + (formId to LabelReading.Reading)
        readingJobs[formId] = viewModelScope.launch {
            // The photo's ID (its content's hash, as saved): the label frame is for it.
            val reading = analyzeLabel(jpeg, only)
            val result = reading.map { it.analysis.copy(photoId = PhotoId.make(jpeg)) }
            readingJobs.remove(formId)
            val saved = savedWhileReading.remove(formId)
            if (saved != null) {
                readings.value = readings.value - formId
                result.onSuccess { completeWine(saved, it) }.onFailure { post(aiErrorText(it)) }
            } else {
                val done = result.fold(
                    { analysis ->
                        val read = reading.getOrNull()
                        LabelReading.Done(analysis, read?.model, read?.connection)
                    },
                    { LabelReading.Failed(it) },
                )
                readings.value = readings.value + (formId to done)
            }
        }
    }

    /** The form took the answer. */
    fun consumeLabelReading(formId: UUID) {
        if (readings.value[formId] !is LabelReading.Reading) readings.value = readings.value - formId
    }

    /** The form was left: the reading stops, unless its wine was just added (it completes it). */
    fun releaseLabelReading(formId: UUID) {
        if (savedWhileReading.containsKey(formId)) return
        readingJobs.remove(formId)?.cancel()
        readings.value = readings.value - formId
    }

    /** The AI answered after the wine was saved: fills the fields still as they were at saving, with Undo. */
    @androidx.annotation.VisibleForTesting
    internal fun completeWine(saved: Wine, analysis: LabelAnalysis) {
        val completed = analysis.completing(saved)
        viewModelScope.launch {
            var change: Pair<Wine, Wine>? = null
            // A wine the AI's answer makes this one a duplicate of (none matched before: kept apart on purpose).
            var twin: Wine? = null
            repository.update { cellar ->
                val live = cellar.wines[saved.id] ?: return@update
                val updated = live.completed(from = saved, to = completed)
                // Nothing left to fill (changed here or on another device since): not "completed".
                if (!updated.sameDetails(live)) {
                    val matchedBefore = cellar.wine(matching = live)?.id
                    cellar.updateWine(updated)
                    change = live to updated
                    twin = cellar.wine(matching = updated)?.takeIf { it.id != matchedBefore }
                } else if (updated.labelFrame != live.labelFrame) {
                    // Only where the label is: kept quietly for the studio picture.
                    cellar.updateWine(live.copy(labelFrame = updated.labelFrame))
                }
            }.onSuccess {
                val (live, updated) = change ?: return@onSuccess post(UiText.Res(R.string.msg_ai_nothing))
                val revert = { c: Cellar -> c.wines[live.id]?.let { c.updateWine(it.completed(from = updated, to = live)) }; Unit }
                // The bottles' "Added" snackbar still waiting or shown: one snackbar, whose Undo takes both back.
                val add = lastAdd?.takeIf { it.wineId == saved.id && it.slot.replace() }
                if (add == null) {
                    post(UiText.Res(R.string.msg_ai_completed, listOf(updated.displayName))) { change<Unit>(block = revert) }
                } else {
                    lastAdd = null
                    val text = if (add.ordered) R.string.msg_on_order_ai_completed else R.string.msg_added_ai_completed
                    post(UiText.Res(text, listOf(add.count, updated.displayName))) {
                        change<Unit> { c ->
                            revert(c)
                            add.revert(c)
                        }
                    }
                }
                // Then the merge is offered (a snackbar has one action, and the Undo above comes first).
                twin?.let { other -> offerMerge(updated, other) }
            }.onFailure { fail(it) }
        }
    }

    /** The AI made a new wine the same as [other]: offers to put its bottles with [other]'s. */
    private fun offerMerge(wine: Wine, other: Wine) {
        showMessage(UiText.Res(R.string.msg_same_wine, listOf(other.displayName)), UiText.Res(R.string.action_merge)) {
            mergeWine(wine.id, other.id)
        }
    }

    /** The same wine entered twice: the bottles of [source] go to [target], with Undo. */
    fun mergeWine(source: UUID, target: UUID) {
        val before = cellar.value
        val from = before.wines[source] ?: return
        val into = before.wines[target] ?: return
        val bottleIds = before.bottles.values.filter { it.wineId == source }.map { it.id }.toSet()
        val discoveryIds = before.discoveries.values.filter { it.wineId == source }.map { it.id }.toSet()
        change<Unit>({
            post(UiText.Res(R.string.msg_merged, listOf(into.displayName))) {
                change<Unit> { c -> c.unmergeWine(from, into, bottleIds, discoveryIds) }
            }
        }) { it.mergeWine(source, target) }
    }

    /**
     * Sends only the label photo to the models set in Settings, one at a time until one reads
     * it, or to [only]. With the studio look on, the same request also asks where the label
     * is, to frame the studio picture on it.
     */
    suspend fun analyzeLabel(jpeg: ByteArray, only: String? = null): Result<AiReading> {
        val findsLabel = preferences.studioLook.value
        return runCatching { AiService.analyzeLabel(jpeg, ai.configuration.value, ai::apiKey, only, findsLabel, appLanguage) }
    }

    /** The language of the app ("en", "fr"), for the short text of what to expect. */
    val appLanguage: String get() = if (java.util.Locale.getDefault().language == "fr") "fr" else "en"

    /** Wines whose profile the AI is being asked for ("What to expect"). */
    val askingExpected = MutableStateFlow<Set<UUID>>(emptySet())

    /**
     * "What to expect": asks the AI models in order, sending only the wine's identity
     * (producer, name, appellation, vintage, colour, grapes), never its photo, notes, prices
     * or places. The answer is kept with the wine, with Undo.
     */
    fun askWhatToExpect(wineId: UUID, only: String? = null) {
        val wine = cellar.value.wines[wineId] ?: return
        if (wineId in askingExpected.value) return
        askingExpected.value = askingExpected.value + wineId
        viewModelScope.launch {
            val result = runCatching {
                AiService.expectedProfile(WineIdentity(wine), appLanguage, ai.configuration.value, ai::apiKey, only)
            }
            askingExpected.value = askingExpected.value - wineId
            result.onSuccess { (profile, _) ->
                if (profile == null) return@onSuccess post(UiText.Res(R.string.expected_ai_unknown))
                setExpectedProfile(wineId, profile)
            }.onFailure { error -> if (error !is kotlinx.coroutines.CancellationException) post(aiErrorText(error)) }
        }
    }

    /** Wines being completed from the web. */
    val completingFromWeb = MutableStateFlow<Set<UUID>>(emptySet())

    /**
     * "Complete from the web": a model allowed to search the web is sent only the wine's
     * identity; its empty details, what to expect and the pages cited are filled, with Undo.
     * An automatic completion says nothing when it fails or finds nothing.
     */
    fun completeFromWeb(wineId: UUID, automatic: Boolean = false) {
        val wine = cellar.value.wines[wineId] ?: return
        if (wineId in completingFromWeb.value) return
        completingFromWeb.value = completingFromWeb.value + wineId
        viewModelScope.launch {
            val result = runCatching { AiService.completeFromWeb(WineIdentity(wine), appLanguage, ai.configuration.value, ai::apiKey) }
            completingFromWeb.value = completingFromWeb.value - wineId
            result.onFailure { error -> if (!automatic && error !is kotlinx.coroutines.CancellationException) post(aiErrorText(error)) }
            val found = result.getOrNull()
            if (found == null) {
                if (!automatic && result.isSuccess) post(UiText.Res(R.string.ai_err_invalid))
                return@launch
            }
            val (completion, sources) = found
            var change: Pair<Wine, Wine>? = null
            repository.update { cellar ->
                val live = cellar.wines[wineId] ?: return@update
                val completed = completion.completing(live, sources, java.time.Instant.now())
                if (completed != live) {
                    cellar.updateWine(completed)
                    change = live to completed
                }
            }.onSuccess {
                val done = change
                if (done == null) {
                    if (!automatic) post(UiText.Res(R.string.msg_ai_nothing))
                    return@onSuccess
                }
                val (live, completed) = done
                post(UiText.Res(R.string.web_completed)) {
                    change<Unit> { c -> c.wines[wineId]?.let { c.updateWine(it.completedFromWeb(from = completed, to = live)) } }
                }
            }.onFailure { if (!automatic) fail(it) }
        }
    }

    /** Keeps [profile] with the wine (null: back to the bundled one), with Undo. */
    fun setExpectedProfile(wineId: UUID, profile: ExpectedProfile?) {
        val before = cellar.value.wines[wineId]?.expectedProfile
        change<Unit>({
            post(UiText.Res(if (profile == null) R.string.expected_removed else R.string.expected_saved)) {
                change<Unit> { c -> c.wines[wineId]?.let { c.updateWine(it.copy(expectedProfile = before)) } }
            }
        }) { c -> c.wines[wineId]?.let { c.updateWine(it.copy(expectedProfile = profile)) } }
    }

    fun aiErrorText(error: Throwable): UiText = when (error) {
        AiError.NotConfigured -> UiText.Res(R.string.ai_err_not_configured)
        AiError.MissingKey -> UiText.Res(R.string.ai_err_missing_key)
        AiError.NoModel -> UiText.Res(R.string.ai_err_no_model)
        AiError.Refused -> UiText.Res(R.string.ai_err_refused)
        AiError.InvalidResponse -> UiText.Res(R.string.ai_err_invalid)
        AiError.InsecureUrl -> UiText.Res(R.string.search_err_server_url)
        is LocalNetworkException -> UiText.Res(localNetworkErrorText(error))
        is AiError.Server -> if (error.status == 0) UiText.Res(R.string.ai_err_network) else UiText.Res(R.string.ai_err_server, listOf(error.status))
        is AiAllModelsFailed -> {
            val configuration = ai.configuration.value
            val failed = error.failures.map { it.model to aiErrorText(it.error) }
            UiText.Built { resources ->
                fun name(model: AiModelChoice) = configuration.connection(model)?.let { "${model.model} (${it.title(resources)})" } ?: model.model
                (listOf(resources.getString(R.string.ai_err_all_failed)) +
                    failed.map { (model, reason) -> resources.getString(R.string.ai_err_model_line, name(model), reason.resolve(resources)) } +
                    error.untried.map { resources.getString(R.string.ai_err_model_untried, name(it)) }).joinToString("\n")
            }
        }
        else -> UiText.Res(R.string.err_generic)
    }

    /** Checks a connection's key and lists its chat models (through OpenRouter, those that read images). */
    suspend fun loadModels(connection: AiConnection, key: String?): Result<List<String>> = runCatching { AiService.models(connection, key) }

    /** Checks a model with a made-up label bundled with the app (res/raw/ai_test_label.jpg), never a photo of the user. */
    suspend fun testModel(model: AiModelChoice, resources: android.content.res.Resources): Result<Unit> = runCatching {
        val connection = ai.configuration.value.connection(model) ?: throw AiError.NotConfigured
        val image = withContext(Dispatchers.IO) { resources.openRawResource(R.raw.ai_test_label).use { it.readBytes() } }
        val key = withContext(Dispatchers.IO) { ai.apiKey(connection) }
        AiService.test(connection, model.model, key, image)
    }

    fun showMessage(text: UiText) = post(text)

    /** A message with an action other than Undo, such as Open settings. */
    fun showMessage(text: UiText, actionLabel: UiText, action: () -> Unit) {
        messageChannel.trySend(UiMessage(text, action, actionLabel = actionLabel))
    }

    // region Documents

    // Document files are never deleted right away either: an Undo, or another device that
    // has not synced yet, may refer to them again (DocumentStore.removeOrphans, at launch).

    /** Copies picked files to a temporary place, checking their size; problems are reported. */
    suspend fun stageDocuments(uris: List<Uri>): List<PendingDocument> {
        val problems = mutableSetOf<DocumentStore.Problem>()
        val staged = withContext(Dispatchers.IO) {
            uris.mapNotNull { uri ->
                try {
                    documents.stage(uri)
                } catch (error: DocumentStore.AddException) {
                    problems += error.problem
                    null
                }
            }
        }
        reportDocumentProblems(problems)
        return staged
    }

    /** A photo of a paper (taken with the camera app) as an image document, resized like photos. */
    suspend fun stagePhotoOfPaper(uri: Uri): PendingDocument? {
        val jpeg = withContext(Dispatchers.IO) { photos.prepare(uri, PAPER_PIXEL_SIZE) }
        if (jpeg == null) {
            post(UiText.Res(R.string.err_document_unreadable))
            return null
        }
        return try {
            withContext(Dispatchers.IO) { documents.stagePhoto(jpeg) }
        } catch (error: DocumentStore.AddException) {
            reportDocumentProblems(setOf(error.problem))
            null
        }
    }

    private fun reportDocumentProblems(problems: Set<DocumentStore.Problem>) {
        when {
            DocumentStore.Problem.TooLarge in problems -> post(UiText.Res(R.string.err_document_too_large))
            DocumentStore.Problem.Unreadable in problems -> post(UiText.Res(R.string.err_document_unreadable))
        }
    }

    /** Stores staged documents and attaches them to a wine (for all its bottles) or a bottle. */
    fun attachDocuments(target: AttachmentTarget, staged: List<PendingDocument>, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val attachments = withContext(Dispatchers.IO) {
                staged.mapNotNull { documents.commit(it) }.also { staged.forEach(documents::discard) }
            }
            if (attachments.size < staged.size) post(UiText.Res(R.string.err_document_unreadable))
            if (attachments.isNotEmpty()) {
                repository.update { it.attach(attachments, target) }.onSuccess { confirm() }.onFailure { fail(it) }
            }
            onDone()
        }
    }

    fun discardDocuments(staged: List<PendingDocument>) {
        viewModelScope.launch(Dispatchers.IO) { staged.forEach(documents::discard) }
    }

    fun renameDocument(target: AttachmentTarget, id: String, name: String) = change<Unit> { it.renameAttachment(id, target, name) }

    /** Removes a document, with Undo (the file stays for a while). */
    fun removeDocument(target: AttachmentTarget, id: String) {
        change<Pair<Attachment, Int>?>({ removed ->
            if (removed != null) {
                post(UiText.Res(R.string.msg_document_removed)) {
                    change<Unit> { c -> c.attach(listOf(removed.first), target, removed.second) }
                }
            }
        }) { it.removeAttachment(id, target) }
    }

    /** A copy of the document with its name, for another app to open or receive; null if it is not here. */
    suspend fun documentUri(attachment: Attachment): Uri? = withContext(Dispatchers.IO) { documents.sharedUri(attachment) }

    // endregion

    // endregion

    // region Racks

    /** Adds a storage; [onDone] says whether it was saved (on failure the error is shown). */
    fun addRack(rack: Rack, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            repository.update { it.addRack(rack) }.onSuccess { onDone(true) }.onFailure {
                fail(it)
                onDone(false)
            }
        }
    }

    /**
     * Saves a rack, moving its bottles by [rowMap] (see Cellar.updateRack). With [takingOut]
     * the bottles in removed places leave the rack, with a long Undo that puts the rack and
     * the bottles back as they were. Calls [onDone] only when the rack was saved.
     */
    fun updateRack(
        rack: Rack,
        rowMap: Map<Int, Int?> = emptyMap(),
        takingOut: Boolean = false,
        cellMap: Map<RackCell, RackCell?> = emptyMap(),
        onDone: () -> Unit,
    ) {
        val before = cellar.value
        val old = before.rack(rack.id)
        val bottles = before.bottlesInCellar.filter { it.slot?.rackId == rack.id }
        val displaced = before.displacedBottles(rack, rowMap, cellMap).size
        change<Unit>({
            onDone()
            if (takingOut && displaced > 0 && old != null) {
                post(UiText.Res(R.string.msg_bottles_taken_out, listOf(displaced)), long = true) {
                    // The bottles in the rack now go back with their rows, those taken out to their
                    // place if still free: what was placed, moved or opened since stays.
                    change<Unit> { c -> c.undoRackUpdate(old, rowMap, bottles, cellMap) }
                }
            }
        }) { it.updateRack(rack, rowMap, takingOut, cellMap) }
    }

    /**
     * Deletes a rack; its bottles stay in the cellar without a place. The long Undo puts the
     * rack back where it was in the list, and its bottles in the places still free.
     */
    fun removeRack(rackId: UUID) {
        val before = cellar.value
        val index = before.racks.indexOfFirst { it.id == rackId }
        if (index < 0) return
        val rack = before.racks[index]
        val placed = before.bottlesInCellar.filter { it.slot?.rackId == rackId }.map { it.id to it.slot!! }
        change<Unit>({
            post(UiText.Res(R.string.msg_rack_deleted, listOf(rack.name)), long = true) {
                change<Unit> { c ->
                    if (c.rack(rack.id) != null) return@change
                    c.addRack(rack)
                    val at = c.racks.size - 1
                    if (index < at) c.moveRacks(setOf(at), index)
                    placed.forEach { (id, slot) ->
                        val bottle = c.bottles[id] ?: return@forEach
                        if (bottle.status == Bottle.Status.InCellar && bottle.slot == null && c.bottleAt(slot) == null) c.move(id, slot)
                    }
                }
            }
        }) { it.removeRack(rackId) }
    }

    fun moveRack(from: Int, to: Int) = change<Unit> {
        // `to` is the final index; moveRacks takes SwiftUI's "insert before" offset.
        it.moveRacks(setOf(from), if (to > from) to + 1 else to)
    }

    // endregion

    // region Settings and data

    fun setCurrency(code: String) = change<Unit> { it.settings = it.settings.copy(currency = code) }

    fun setDynamicColor(enabled: Boolean) = preferences.setDynamicColor(enabled)

    fun setStudioLook(enabled: Boolean) = preferences.setStudioLook(enabled)

    /** The sample can be loaded only into an empty cellar that no other device shares. */
    fun sampleAllowed(cellar: Cellar, shared: Boolean): Boolean = cellar.isEmpty && !shared

    /**
     * Offered on the empty screens and in Settings in beta builds only; store builds offer it in
     * Help only (Getting started › Try with a sample cellar).
     */
    fun canLoadSample(cellar: Cellar, shared: Boolean): Boolean = BuildConfig.BETA && sampleAllowed(cellar, shared)

    /** Fills an empty cellar with the sample (never replaces bottles, nor reaches other devices); Undo removes it. */
    fun loadSample(labels: SampleData.Labels) {
        if (!sampleAllowed(cellar.value, sharedWithDevices.value)) return
        change<Boolean>({ loaded -> if (loaded) post(UiText.Res(R.string.msg_sample_loaded)) { removeSample(announce = false) } }) { c ->
            val empty = c.isEmpty
            if (empty) c.merge(SampleData.cellar(year, labels))
            empty
        }
    }

    /**
     * Removes the sample's racks, wines, bottles and Journal entries, and only them (the
     * user's records stay), with Undo unless [announce] is false (the Undo of loading it).
     */
    fun removeSample(announce: Boolean = true) {
        viewModelScope.launch {
            val before = cellar.value.copy()
            if (!before.containsSample) return@launch
            repository.replace(before.removingSample())
                .onSuccess { if (announce) post(UiText.Res(R.string.msg_sample_removed)) { change<Unit> { c -> c.merge(before) } } }
                .onFailure { fail(it) }
        }
    }

    /**
     * Erases the bottles, wines, racks, tastings and discoveries (on the other devices too,
     * when they sync with this one); the settings are kept. Undo puts them back. [alsoBackups]:
     * once the empty cellar is saved, the previous versions, the weekly archives and the cellar
     * files kept aside go too ([BackupManager.eraseBackups]).
     */
    fun eraseAll(alsoBackups: Boolean = false) {
        viewModelScope.launch {
            // The previous versions stay: the cellar as it is now is one of them first, as before
            // any Replace, or nothing is erased.
            if (!alsoBackups && !backups.keepBeforeReplacing(cellar.value.copy())) {
                post(UiText.Res(R.string.err_version_not_kept))
                return@launch
            }
            val before = cellar.value.copy()
            repository.replace(Cellar(settings = before.settings))
                .onSuccess {
                    if (alsoBackups) {
                        backups.eraseBackups()
                        // The photos and documents go too, once the Undo is gone (or at the next launch).
                        val refs = cellar.value.fileRefs
                        withContext(Dispatchers.IO) {
                            repository.keptAside().forEach { it.delete() }
                            photos.expireOrphans(refs.filter { it.kind == FileKind.Photo }.map { it.id }.toSet())
                            documents.expireOrphans(refs.filter { it.kind == FileKind.Document }.map { it.id }.toSet())
                        }
                    }
                    val undo = {
                        // Their files are referred to again: no longer due.
                        change<Unit>({ if (alsoBackups) removeUnreferencedFiles() }) { c -> c.merge(before) }
                    }
                    messageChannel.trySend(UiMessage(UiText.Res(R.string.msg_erased), undo, undoGone = if (alsoBackups) ::removeUnreferencedFiles else null))
                }
                .onFailure { fail(it) }
        }
    }

    /**
     * Removes the photo and document files nothing has referred to for a while, or made due by
     * Erase all data; those of the previous versions are kept too.
     */
    private fun removeUnreferencedFiles() {
        val refs = cellar.value.fileRefs
        viewModelScope.launch(Dispatchers.IO) {
            val kept = refs + ((backups.versions as? CellarVersionFolder)?.fileRefs() ?: emptySet())
            photos.removeOrphans(kept.filter { it.kind == FileKind.Photo }.map { it.id }.toSet())
            documents.removeOrphans(kept.filter { it.kind == FileKind.Document }.map { it.id }.toSet())
        }
    }

    /** Cellar files that could not be read at launch and were kept aside. */
    fun keptAside(): List<java.io.File> = repository.keptAside()

    /** Copies a kept-aside cellar file, as it is, to a file the user chose. */
    fun exportKeptAside(resolver: ContentResolver, uri: Uri, file: java.io.File) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { requireNotNull(resolver.openOutputStream(uri, "wt")).use { out -> file.inputStream().use { it.copyTo(out) } } }
            }
            post(UiText.Res(if (result.isSuccess) R.string.msg_exported else R.string.msg_export_failed))
        }
    }

    /** Deletes a kept-aside cellar file (photo cleanup resumes once none is left). */
    fun deleteKeptAside(file: java.io.File, onDone: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { file.delete() }
            onDone()
        }
    }

    // region Backups and restore

    private val restoreState = MutableStateFlow<RestoreSource?>(null)

    /** The backup or version shown before restoring it (RestorePreviewScreen). */
    val pendingRestore: StateFlow<RestoreSource?> = restoreState

    private val reading = MutableStateFlow(false)

    /** A backup the user picked is being read (a large archive takes a while). */
    val readingBackup: StateFlow<Boolean> = reading

    /** Shows [source] in the preview; the files of one shown before and not restored go. */
    private fun showRestore(source: RestoreSource) {
        val previous = restoreState.value
        restoreState.value = source
        if (previous != null && previous.workingFolder != source.workingFolder) discardFiles(previous)
    }

    private fun discardFiles(source: RestoreSource) {
        source.workingFolder?.let { folder -> viewModelScope.launch(Dispatchers.IO) { folder.deleteRecursively() } }
    }

    /** The preview was left without restoring: the files read from the backup go. */
    fun discardRestore() {
        val source = restoreState.value ?: return
        restoreState.value = null
        discardFiles(source)
    }

    /**
     * Back up now: the archive written to a file the user chose. A file left half written (no
     * space, the drive removed) is deleted, so it is never taken for a backup.
     */
    fun backUpNow(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                val output = withContext(Dispatchers.IO) { requireNotNull(resolver.openOutputStream(uri, "wt")) }
                // Closed by writeArchive: it is a backup only once the file is complete.
                backups.writeArchive(output).getOrThrow()
            }
            result.onSuccess { written ->
                val notIncluded = written.missing + written.leftOut
                post(if (notIncluded > 0) UiText.Plural(R.plurals.msg_backup_saved_without, notIncluded) else UiText.Res(R.string.msg_backup_saved))
            }.onFailure {
                withContext(Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(resolver, uri) } }
                post(UiText.Res(R.string.err_backup_failed))
            }
        }
    }

    /** Reads an earlier version, then [onReady] opens its preview. */
    fun openVersion(version: CellarVersion, onReady: () -> Unit) {
        viewModelScope.launch {
            runCatching { backups.source(version) }
                .onSuccess { showRestore(it); onReady() }
                .onFailure { post(UiText.Res(R.string.err_version_unreadable)) }
        }
    }

    /** The newest earlier version (after a cellar file that could not be read), then [onReady]. */
    fun openLatestVersion(onReady: () -> Unit) {
        viewModelScope.launch {
            val source = backups.latestVersion()
            if (source == null) {
                post(UiText.Res(R.string.err_version_unreadable))
            } else {
                showRestore(source)
                onReady()
            }
        }
    }

    /**
     * Restores the backup or version shown, with Undo. An archive's photos and documents are
     * copied first. Replace is a change made here like any other: sync takes it to the other
     * devices (what is not in the backup is deleted there too). [onDone] tells whether it was
     * restored.
     */
    fun restore(mode: RestoreMode, onDone: (Boolean) -> Unit) {
        val source = restoreState.value ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { backups.install(source.files) }
            // Replace: the cellar as it is now is a previous version first, or it is not replaced.
            if (mode == RestoreMode.Replace && !backups.keepBeforeReplacing(cellar.value.copy())) {
                post(UiText.Res(R.string.err_version_not_kept))
                onDone(false)
                return@launch
            }
            // Under the repository lock: a sync that lands meanwhile is kept, not reverted.
            repository.replaceWith { current -> current.restoring(source.cellar, mode) }
                .onSuccess { (before, restored) ->
                    val summary = before.copy().merge(source.cellar)
                    source.workingFolder?.let { folder -> withContext(Dispatchers.IO) { folder.deleteRecursively() } }
                    restoreState.value = null
                    confirm()
                    val text = if (mode == RestoreMode.Replace) UiText.Res(R.string.msg_restored) else importText(summary)
                    val undo: (() -> Unit)? = if (restored == before) null else ({ undoRestore(before, restored) })
                    messageChannel.trySend(UiMessage(text, undo))
                    onDone(true)
                }
                .onFailure {
                    fail(it)
                    onDone(false)
                }
        }
    }

    /** Puts back the cellar as it was before a restore, unless it changed since (here or by sync). */
    private fun undoRestore(before: Cellar, restored: Cellar) {
        viewModelScope.launch {
            repository.replaceIf(restored, before)
                .onSuccess { undone -> if (!undone) post(UiText.Res(R.string.msg_undo_restore_too_late)) }
                .onFailure { fail(it) }
        }
    }

    // endregion

    /** The spreadsheet; its "location" column names positions in the language of [resources]. */
    fun exportCsv(resolver: ContentResolver, uri: Uri, resources: android.content.res.Resources) = export(resolver, uri) {
        CellarExport.csv(it, placeName = { rack, slot -> rack.displayLabel(slot, resources) })
    }

    /** The printable inventory (PDF, A4); labels come from [resources]. */
    fun exportPdf(resolver: ContentResolver, uri: Uri, resources: android.content.res.Resources) {
        val snapshot = cellar.value
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    requireNotNull(resolver.openOutputStream(uri, "wt")).use {
                        app.encave.ui.settings.InventoryPdf.write(snapshot, resources, year, it)
                    }
                }
            }
            post(UiText.Res(if (result.isSuccess) R.string.msg_exported else R.string.msg_export_failed))
        }
    }

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

    /**
     * Imports a file: an Encave archive (ZIP) or JSON backup is read and shown first, by
     * [onBackup], to add what is missing or replace the cellar; a CSV file (one row per bottle)
     * is added right away.
     */
    fun import(resolver: ContentResolver, uri: Uri, onBackup: () -> Unit = {}) {
        viewModelScope.launch {
            val head = withContext(Dispatchers.IO) {
                runCatching { requireNotNull(resolver.openInputStream(uri)).use { it.readNBytes(64) } }
            }.getOrElse {
                post(UiText.Res(R.string.err_generic))
                return@launch
            }
            val isZip = head.size >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() && head[2] == 3.toByte() && head[3] == 4.toByte()
            val isJson = head.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t').startsWith("{")
            if (isZip || isJson) {
                reading.value = true
                try {
                    runCatching { backups.source(resolver, uri) }
                        .onSuccess { showRestore(it); onBackup() }
                        // Reading, not saving, failed: not "could not be saved".
                        .onFailure { post(if (it is java.io.IOException) UiText.Res(R.string.err_document_unreadable) else errorText(it)) }
                } finally {
                    reading.value = false
                }
                return@launch
            }
            val text = withContext(Dispatchers.IO) {
                // Excel's and CellarTracker's files are often Windows-1252, not UTF-8.
                runCatching { requireNotNull(resolver.openInputStream(uri)).use { CsvText.decode(it.readBytes()) } }
            }.getOrElse {
                post(UiText.Res(R.string.err_generic))
                return@launch
            }
            repository.update { cellar -> cellar.importCsv(text) }
                .onSuccess { post(importText(it)) }
                .onFailure { fail(it) }
        }
    }

    private fun importText(summary: ImportSummary): UiText = if (summary.discoveriesAdded > 0) {
        UiText.Res(R.string.msg_imported_elsewhere, listOf(summary.discoveriesAdded))
    } else if (summary.rowsSkipped > 0) {
        UiText.Res(R.string.msg_imported_skipped, listOf(summary.winesAdded, summary.bottlesAdded, summary.rowsSkipped))
    } else {
        UiText.Res(R.string.msg_imported, listOf(summary.winesAdded, summary.bottlesAdded))
    }

    // endregion

    companion object {
        /** Photos of papers keep more pixels than label photos, so small print stays legible. */
        const val PAPER_PIXEL_SIZE = 2400

        fun factory(app: EncaveApplication): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val shared = app.sync.state.map { (it.membership?.active?.size ?: 0) > 1 }
                val updates = app.sync.changesFromOthers.map { summary ->
                    val name = ChangeNotifications.deviceName(summary, app.sync.state.value.membership)
                    if (name != null) UiText.Res(R.string.sync_updated_from, listOf(name)) else UiText.Res(R.string.sync_updated_from_others)
                }
                AppViewModel(app.repository, app.preferences, app.photos, app.documents, app.ai, app.backups, shared, updates)
            }
        }
    }
}
