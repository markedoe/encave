package app.encave.data

import app.encave.core.Cellar
import app.encave.core.CellarException
import app.encave.core.CellarStore
import app.encave.core.InMemoryCellarStore
import app.encave.core.Slot
import app.encave.core.Wine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class CellarRepositoryTest {
    @Test
    fun changesArePublishedAsNewCopiesAndSaved() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = InMemoryCellarStore()
        val repository = CellarRepository(store, TestScope(dispatcher), dispatcher)
        advanceUntilIdle()
        val before = repository.cellar.value
        repository.update { it.addBottles(Wine(name = "W"), quantity = 2) }
        assertNotSame(before, repository.cellar.value)
        assertTrue(before.bottles.isEmpty())
        assertEquals(2, repository.cellar.value.bottles.size)
        assertEquals(2, store.load().bottles.size)
    }

    @Test
    fun failedChangesLeaveTheCellarUntouched() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = CellarRepository(InMemoryCellarStore(), TestScope(dispatcher), dispatcher)
        advanceUntilIdle()
        val result = repository.update { it.addBottles(Wine(name = "W"), slots = listOf(Slot(UUID.randomUUID(), 0, 0))) }
        assertTrue(result.exceptionOrNull() is CellarException)
        assertTrue(repository.cellar.value.isEmpty)
    }

    @Test
    fun anUndoOfARestoreIsRefusedOnceTheCellarChanged() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = CellarRepository(InMemoryCellarStore(), TestScope(dispatcher), dispatcher)
        advanceUntilIdle()
        val before = repository.cellar.value
        val restored = Cellar().also { it.addBottles(Wine(name = "Restored"), quantity = 1) }
        repository.replace(restored)
        // Something changed since (here, or synced): the Undo would take it back too.
        repository.update { it.addBottles(Wine(name = "Since"), quantity = 1) }
        assertFalse(repository.replaceIf(restored, before).getOrThrow())
        assertEquals(2, repository.cellar.value.bottles.size)
        // Nothing changed since: put back.
        val now = repository.cellar.value
        assertTrue(repository.replaceIf(now, before).getOrThrow())
        assertTrue(repository.cellar.value.isEmpty)
    }

    /** A stored cellar that cannot be read; [moved] is what setting it aside returns. */
    private class Unreadable(private val moved: String?) : CellarStore {
        var saves = 0
        override fun load(): Cellar = error("damaged")
        override fun save(cellar: Cellar) {
            saves++
        }
        override fun setAside(): String? = moved
    }

    @Test
    fun anUnreadableFileIsSetAsideBeforeAnythingIsSaved() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = Unreadable("cellar-unreadable-20261018-090000.json")
        val repository = CellarRepository(store, TestScope(dispatcher), dispatcher)
        advanceUntilIdle()
        assertEquals("cellar-unreadable-20261018-090000.json", repository.setAsideName)
        assertFalse(repository.loadFailed)
        assertTrue(repository.update { it.addBottles(Wine(name = "W")) }.isSuccess)
        assertEquals(1, store.saves)
    }

    @Test
    fun anUnreadableFileThatCannotBeMovedIsNeverOverwritten() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = Unreadable(null)
        val repository = CellarRepository(store, TestScope(dispatcher), dispatcher)
        advanceUntilIdle()
        assertTrue(repository.loadFailed)
        assertTrue(repository.update { it.addBottles(Wine(name = "W")) }.isFailure)
        assertTrue(repository.replace(Cellar()).isFailure)
        assertTrue(repository.applySynced(merge = { it }).isFailure)
        assertEquals(0, store.saves)
    }

    @Test
    fun syncedChangesAreTakenInOnlyOnceSaved() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val failing = object : CellarStore {
            override fun load() = Cellar()
            override fun save(cellar: Cellar) = throw java.io.IOException("disk full")
        }
        val repository = CellarRepository(failing, TestScope(dispatcher), dispatcher)
        advanceUntilIdle()
        var saved = false
        val result = repository.applySynced(merge = { Cellar().apply { addBottles(Wine(name = "W")) } }, onSaved = { saved = true })
        assertTrue(result.isFailure)
        assertFalse(saved)
        assertTrue(repository.cellar.value.isEmpty)
    }
}
