package app.encave

import androidx.test.core.app.ApplicationProvider
import app.encave.core.Http
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HttpUserAgentTest {
    @Test
    fun requestsNameTheAppAndItsVersionOnly() {
        ApplicationProvider.getApplicationContext<EncaveApplication>()
        assertEquals("Encave/${BuildConfig.VERSION_NAME}", Http.userAgent)
    }
}
