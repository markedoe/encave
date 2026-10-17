package app.encave.ui.racks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.R
import app.encave.ui.AppNavigator
import app.encave.ui.AppViewModel
import app.encave.ui.components.EmptyState
import app.encave.ui.components.LocalSnackbarHostState
import app.encave.ui.components.labelRes
import app.encave.ui.navigation.RackDetailRoute
import app.encave.ui.navigation.RackEditorRoute
import app.encave.ui.navigation.SettingsRoute
import app.encave.ui.theme.Dimens

/** Racks with their occupancy. Reorder mode uses buttons, which also work with TalkBack. */
@Composable
fun RacksScreen(viewModel: AppViewModel, navigator: AppNavigator) {
    val cellar by viewModel.cellar.collectAsStateWithLifecycle()
    var reordering by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.nav_racks)) },
                actions = {
                    if (cellar.racks.size > 1) {
                        IconButton(onClick = { reordering = !reordering }) {
                            if (reordering) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.action_done))
                            else Icon(Icons.Outlined.SwapVert, contentDescription = stringResource(R.string.action_reorder))
                        }
                    }
                    IconButton(onClick = { navigator.open(SettingsRoute) }) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            if (cellar.racks.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { navigator.open(RackEditorRoute()) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.action_add_rack)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
    ) { padding ->
        if (cellar.racks.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.GridView,
                title = stringResource(R.string.racks_empty_title),
                body = stringResource(R.string.racks_empty_body),
                modifier = Modifier.padding(padding),
            ) {
                Button(onClick = { navigator.open(RackEditorRoute()) }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(stringResource(R.string.action_add_rack), Modifier.padding(start = Dimens.s))
                }
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 96.dp)) {
            itemsIndexed(cellar.racks, key = { _, rack -> rack.id.toString() }) { index, rack ->
                val used = cellar.occupancy(rack)
                ListItem(
                    modifier = Modifier.animateItem().clickable(enabled = !reordering) {
                        navigator.open(RackDetailRoute(rack.id.toString()))
                    },
                    leadingContent = { RackThumbnail(rack, cellar, Modifier.padding(vertical = Dimens.xs)) },
                    headlineContent = { Text(rack.name, style = MaterialTheme.typography.titleMedium) },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                            Text(
                                "${stringResource(rack.kind.labelRes)} · ${stringResource(R.string.rack_holds, rack.capacity)}",
                            )
                            Text(stringResource(R.string.rack_occupancy, used, rack.capacity))
                            LinearProgressIndicator(
                                progress = { if (rack.capacity == 0) 0f else used.toFloat() / rack.capacity },
                                modifier = Modifier.fillMaxWidth(),
                                drawStopIndicator = {},
                            )
                        }
                    },
                    trailingContent = if (reordering) {
                        {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { viewModel.moveRack(index, index - 1) }, enabled = index > 0) {
                                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.action_move_up, rack.name))
                                }
                                IconButton(onClick = { viewModel.moveRack(index, index + 1) }, enabled = index < cellar.racks.lastIndex) {
                                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.action_move_down, rack.name))
                                }
                            }
                        }
                    } else {
                        null
                    },
                )
                HorizontalDivider()
            }
        }
    }
}
