package app.encave.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.WineBar
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.LocalBar
import androidx.compose.material.icons.outlined.WineBar
import androidx.compose.ui.graphics.vector.ImageVector
import app.encave.R
import kotlinx.serialization.Serializable

// Type-safe routes. Ids are UUID strings.

@Serializable data object CellarRoute
@Serializable data object RacksRoute
@Serializable data object TonightRoute
@Serializable data object JournalRoute

@Serializable data class WineDetailRoute(val wineId: String)
@Serializable data class EditWineRoute(val wineId: String)

/** Add bottles, optionally of an existing wine or into a given rack slot. */
@Serializable
data class AddBottleRoute(
    val wineId: String? = null,
    val rackId: String? = null,
    val row: Int = -1,
    val column: Int = -1,
    val depth: Int = 0,
    /** Set when buying a wine from the shopping list: it is marked bought once saved. */
    val discoveryId: String? = null,
)

/** Remember a wine tasted elsewhere (new), or edit a discovery. */
@Serializable data class DiscoveryEditorRoute(val discoveryId: String? = null)

@Serializable data class RackDetailRoute(val rackId: String, val highlightBottleId: String? = null)
@Serializable data class RackEditorRoute(val rackId: String? = null)
@Serializable data class TastingRoute(val bottleId: String)
@Serializable data object PairingRoute
@Serializable data object InsightsRoute
@Serializable data object SettingsRoute

/** The four sections, in navigation order. */
enum class TopLevel(
    val route: Any,
    @StringRes val label: Int,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
) {
    Cellar(CellarRoute, R.string.nav_cellar, Icons.Filled.WineBar, Icons.Outlined.WineBar),
    Racks(RacksRoute, R.string.nav_racks, Icons.Filled.GridView, Icons.Outlined.GridView),
    Tonight(TonightRoute, R.string.nav_tonight, Icons.Filled.LocalBar, Icons.Outlined.LocalBar),
    Journal(JournalRoute, R.string.nav_journal, Icons.Filled.AutoStories, Icons.Outlined.AutoStories),
}
