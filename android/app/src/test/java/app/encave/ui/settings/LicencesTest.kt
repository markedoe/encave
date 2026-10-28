package app.encave.ui.settings

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LicencesTest {
    @Test
    fun everyLibraryComesWithTheTextOfItsLicence() {
        val acknowledgements = Acknowledgements.load(ApplicationProvider.getApplicationContext())
        assertNotNull(acknowledgements)
        acknowledgements!!
        assertTrue(acknowledgements.licences["FSL-1.1-ALv2"]!!.text.contains("Functional Source License"))
        assertTrue(acknowledgements.dependencies.any { it.name == "ZXing" })
        acknowledgements.dependencies.forEach { dependency ->
            assertTrue(dependency.name, acknowledgements.licences[dependency.licence]?.text?.isNotBlank() == true)
            assertTrue(dependency.name, dependency.artifacts.isNotEmpty())
        }
    }
}
