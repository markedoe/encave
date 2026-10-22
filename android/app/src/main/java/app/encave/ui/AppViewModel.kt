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
import app.encave.core.Attachment
import app.encave.core.AttachmentTarget
import app.encave.core.Bottle
import app.encave.core.Cellar
import app.encave.core.CellarError
import app.encave.core.CellarException
import app.encave.core.CellarExport
import app.encave.core.CellarFilter
import app.encave.core.CellarSort
import app.encave.core.Discovery
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
import app.encave.core.SampleData
import app.encave.core.Slot
import app.encave.core.Tasting
import app.encave.core.Wine
import app.encave.core.importCsv
import app.encave.core.merge
import app.encave.core.CellarVersion
import app.encave.core.RestoreMode
import app.encave.core.restoring
import app.encave.data.BackupManager
import app.encave.data.CellarRepository
import app.encave.data.RestoreSource
import app.encave.data.DocumentStore
import app.encave.data.PendingDocument
import app.encave.data.PhotoStore
import app.encave.data.Preferences
import app.encave.data.ai.AiError
import app.encave.data.ai.AiPreferences
import app.encave.data.ai.AiService
import app.encave.data.ai.LabelAnalysis
import app.encave.sync.ChangeNotifications
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
}

/** A snackbar message, with an optional undo; [persistent] ones stay until dismissed. */
data class UiMessage(val text: UiText, val undo: (() -> Unit)? = null, val persistent: Boolean = false)

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

    val year: Int get() = LocalDate.now().year

    /** Cellar list search, filters and sort; kept while moving between sections. */
    val filter = MutableStateFlow(CellarFilter())
    val sort = MutableStateFlow(CellarSort.Name)

    /** What the Journal shows; back to everything when a new entry would be hidden. */
    val journalFilter = MutableStateFlow(DiaryFilter.All)

    /** The Journal's tag chip; cleared, like the filter, when a new entry would be hidden. */
    val journalTag = MutableStateFlow<String?>(null)

    val groups: StateFlow<List<WineGroup>> = combine(cellar, filter, sort) { cellar, filter, sort ->
        cellar.groups(filter, sort, year)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
                CellarError.NotReceived -> R.string.err_not_received
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
            repository.update(block).onSuccess(onSuccess).onFailure { post(errorText(it)) }
        }
    }

    private fun nameOf(cellar: Cellar, bottleId: UUID): String {
        val wineId = cellar.bottles[bottleId]?.wineId
        return wineId?.let { cellar.wines[it]?.displayName } ?: ""
    }

    // region Bottles

    /** [photo] is a prepared JPEG for a new wine; it is saved before the bottles are added. */
    fun addBottles(
        wine: Wine,
        quantity: Int,
        slots: List<Slot>,
        purchase: PurchaseInfo,
        photo: ByteArray? = null,
        boughtDiscoveryId: UUID? = null,
        /** Documents picked while adding: stored and attached to the wine, for all its bottles. */
        pendingDocuments: List<PendingDocument> = emptyList(),
    ) {
        viewModelScope.launch {
            val photoId = photo?.let { withContext(Dispatchers.IO) { photos.save(it) } }
            val attachments = withContext(Dispatchers.IO) { pendingDocuments.mapNotNull { documents.commit(it) } }
            var record = if (photoId != null) wine.copy(photoId = photoId) else wine
            if (attachments.isNotEmpty()) record = record.copy(attachments = record.attachments.orEmpty() + attachments)
            repository.update { cellar ->
                cellar.addBottles(record, quantity, slots, purchase).also {
                    if (boughtDiscoveryId != null && cellar.discoveries[boughtDiscoveryId] != null) cellar.markBought(boughtDiscoveryId)
                }
            }
                .onSuccess {
                    withContext(Dispatchers.IO) { pendingDocuments.forEach(documents::discard) }
                    if (purchase.isOrdered) post(UiText.Res(R.string.msg_on_order, listOf(it.size, record.displayName)))
                    else post(UiText.Plural(R.plurals.bottles_added, it.size))
                    // Added without the documents that could not be stored (a full disk…).
                    if (attachments.size < pendingDocuments.size) post(UiText.Res(R.string.err_document_unreadable))
                }
                .onFailure { post(errorText(it)) }
        }
    }

    fun drink(bottleId: UUID, tasting: Tasting) {
        val previous = cellar.value.bottles[bottleId]?.slot
        if (!journalFilter.value.showsTastings) journalFilter.value = DiaryFilter.All
        // A tag only picks wines tasted elsewhere.
        journalTag.value = null
        change<String>({ name -> post(UiText.Res(R.string.msg_drunk, listOf(name))) { undoDrink(bottleId, previous) } }) {
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

    fun markGone(bottleId: UUID) {
        val previous = cellar.value.bottles[bottleId]?.slot
        change<String>({ name -> post(UiText.Res(R.string.msg_gone, listOf(name))) { undoDrink(bottleId, previous) } }) {
            it.markGone(bottleId)
            nameOf(it, bottleId)
        }
    }

    /** Bottles on order arrive: they join the cellar without a place. Undo puts the orders back. */
    fun receive(bottleIds: List<UUID>) {
        val before = bottleIds.mapNotNull { cellar.value.bottles[it] }
        if (before.isEmpty()) return
        change<Unit>({ post(UiText.Res(R.string.msg_received, listOf(before.size))) { restore(before) } }) {
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
        if (before.isEmpty()) return
        val name = occasion?.trim().orEmpty()
        change<Unit>({
            val text = if (name.isEmpty()) UiText.Res(R.string.msg_reservation_removed) else UiText.Res(R.string.reserved_for, listOf(name))
            post(text) { change<Unit> { c -> before.forEach { b -> c.bottles[b.id]?.let { c.updateBottle(it.copy(reservedFor = b.reservedFor)) } } } }
        }) { it.reserve(before.map(Bottle::id), occasion) }
    }

    fun saveSearch(name: String, filter: CellarFilter) = change<Unit> { it.saveSearch(name, filter) }

    fun deleteSearch(id: UUID) = change<Unit> { it.deleteSearch(id) }

    // region Several wines or bottles at once (one Undo for all)

    private fun bottlesOfWines(wineIds: Set<UUID>) = cellar.value.bottles.values.filter { it.wineId in wineIds }

    fun placeWines(wineIds: Set<UUID>, rackId: UUID) {
        val before = bottlesOfWines(wineIds)
        change<Int>({ placed ->
            if (placed == 0) post(UiText.Res(R.string.msg_no_free_place))
            else post(UiText.Res(R.string.msg_bottles_placed, listOf(placed))) { restore(before) }
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

    fun move(bottleId: UUID, slot: Slot?) = change<String?>({ label -> label?.let { post(UiText.Res(R.string.msg_moved, listOf(it))) } }) {
        it.move(bottleId, slot)
        slot?.let { s -> it.rack(s.rackId)?.let { rack -> "${rack.name} · ${rack.label(s)}" } }
    }

    fun swap(a: Slot, b: Slot) = change<Unit> { it.swap(a, b) }

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
                .onFailure { post(errorText(it)) }
        }
    }

    /** [photo] replaces the wine's photo when set; [removePhoto] clears it. */
    fun updateWine(wine: Wine, photo: ByteArray? = null, removePhoto: Boolean = false, quiet: Boolean = false) {
        viewModelScope.launch {
            val newId = photo?.let { withContext(Dispatchers.IO) { photos.save(it) } }
            val record = when {
                newId != null -> wine.copy(photoId = newId)
                removePhoto -> wine.copy(photoId = null)
                else -> wine
            }
            // The form does not show documents: those of the wine as it is now are kept
            // (added meanwhile on another device, for example).
            repository.update { c -> c.updateWine(record.copy(attachments = c.wines[record.id]?.attachments ?: record.attachments)) }
                .onSuccess { if (!quiet) post(UiText.Res(R.string.msg_saved)) }
                .onFailure { post(errorText(it)) }
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
            }.onFailure { post(errorText(it)) }
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
                    post(UiText.Res(if (discovery.wantsToBuy) R.string.discovery_added_to_list else R.string.discovery_saved))
                }
                .onFailure { post(errorText(it)) }
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
                it.updateWine(record)
                it.updateDiscovery(discovery)
            }.onSuccess { post(UiText.Res(R.string.msg_saved)) }
                .onFailure { post(errorText(it)) }
        }
    }

    fun toggleToBuy(id: UUID) = change<Unit> { cellar ->
        val discovery = cellar.discoveries[id] ?: return@change
        val wants = !discovery.wantsToBuy
        cellar.updateDiscovery(discovery.copy(wantsToBuy = wants, boughtAt = if (wants) null else discovery.boughtAt))
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

    /** Sends only the label photo to the provider set in Settings. */
    suspend fun analyzeLabel(jpeg: ByteArray): Result<LabelAnalysis> {
        val settings = ai.settings.value
        val key = withContext(Dispatchers.IO) { ai.apiKey(settings.provider) }
        return runCatching { AiService.analyzeLabel(jpeg, settings, key) }
    }

    fun aiErrorText(error: Throwable): UiText = when (error) {
        AiError.NotConfigured -> UiText.Res(R.string.ai_err_not_configured)
        AiError.MissingKey -> UiText.Res(R.string.ai_err_missing_key)
        AiError.NoModel -> UiText.Res(R.string.ai_err_no_model)
        AiError.Refused -> UiText.Res(R.string.ai_err_refused)
        AiError.InvalidResponse -> UiText.Res(R.string.ai_err_invalid)
        AiError.InsecureUrl -> UiText.Res(R.string.search_err_server_url)
        is AiError.Server -> if (error.status == 0) UiText.Res(R.string.ai_err_network) else UiText.Res(R.string.ai_err_server, listOf(error.status))
        else -> UiText.Res(R.string.err_generic)
    }

    /** Checks the key of the current provider and lists its chat models. */
    suspend fun loadModels(key: String?): Result<List<String>> = runCatching { AiService.models(ai.settings.value, key) }

    fun showMessage(text: UiText) = post(text)

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
                repository.update { it.attach(attachments, target) }.onFailure { post(errorText(it)) }
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

    fun setStudioLook(enabled: Boolean) = preferences.setStudioLook(enabled)

    /** The sample is offered only on an empty cellar that no other device shares. */
    fun canLoadSample(cellar: Cellar, shared: Boolean): Boolean = cellar.isEmpty && !shared

    /** Fills an empty cellar with the sample (never replaces bottles, nor reaches other devices). */
    fun loadSample(labels: SampleData.Labels) {
        if (!canLoadSample(cellar.value, sharedWithDevices.value)) return
        change<Boolean>({ loaded -> if (loaded) post(UiText.Res(R.string.msg_sample_loaded)) }) { c ->
            val empty = c.isEmpty
            if (empty) c.merge(SampleData.cellar(year, labels))
            empty
        }
    }

    /**
     * Erases the bottles, wines, racks, tastings and discoveries (on the other devices too,
     * when they sync with this one); the settings are kept. Undo puts them back.
     */
    fun eraseAll() {
        viewModelScope.launch {
            val before = cellar.value.copy()
            repository.replace(Cellar(settings = before.settings))
                .onSuccess { post(UiText.Res(R.string.msg_erased)) { change<Unit> { c -> c.merge(before) } } }
                .onFailure { post(errorText(it)) }
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

    /** Back up now: the archive written to a file the user chose. */
    fun backUpNow(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                val output = withContext(Dispatchers.IO) { requireNotNull(resolver.openOutputStream(uri, "wt")) }
                output.use { backups.writeArchive(it).getOrThrow() }
            }
            post(UiText.Res(if (result.isSuccess) R.string.msg_backup_saved else R.string.err_backup_failed))
        }
    }

    /** Reads an earlier version, then [onReady] opens its preview. */
    fun openVersion(version: CellarVersion, onReady: () -> Unit) {
        viewModelScope.launch {
            runCatching { backups.source(version) }
                .onSuccess { restoreState.value = it; onReady() }
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
                restoreState.value = source
                onReady()
            }
        }
    }

    /**
     * Restores the backup or version shown, with Undo. An archive's photos and documents are
     * copied first. Replace is a change made here like any other: sync takes it to the other
     * devices (what is not in the backup is deleted there too).
     */
    fun restore(mode: RestoreMode, onDone: () -> Unit) {
        val source = restoreState.value ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { backups.install(source.files) }
            val before = cellar.value.copy()
            val restored = before.restoring(source.cellar, mode)
            val summary = before.copy().merge(source.cellar)
            repository.replace(restored)
                .onSuccess {
                    source.workingFolder?.let { folder -> withContext(Dispatchers.IO) { folder.deleteRecursively() } }
                    restoreState.value = null
                    val text = if (mode == RestoreMode.Replace) UiText.Res(R.string.msg_restored) else importText(summary)
                    val undo: (() -> Unit)? = if (restored == before) null else ({ undoRestore(before) })
                    messageChannel.trySend(UiMessage(text, undo))
                    onDone()
                }
                .onFailure { post(errorText(it)) }
        }
    }

    private fun undoRestore(before: Cellar) {
        viewModelScope.launch { repository.replace(before).onFailure { post(errorText(it)) } }
    }

    // endregion

    fun exportCsv(resolver: ContentResolver, uri: Uri) = export(resolver, uri) { CellarExport.csv(it) }

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
                runCatching { backups.source(resolver, uri) }
                    .onSuccess { restoreState.value = it; onBackup() }
                    .onFailure { post(errorText(it)) }
                return@launch
            }
            val text = withContext(Dispatchers.IO) {
                runCatching { requireNotNull(resolver.openInputStream(uri)).use { it.readBytes().toString(Charsets.UTF_8) } }
            }.getOrElse {
                post(UiText.Res(R.string.err_generic))
                return@launch
            }
            repository.update { cellar -> cellar.importCsv(text) }
                .onSuccess { post(importText(it)) }
                .onFailure { post(errorText(it)) }
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
