package app.encave.widget

import app.encave.core.Cellar
import app.encave.core.CellarStore
import app.encave.core.InMemoryCellarStore
import app.encave.core.Wine
import app.encave.core.WidgetSnapshot
import app.encave.data.CellarRepository
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetPublisherTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val year = 2026

    private fun file() = File(folder.root, WidgetSnapshot.FILE_NAME)

    @Test
    fun aBurstOfChangesIsWrittenOnceAfterAPause() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val repository = CellarRepository(InMemoryCellarStore(), scope, dispatcher)
        val store = WidgetSnapshotStore(file())
        var redraws = 0
        WidgetPublisher(repository, store, scope, onChanged = { redraws++ }, io = dispatcher, year = { year }).start()
        advanceUntilIdle()
        // The empty cellar is summarized too, so the widgets invite to add bottles.
        assertEquals(1, redraws)
        assertTrue(store.snapshot.value!!.isEmpty)

        repeat(3) { repository.update { it.addBottles(Wine(name = "W$it", drinkFrom = 2020, drinkUntil = 2030)) } }
        advanceTimeBy(WidgetPublisher.DELAY_MS / 2)
        assertEquals(1, redraws)
        advanceUntilIdle()
        assertEquals(2, redraws)
        assertEquals(3, store.snapshot.value!!.bottles)
        assertEquals(store.snapshot.value, WidgetSnapshot.decode(file().readText()))

        // The same summary again: nothing written, no redraw.
        repository.update { }
        advanceUntilIdle()
        assertEquals(2, redraws)
        scope.cancel()
    }

    @Test
    fun syncedChangesAreSummarizedToo() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val repository = CellarRepository(InMemoryCellarStore(), scope, dispatcher)
        val store = WidgetSnapshotStore(file())
        WidgetPublisher(repository, store, scope, onChanged = {}, io = dispatcher, year = { year }).start()
        advanceUntilIdle()
        repository.applySynced(merge = { Cellar().apply { addBottles(Wine(name = "From the other device"), quantity = 2) } })
        advanceUntilIdle()
        assertEquals(2, store.snapshot.value!!.bottles)
        scope.cancel()
    }

    @Test
    fun aCellarThatCouldNotBeReadIsNotSummarized() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val broken = object : CellarStore {
            override fun load(): Cellar = error("damaged")
            override fun save(cellar: Cellar) = Unit
        }
        val saved = WidgetSnapshot.of(Cellar().apply { addBottles(Wine(name = "Kept")) }, year)
        file().writeText(saved.encoded())
        val repository = CellarRepository(broken, scope, dispatcher)
        val store = WidgetSnapshotStore(file())
        var redraws = 0
        WidgetPublisher(repository, store, scope, onChanged = { redraws++ }, io = dispatcher, year = { year }).start()
        advanceUntilIdle()
        assertTrue(repository.loadFailed)
        assertEquals(0, redraws)
        assertEquals(saved, store.snapshot.value)
        scope.cancel()
    }

    @Test
    fun aDamagedFileReadsAsNoSummary() {
        file().writeText("{ not json")
        val store = WidgetSnapshotStore(file())
        assertNull(store.snapshot.value)
        val snapshot = WidgetSnapshot.of(Cellar(), year)
        assertTrue(store.write(snapshot))
        assertFalse(store.write(snapshot))
        assertEquals(snapshot, WidgetSnapshotStore(file()).snapshot.value)
        assertFalse(File(folder.root, "${WidgetSnapshot.FILE_NAME}.tmp").exists())
    }
}
