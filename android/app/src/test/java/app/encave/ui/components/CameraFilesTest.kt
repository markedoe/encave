package app.encave.ui.components

import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.encave.EncaveApplication
import app.encave.MainActivity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Camera originals (full size, with where they were taken) do not stay in the cache. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CameraFilesTest {
    private val app = ApplicationProvider.getApplicationContext<EncaveApplication>()

    private fun photo(name: String) = File(CameraFiles.folder(app), name).apply {
        parentFile?.mkdirs()
        writeBytes(byteArrayOf(1, 2, 3))
    }

    @Test
    fun aPhotoIsDeletedOnceUsedEvenWhenUsingItFails() {
        val label = photo("label.jpg")
        var read = false
        runBlocking { CameraFiles.useOnce(label, Uri.fromFile(label)) { read = label.exists() } }
        assertTrue(read)
        assertFalse(label.exists())
        val paper = photo("paper.jpg")
        runCatching { runBlocking { CameraFiles.useOnce(paper, Uri.fromFile(paper)) { error("unreadable") } } }
        assertFalse(paper.exists())
    }

    @Test
    fun photosLeftByAnEarlierLaunchAreRemovedAtLaunchOnly() {
        photo("label.jpg")
        photo("paper.jpg")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(emptyList<String>(), CameraFiles.folder(app).list()?.toList().orEmpty())
            // Recreated (as after the system closed it while the camera app was open): kept for the camera's answer.
            val pending = photo("label.jpg")
            scenario.recreate()
            assertTrue(pending.exists())
        }
    }
}
