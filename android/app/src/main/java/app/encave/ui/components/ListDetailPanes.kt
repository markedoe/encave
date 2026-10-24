package app.encave.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.window.core.layout.WindowSizeClass
import app.encave.ui.AppNavigator
import app.encave.ui.theme.Dimens
import kotlinx.coroutines.launch

/** True on tablets, unfolded foldables and wide windows: the rule the Cellar uses for two panes. */
@Composable
fun isWideWindow(): Boolean =
    currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

/**
 * A section as a list beside its detail on wide windows; on phones, [list] alone with
 * `open = null`, so the section opens its detail as a full screen exactly as before.
 *
 * [list] gets `open(key)` to show a detail and the key shown, to highlight its row.
 * [detail] gets the key, a navigator whose Back closes the pane, and whether the list is beside it.
 */
@Composable
fun ListDetailPanes(
    navigator: AppNavigator,
    placeholder: String,
    list: @Composable (open: ((String) -> Unit)?, selected: String?) -> Unit,
    detail: @Composable (key: String, paneNavigator: AppNavigator, twoPane: Boolean) -> Unit,
    wide: Boolean = isWideWindow(),
) {
    if (!wide) {
        list(null, null)
        return
    }
    val panes = rememberListDetailPaneScaffoldNavigator<String>()
    val scope = rememberCoroutineScope()
    val selected = panes.currentDestination?.contentKey
    val twoPane = panes.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Expanded &&
        panes.scaffoldValue[ListDetailPaneScaffoldRole.Detail] == PaneAdaptedValue.Expanded
    val paneNavigator = remember(navigator) { navigator.inPane { scope.launch { panes.navigateBack(BackNavigationBehavior.PopUntilContentChange) } } }
    // The list shows the messages; the detail beside it has its own host, so none shows twice.
    val detailMessages = remember { SnackbarHostState() }
    // A detail closed (Back) is left for good, as a screen popped off the stack.
    val stores: DetailStores = viewModel()
    LaunchedEffect(stores, selected) { if (selected == null) stores.keepOnly(null) }

    TwoPaneMessages(twoPane) {
        NavigableListDetailPaneScaffold(
            navigator = panes,
            // Back closes the detail shown, then leaves the section: both panes always show here,
            // so popping until the layout changes would leave the section at once.
            defaultBackBehavior = BackNavigationBehavior.PopUntilContentChange,
            listPane = {
                AnimatedPane(Modifier.testTag("panes.list")) {
                    list({ key -> scope.launch { panes.navigateTo(ListDetailPaneScaffoldRole.Detail, key) } }, if (twoPane) selected else null)
                }
            },
            detailPane = {
                AnimatedPane(Modifier.testTag("panes.detail")) {
                    if (selected != null) {
                        CompositionLocalProvider(LocalSnackbarHostState provides if (twoPane) detailMessages else LocalSnackbarHostState.current) {
                            // A new detail starts afresh, not with the state of the one shown before.
                            key(selected) { DetailStore(selected) { detail(selected, paneNavigator, twoPane) } }
                        }
                    } else {
                        Box(Modifier.fillMaxSize().testTag("panes.placeholder"), contentAlignment = Alignment.Center) {
                            Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Dimens.xl))
                        }
                    }
                }
            },
        )
    }
}

/**
 * With two panes, shows the section's messages ("Moved to… / Undo") once, centered across both
 * panes, rather than inside the list pane under the list. The list's own Scaffold then gets a
 * host of its own, shown here too, so nothing it shows is lost. One pane: [content] as it is.
 */
@Composable
fun TwoPaneMessages(twoPane: Boolean, content: @Composable () -> Unit) {
    val messages = LocalSnackbarHostState.current
    val listMessages = remember { SnackbarHostState() }
    // The same layout either way: the panes keep their state when the window changes.
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalSnackbarHostState provides if (twoPane) listMessages else messages, content = content)
        if (twoPane) {
            val bottom = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            SnackbarHost(messages, bottom)
            SnackbarHost(listMessages, bottom)
        }
    }
}

/** The view model stores of a section's details, one per detail, kept while the activity is recreated. */
class DetailStores : ViewModel() {
    private val stores = HashMap<String, ViewModelStore>()

    fun storeFor(key: String): ViewModelStore = stores.getOrPut(key) { ViewModelStore() }

    /** Forgets the details no longer shown, closing what their screens kept. */
    fun keepOnly(key: String?) {
        stores.keys.filter { it != key }.forEach { stores.remove(it)?.clear() }
    }

    override fun onCleared() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }
}

/**
 * Gives a detail its own store, as a screen of its own would have: what it keeps with
 * [rememberRetained] (a form being edited) belongs to it and not to the next detail shown.
 */
@Composable
private fun DetailStore(key: String, content: @Composable () -> Unit) {
    val stores: DetailStores = viewModel()
    LaunchedEffect(stores, key) { stores.keepOnly(key) }
    val owner = remember(stores, key) {
        object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = stores.storeFor(key)
        }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}
