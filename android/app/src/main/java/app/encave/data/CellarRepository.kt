package app.encave.data

import app.encave.core.Cellar
import app.encave.core.CellarStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Holds the cellar for the UI and persists every change.
 *
 * Each change runs on a copy, which is saved before it is published: on failure
 * nothing changes, and the published instance is never mutated.
 */
class CellarRepository(
    private val store: CellarStore,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val state = MutableStateFlow(Cellar())
    private val loadedState = MutableStateFlow(false)
    private val mutex = Mutex()

    val cellar: StateFlow<Cellar> = state.asStateFlow()

    /** False until the file has been read, so the UI does not flash the empty state. */
    val loaded: StateFlow<Boolean> = loadedState.asStateFlow()

    /**
     * Set when the stored cellar could not be read at launch and could not be moved aside
     * either: the cellar shown is not the stored one, and nothing is saved over the file.
     */
    @Volatile var loadFailed: Boolean = false
        private set

    /**
     * The name the unreadable cellar file was kept under (next to the cellar file). Encave
     * then starts with an empty cellar; the file is never overwritten.
     */
    @Volatile var setAsideName: String? = null
        private set

    init {
        scope.launch {
            mutex.withLock {
                val result = withContext(io) { runCatching { store.load() } }
                result.onSuccess { state.value = it }.onFailure {
                    // Moved aside before anything can save, while the lock is held.
                    setAsideName = withContext(io) { runCatching { store.setAside() }.getOrNull() }
                    loadFailed = setAsideName == null
                }
                loadedState.value = true
            }
        }
    }

    /** Cellar files that could not be read and were kept aside, oldest first. */
    fun keptAside(): List<java.io.File> = store.keptAside()

    private fun refused(): Result<Nothing> = Result.failure(java.io.IOException("The cellar file could not be read and is kept as it is."))

    /**
     * Told of every change made on this device (not of changes received from other
     * devices), with the cellar before and after, while the change still holds the
     * lock: sync records what changed. Called off the main thread.
     */
    @Volatile var onLocalChange: ((old: Cellar, new: Cellar) -> Unit)? = null

    /** Applies [change] to a copy, saves it, then publishes it. */
    suspend fun <T> update(change: (Cellar) -> T): Result<T> = mutex.withLock {
        if (loadFailed) return@withLock refused()
        val before = state.value
        val copy = before.copy()
        val result = runCatching { change(copy) }
        if (result.isFailure) return@withLock result
        val saved = withContext(io) { runCatching { store.save(copy) } }
        saved.exceptionOrNull()?.let { return@withLock Result.failure(it) }
        state.value = copy
        onLocalChange?.let { record -> withContext(io) { record(before, copy) } }
        result
    }

    /** Replaces the whole cellar (sample data, erase all, and their undo). */
    suspend fun replace(cellar: Cellar): Result<Unit> = mutex.withLock {
        if (loadFailed) return@withLock refused()
        val before = state.value
        val copy = cellar.copy()
        withContext(io) { runCatching { store.save(copy) } }.onSuccess {
            state.value = copy
            onLocalChange?.let { record -> withContext(io) { record(before, copy) } }
        }
    }

    /**
     * Applies what came from another device: [merge] gets the current cellar and
     * returns the new one, off the main thread. Not reported to [onLocalChange].
     * [onSaved] runs once the result is saved, still under the lock (sync keeps its
     * stamps in step with the saved cellar); nothing changes if saving fails.
     */
    suspend fun applySynced(merge: (Cellar) -> Cellar, onSaved: () -> Unit = {}): Result<Unit> = mutex.withLock {
        if (loadFailed) return@withLock refused()
        withContext(io) {
            runCatching {
                val merged = merge(state.value.copy())
                store.save(merged)
                merged
            }
        }.map { merged ->
            state.value = merged
            onSaved()
        }
    }

    /** Reads the cellar while no change is running (a consistent copy for sync). */
    suspend fun <T> read(block: (Cellar) -> T): T = mutex.withLock { block(state.value) }
}
