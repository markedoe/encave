package app.encave.data

import app.encave.core.CellarException
import app.encave.core.InMemoryCellarStore
import app.encave.core.Slot
import app.encave.core.Wine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
