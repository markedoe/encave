package app.encave.ui.cellar

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Cellar figures read as one TalkBack element that can still be activated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StatTileTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun tileKeepsItsClickActionForTalkBack() {
        var clicks = 0
        compose.setContent {
            StatTile(value = 12, caption = "bottles", icon = Icons.Outlined.Settings, hint = "Shows the cellar in figures",
                onClick = { clicks++ })
        }
        val tile = compose.onNode(hasContentDescription("12 bottles", substring = true) and hasClickAction())
        assertEquals("Shows the cellar in figures", tile.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        tile.performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(1, clicks)
    }
}
