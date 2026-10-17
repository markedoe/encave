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

    /** Set when the stored cellar could not be read; the file is then left untouched. */
    var loadFailed: Boolean = false
        private set

    init {
        scope.launch {
            mutex.withLock {
                val result = withContext(io) { runCatching { store.load() } }
                result.onSuccess { state.value = it }.onFailure { loadFailed = true }
                loadedState.value = true
            }
        }
    }

    /**
     * Told of every change made on this device (not of changes received from other
     * devices), with the cellar before and after, while the change still holds the
     * lock: sync records what changed.
     */
    @Volatile var onLocalChange: ((old: Cellar, new: Cellar) -> Unit)? = null

    /** Applies [change] to a copy, saves it, then publishes it. */
    suspend fun <T> update(change: (Cellar) -> T): Result<T> = mutex.withLock {
        val before = state.value
        val copy = before.copy()
        val result = runCatching { change(copy) }
        if (result.isFailure) return@withLock result
        val saved = withContext(io) { runCatching { store.save(copy) } }
        saved.exceptionOrNull()?.let { return@withLock Result.failure(it) }
        loadFailed = false
        state.value = copy
        onLocalChange?.invoke(before, copy)
        result
    }

    /** Replaces the whole cellar (sample data, erase all). */
    suspend fun replace(cellar: Cellar): Result<Unit> = mutex.withLock {
        val before = state.value
        val copy = cellar.copy()
        withContext(io) { runCatching { store.save(copy) } }.onSuccess {
            loadFailed = false
            state.value = copy
            onLocalChange?.invoke(before, copy)
        }
    }

    /**
     * Applies what came from another device: [merge] gets the current cellar and
     * returns the new one. Not reported to [onLocalChange].
     */
    suspend fun applySynced(merge: (Cellar) -> Cellar): Result<Unit> = mutex.withLock {
        val merged = merge(state.value.copy())
        withContext(io) { runCatching { store.save(merged) } }.onSuccess {
            loadFailed = false
            state.value = merged
        }
    }

    /** Reads the cellar while no change is running (a consistent copy for sync). */
    suspend fun <T> read(block: (Cellar) -> T): T = mutex.withLock { block(state.value) }
}
