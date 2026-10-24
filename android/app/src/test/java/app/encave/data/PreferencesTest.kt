package app.encave.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PreferencesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clear() {
        context.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun brandColorsByDefault() {
        assertFalse(Preferences(context).dynamicColor.value)
    }

    @Test
    fun wallpaperColorsTurnedOnStayOn() {
        Preferences(context).setDynamicColor(true)
        assertTrue(Preferences(context).dynamicColor.value)
        Preferences(context).setDynamicColor(false)
        assertFalse(Preferences(context).dynamicColor.value)
    }
}
