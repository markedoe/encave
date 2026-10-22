package app.encave.ui.components

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import app.encave.R
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The notification switch tells TalkBack why it stays off, and follows the system settings on return. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationSwitchTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager get() = shadowOf(app.getSystemService(NotificationManager::class.java))
    private val refusedText get() = app.getString(R.string.reminders_refused)
    private var saved = false
    private val changes = mutableListOf<Boolean>()

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun show() {
        compose.setContent {
            NotificationSwitch(
                title = "Reminders", footer = "Footer", icon = Icons.Outlined.NotificationsActive, tag = "switch",
                isOn = { saved }, onChange = { saved = it; changes += it },
            )
        }
        compose.waitForIdle()
    }

    /** Leaves the app (to the system settings, say) and comes back. */
    private fun leaveAndReturn() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }

    @Test
    fun refusedIsAnnouncedAndClearedOnceNotificationsAreAllowed() {
        manager.setNotificationsEnabled(false)
        show()
        compose.onNodeWithTag("switch").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("switch").assertIsOff()
        compose.onNodeWithText(refusedText).assertExists()
        // A way to the notification settings, next to the message.
        compose.onNodeWithTag("switch.open_settings").assertExists()
        compose.onNodeWithTag("switch").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        assertEquals(emptyList<Boolean>(), changes)

        manager.setNotificationsEnabled(true)
        leaveAndReturn()
        compose.onNodeWithText(refusedText).assertDoesNotExist()
        compose.onNodeWithTag("switch.open_settings").assertDoesNotExist()
        compose.onNodeWithText("Footer").assertExists()
        compose.onNodeWithTag("switch").assertIsOff()

        compose.onNodeWithTag("switch").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("switch").assertIsOn()
        assertEquals(listOf(true), changes)
    }

    @Test
    fun switchTurnsOffWhenNotificationsAreBlockedMeanwhile() {
        manager.setNotificationsEnabled(true)
        saved = true
        show()
        compose.onNodeWithTag("switch").assertIsOn()

        manager.setNotificationsEnabled(false)
        leaveAndReturn()
        compose.onNodeWithTag("switch").assertIsOff()

        manager.setNotificationsEnabled(true)
        leaveAndReturn()
        compose.onNodeWithTag("switch").assertIsOn()
    }
}
