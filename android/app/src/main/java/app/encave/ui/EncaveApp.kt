package app.encave.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.window.core.layout.WindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.encave.MainActivity
import app.encave.R
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.ui.add.AddBottleScreen
import app.encave.ui.add.EditWineScreen
import app.encave.ui.cellar.CellarScreen
import app.encave.ui.cellar.WineDetailScreen
import app.encave.ui.components.LocalSnackbarHostState
import app.encave.ui.components.resolve
import app.encave.ui.journal.InsightsScreen
import app.encave.ui.journal.JournalScreen
import app.encave.ui.journal.TastingScreen
import app.encave.ui.navigation.AddBottleRoute
import app.encave.ui.navigation.CellarRoute
import app.encave.ui.navigation.DiscoveryEditorRoute
import app.encave.ui.navigation.EditWineRoute
import app.encave.ui.navigation.HelpRoute
import app.encave.ui.navigation.DevicesRoute
import app.encave.ui.navigation.RemoteSyncRoute
import app.encave.ui.navigation.InviteDeviceRoute
import app.encave.ui.navigation.JoinDeviceRoute
import app.encave.ui.devices.DevicesScreen
import app.encave.ui.devices.RemoteSyncScreen
import app.encave.ui.devices.InviteDeviceScreen
import app.encave.ui.devices.JoinDeviceScreen
import app.encave.ui.help.HelpScreen
import app.encave.ui.discoveries.DiscoveryEditorScreen
import app.encave.ui.navigation.InsightsRoute
import app.encave.ui.navigation.JournalRoute
import app.encave.ui.navigation.PairingRoute
import app.encave.ui.navigation.RackDetailRoute
import app.encave.ui.navigation.RackEditorRoute
import app.encave.ui.navigation.RacksRoute
import app.encave.ui.navigation.SettingsRoute
import app.encave.ui.navigation.TastingRoute
import app.encave.ui.navigation.TonightRoute
import app.encave.ui.navigation.TopLevel
import app.encave.ui.navigation.WineDetailRoute
import app.encave.ui.racks.RackDetailScreen
import app.encave.ui.racks.RackEditorScreen
import app.encave.ui.racks.RacksScreen
import app.encave.ui.settings.SettingsScreen
import app.encave.ui.tonight.PairingScreen
import app.encave.ui.tonight.TonightScreen

/** Navigation actions shared by every screen. */
class AppNavigator(private val nav: NavHostController) {
    fun back() {
        nav.popBackStack()
    }

    fun open(route: Any) = nav.navigate(route) { launchSingleTop = true }

    fun openTopLevel(destination: TopLevel) = nav.navigate(destination.route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun EncaveApp(viewModel: AppViewModel, links: MutableStateFlow<String?> = MutableStateFlow(null)) {
    val nav = rememberNavController()
    val navigator = remember(nav) { AppNavigator(nav) }
    val link by links.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    // Reminders and app shortcuts: open the section, the wine or the scanner once the cellar is loaded.
    LaunchedEffect(link, loaded) {
        val target = link ?: return@LaunchedEffect
        if (!loaded) return@LaunchedEffect
        links.value = null
        val wineId = MainActivity.wineId(target)
        when {
            target == MainActivity.LINK_TONIGHT -> navigator.openTopLevel(TopLevel.Tonight)
            target == MainActivity.LINK_SCAN -> navigator.open(AddBottleRoute(scan = true))
            wineId != null && viewModel.cellar.value.wines.keys.any { it.toString().equals(wineId, ignoreCase = true) } ->
                navigator.open(WineDetailRoute(wineId.lowercase()))
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            val result = snackbar.showSnackbar(
                message = message.text.resolve(resources),
                actionLabel = message.undo?.let { resources.getString(R.string.action_undo) },
                withDismissAction = message.undo == null,
                duration = if (message.undo != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) message.undo?.invoke()
        }
    }

    val entry by nav.currentBackStackEntryAsState()
    // The current section is the last top-level destination shown.
    var selected by rememberSaveable { mutableStateOf(TopLevel.Cellar) }
    val current = entry?.destination?.let { d -> TopLevel.entries.firstOrNull { d.hasRoute(it.route::class) } }
    LaunchedEffect(current) { if (current != null) selected = current }
    // Full-screen forms hide the navigation bar or rail.
    val fullScreen = entry?.destination?.let { d ->
        d.hasRoute(AddBottleRoute::class) || d.hasRoute(EditWineRoute::class) || d.hasRoute(RackEditorRoute::class) ||
            d.hasRoute(DiscoveryEditorRoute::class)
    } ?: false
    // Bar on phones, rail on tablets and foldables, drawer on large screens.
    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val layoutType = when {
        fullScreen -> NavigationSuiteType.None
        adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_LARGE_LOWER_BOUND) ->
            NavigationSuiteType.NavigationDrawer
        else -> NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptiveInfo)
    }

    CompositionLocalProvider(LocalSnackbarHostState provides snackbar) {
        NavigationSuiteScaffold(
            layoutType = layoutType,
            navigationSuiteItems = {
                TopLevel.entries.forEach { destination ->
                    val isSelected = destination == selected
                    item(
                        selected = isSelected,
                        onClick = { navigator.openTopLevel(destination) },
                        icon = { Icon(if (isSelected) destination.selectedIcon else destination.icon, contentDescription = null) },
                        label = { Text(stringResource(destination.label)) },
                        modifier = Modifier.testTag("nav.${destination.name}"),
                    )
                }
            },
        ) {
            NavHost(navController = nav, startDestination = CellarRoute) {
                composable<CellarRoute> { CellarScreen(viewModel, navigator) }
                composable<RacksRoute> { RacksScreen(viewModel, navigator) }
                composable<TonightRoute> { TonightScreen(viewModel, navigator) }
                composable<JournalRoute> { JournalScreen(viewModel, navigator) }
                composable<WineDetailRoute> {
                    WineDetailScreen(viewModel, navigator, it.toRoute<WineDetailRoute>().wineId, showBack = true)
                }
                composable<EditWineRoute> { EditWineScreen(viewModel, navigator, it.toRoute<EditWineRoute>().wineId) }
                composable<AddBottleRoute> { AddBottleScreen(viewModel, navigator, it.toRoute()) }
                composable<RackDetailRoute> { RackDetailScreen(viewModel, navigator, it.toRoute()) }
                composable<RackEditorRoute> { RackEditorScreen(viewModel, navigator, it.toRoute<RackEditorRoute>().rackId) }
                composable<TastingRoute> { TastingScreen(viewModel, navigator, it.toRoute<TastingRoute>().bottleId) }
                composable<PairingRoute> { PairingScreen(viewModel, navigator) }
                composable<InsightsRoute> { InsightsScreen(viewModel, navigator) }
                composable<SettingsRoute> { SettingsScreen(viewModel, navigator) }
                composable<HelpRoute> { HelpScreen(navigator, it.toRoute<HelpRoute>().topic) }
                composable<DevicesRoute> { DevicesScreen(navigator) }
                composable<RemoteSyncRoute> { RemoteSyncScreen(navigator) }
                composable<InviteDeviceRoute> { InviteDeviceScreen(navigator) }
                composable<JoinDeviceRoute> { JoinDeviceScreen(viewModel, navigator) }
                composable<DiscoveryEditorRoute> {
                    DiscoveryEditorScreen(viewModel, navigator, it.toRoute<DiscoveryEditorRoute>().discoveryId)
                }
            }
        }
    }
}
