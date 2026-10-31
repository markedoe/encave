package app.encave.ui.tonight

import app.encave.ui.components.WineThumbnail
import app.encave.ui.cellar.WineMenuBox
import android.content.Intent
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.window.DialogProperties
import app.encave.core.EveningRequest
import app.encave.core.eveningRequest
import androidx.compose.material3.ButtonDefaults
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.core.content.ContextCompat
import app.encave.core.Bottle
import app.encave.core.PairingSuggestion
import app.encave.core.bottleToOpen
import app.encave.core.nextToBeReady
import app.encave.core.reservedBottleCount
import app.encave.core.tonightPicks
import app.encave.reminders.Reminders
import app.encave.ui.UiText
import app.encave.ui.components.ConfirmDialog
import app.encave.ui.components.OpenNotificationSettingsButton
import app.encave.ui.components.sampleLabels
import app.encave.ui.drink.rememberDrinking
import app.encave.ui.navigation.AddBottleRoute
import app.encave.ui.navigation.RackDetailRoute
import java.time.LocalDate
import java.util.UUID
import androidx.compose.material3.OutlinedButton
import app.encave.ui.devices.SettingsIcon
import app.encave.ui.components.ActionContent
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.LocalBar
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.R
import app.encave.ui.components.displayLocation
import app.encave.ui.components.locationIcon
import app.encave.ui.components.BottlePlaceIcons
import app.encave.core.Cellar
import app.encave.core.DishCategory
import app.encave.core.Maturity
import app.encave.core.WineGroup
import app.encave.core.pairings
import app.encave.ui.AppNavigator
import app.encave.ui.AppViewModel
import app.encave.ui.components.ActionButtonPair
import app.encave.ui.components.EmptyState
import app.encave.ui.components.styleLine
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.pluralStringResource
import app.encave.ui.components.Formats
import app.encave.ui.components.LocalSnackbarHostState
import app.encave.ui.components.MaturityLabel
import app.encave.ui.components.WineGroupRow
import app.encave.ui.components.ListDetailPanes
import app.encave.ui.cellar.WineDetailScreen
import app.encave.ui.components.icon
import app.encave.ui.components.labelRes
import app.encave.ui.components.tint
import app.encave.ui.drink.DrinkSheet
import app.encave.ui.navigation.PairingRoute
import app.encave.ui.navigation.SettingsRoute
import app.encave.ui.navigation.WineDetailRoute
import app.encave.ui.theme.Dimens
import app.encave.ui.theme.SerifDisplay

/** What to open tonight: a suggestion that changes daily, food pairing, then bottles by maturity, most urgent first. */
@Composable
fun TonightScreen(viewModel: AppViewModel, navigator: AppNavigator) {
    ListDetailPanes(
        navigator = navigator,
        placeholder = stringResource(R.string.cellar_select_wine),
        list = { open, selected -> TonightList(viewModel, navigator, open, selected) },
        detail = { id, paneNavigator, twoPane ->
            WineDetailScreen(viewModel, paneNavigator, id, showBack = !twoPane, showMessages = !twoPane)
        },
    )
}

/** The suggestion, then the wines by maturity; [onOpenWine] shows a wine beside the list on wide windows. */
@Composable
private fun TonightList(viewModel: AppViewModel, navigator: AppNavigator, onOpenWine: ((String) -> Unit)?, selectedId: String?) {
    val openWine: (String) -> Unit = { id -> onOpenWine?.invoke(id) ?: navigator.open(WineDetailRoute(id)) }
    val cellar by viewModel.cellar.collectAsStateWithLifecycle()
    val shared by viewModel.sharedWithDevices.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val year = viewModel.year
    val sections = remember(cellar, year) { cellar.groupsByMaturity(year) }
    // The local date, as on Apple: a new first suggestion each day from midnight.
    val today = LocalDate.now()
    // The first wine shown today, "yyyy-MM-dd|id" on this device: it stays today's pick while
    // it can be drunk, even when wines are added.
    val tonightPrefs = LocalContext.current.getSharedPreferences("tonight", android.content.Context.MODE_PRIVATE)
    val kept = tonightPrefs.getString("firstPick", null)?.takeIf { it.startsWith("$today|") }
        ?.substringAfter('|')?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    val candidates = remember(cellar, year, today, kept) { cellar.tonightPicks(year, today, kept) }
    val shown = candidates.firstOrNull()?.let { "$today|${it.wine.id}" }
    LaunchedEffect(shown) {
        if (shown != null && tonightPrefs.getString("firstPick", null) != shown) tonightPrefs.edit().putString("firstPick", shown).apply()
    }
    // "Another idea" taps; the first suggestion of the day comes from the date.
    var pickOffset by rememberSaveable { mutableIntStateOf(0) }
    var drinking by rememberDrinking()
    var confirmSample by rememberSaveable { mutableStateOf(false) }
    var askingEvening by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // Wide windows: today's suggestion beside the list rather than an empty pane, once.
    var preselected by rememberSaveable { mutableStateOf(false) }
    val suggested = candidates.firstOrNull()?.id?.toString()
    LaunchedEffect(onOpenWine != null, suggested) {
        if (onOpenWine != null && !preselected && selectedId == null && suggested != null) {
            preselected = true
            onOpenWine(suggested)
        }
    }
    val showInRack: (Bottle) -> Unit = { bottle ->
        bottle.slot?.let { navigator.open(RackDetailRoute(it.rackId.toString(), bottle.id.toString())) }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.nav_tonight)) },
                actions = {
                    if (cellar.bottlesInCellar.isNotEmpty()) {
                        IconButton(onClick = { askingEvening = true }, modifier = Modifier.testTag("tonight.evening")) {
                            Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.tonight_evening_share))
                        }
                    }
                    IconButton(onClick = { navigator.open(SettingsRoute) }) {
                        SettingsIcon()
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
    ) { padding ->
        if (cellar.bottlesInCellar.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.LocalBar,
                title = stringResource(R.string.tonight_empty_title),
                body = stringResource(R.string.tonight_empty_body),
                modifier = Modifier.padding(padding),
            ) {
                Button(onClick = { navigator.open(AddBottleRoute()) }, modifier = Modifier.testTag("tonight.empty_add")) {
                    ActionContent(stringResource(R.string.action_add_bottle), Icons.Filled.Add)
                }
                if (viewModel.canLoadSample(cellar, shared)) {
                    OutlinedButton(onClick = { confirmSample = true }) { Text(stringResource(R.string.action_load_sample)) }
                }
            }
        } else {
            val reserved = cellar.reservedBottleCount
            LazyColumn(Modifier.fillMaxSize().testTag("tonight.list"), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = Dimens.xxl)) {
                item {
                    when {
                        // Every bottle is kept for an occasion.
                        sections.isEmpty() -> NoteRow(Icons.Outlined.CardGiftcard, stringResource(R.string.tonight_all_reserved), Modifier.testTag("tonight.all_reserved"))
                        candidates.isEmpty() -> NothingReadyCard(cellar.nextToBeReady(year))
                        else -> {
                            val pick = candidates[pickOffset % candidates.size]
                            SurpriseCard(
                                group = pick, cellar = cellar, year = year,
                                onOpen = { openWine(pick.id.toString()) },
                                onDrink = { drinking = pick.bottles.bottleToOpen?.id?.toString() },
                                onShowInRack = showInRack,
                                // Only when there is another wine to suggest.
                                onShuffle = if (candidates.size > 1) ({ pickOffset++ }) else null,
                            )
                        }
                    }
                    if (reserved > 0 && sections.isNotEmpty()) {
                        Text(
                            stringResource(R.string.tonight_reserved_left_out, reserved),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Dimens.xl, vertical = Dimens.xs),
                        )
                    }
                }
                item {
                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s).clickable { navigator.open(PairingRoute) },
                        shape = RoundedCornerShape(Dimens.radiusCard),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Row(Modifier.padding(Dimens.l), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Restaurant, contentDescription = null, modifier = Modifier.size(32.dp))
                            Column(Modifier.padding(start = Dimens.l).weight(1f)) {
                                Text(stringResource(R.string.tonight_pairing_title), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.tonight_pairing_body), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                // The monthly reminder offer comes after the first section (drink soon when there is
                // one), so the most urgent bottles follow the suggestion on a phone screen.
                val reminderOffer: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
                    item(key = "reminderOffer") {
                        ReminderOffer(cellar, onTurnedOn = { viewModel.showMessage(UiText.Res(R.string.reminders_turned_on)) })
                    }
                }
                if (sections.isEmpty()) reminderOffer()
                sections.forEachIndexed { index, section ->
                    if (index == 1) reminderOffer()
                    item(key = section.maturity.raw) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = Dimens.l, end = Dimens.l, top = Dimens.xl, bottom = Dimens.xs)
                                .semantics(mergeDescendants = true) { heading() },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(section.maturity.icon, contentDescription = null, tint = section.maturity.tint)
                            Text(
                                stringResource(section.maturity.labelRes),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(start = Dimens.s).weight(1f),
                            )
                            val bottles = section.groups.sumOf { it.count }
                            val bottlesDescription = pluralStringResource(R.plurals.bottle_count, bottles, bottles)
                            Text(
                                Formats.integer(bottles), style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.semantics { contentDescription = bottlesDescription },
                            )
                        }
                    }
                    items(section.groups, key = { section.maturity.raw + it.id }) { group ->
                        TonightRow(
                            group = group, cellar = cellar, year = year, viewModel = viewModel, navigator = navigator,
                            onOpen = { openWine(group.id.toString()) },
                            selected = group.id.toString() == selectedId,
                            onDrink = { drinking = group.bottles.bottleToOpen?.id?.toString() },
                        )
                        HorizontalDivider(Modifier.padding(start = 56.dp))
                    }
                }
                if (sections.size == 1) reminderOffer()
            }
        }
    }
    drinking?.let { id ->
        DrinkSheet(cellar, id, onDismiss = { drinking = null }, onDrink = { b, t -> viewModel.drink(b.id, t) })
    }
    if (askingEvening) {
        val context = LocalContext.current
        EveningRequestDialog(onDismiss = { askingEvening = false }) { occasion, onlyReady ->
            val request = cellar.eveningRequest(
                occasion = occasion, onlyReady = onlyReady,
                language = EveningRequest.Language.of(resources.configuration.locales[0].language), year = year,
            ) { cellar.displayLocation(it, resources) }
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, request.text)
            runCatching { context.startActivity(Intent.createChooser(send, resources.getString(R.string.tonight_evening_share))) }
        }
    }
    if (confirmSample) {
        ConfirmDialog(
            title = stringResource(R.string.settings_sample_confirm_title),
            body = stringResource(R.string.settings_sample_confirm_body),
            confirm = stringResource(R.string.action_load_sample),
            onConfirm = { viewModel.loadSample(sampleLabels(resources)) },
            onDismiss = { confirmSample = false },
            destructive = false,
        )
    }
}

/**
 * Share an evening request: the occasion, then the share sheet with the request and the
 * bottles, for an assistant app the user picks. Encave sends nothing itself.
 */
@Composable
private fun EveningRequestDialog(onDismiss: () -> Unit, onShare: (String, Boolean) -> Unit) {
    var occasion by rememberSaveable { mutableStateOf("") }
    var onlyReady by rememberSaveable { mutableStateOf(true) }
    // Full width with margins, as the dialog to keep a bottle for an occasion.
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = Dimens.xl).widthIn(max = 560.dp),
        title = { Text(stringResource(R.string.tonight_evening_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
                Text(stringResource(R.string.tonight_evening_body))
                OutlinedTextField(
                    value = occasion, onValueChange = { occasion = it },
                    label = { Text(stringResource(R.string.tonight_evening_occasion)) },
                    modifier = Modifier.fillMaxWidth().testTag("evening.occasion"),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                Row(
                    Modifier.fillMaxWidth().toggleable(value = onlyReady, role = Role.Switch, onValueChange = { onlyReady = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.tonight_evening_only_ready), Modifier.weight(1f))
                    Switch(checked = onlyReady, onCheckedChange = null)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onShare(occasion, onlyReady) }, modifier = Modifier.testTag("evening.share")) {
                Text(stringResource(R.string.action_share))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** A wine in Tonight's list. Long press: the wine's menu, as in the Cellar (and on Apple); TalkBack offers the same. */
@Composable
private fun TonightRow(
    group: WineGroup,
    cellar: Cellar,
    year: Int,
    viewModel: AppViewModel,
    navigator: AppNavigator,
    onOpen: () -> Unit,
    onDrink: () -> Unit,
    selected: Boolean = false,
) {
    WineMenuBox(group, cellar, viewModel, navigator, onDrink = onDrink) { rowModifier, openMenu ->
        WineGroupRow(
            group = group, cellar = cellar, year = year, showMaturity = false,
            onClick = onOpen,
            onLongClick = openMenu,
            onLongClickLabel = stringResource(R.string.action_more),
            selected = selected,
            modifier = rowModifier.testTag("tonight.row"),
        )
    }
}

/** A line of explanation with its icon, in place of the suggestion. */
@Composable
private fun NoteRow(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = Dimens.xl, vertical = Dimens.l).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, Modifier.padding(start = Dimens.m), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Nothing ready or to drink soon: say so, and when the next wine will be. */
@Composable
private fun NothingReadyCard(next: WineGroup?) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s).testTag("tonight.nothing_ready").semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(Dimens.radiusCard),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.HourglassEmpty, contentDescription = null)
                Text(
                    stringResource(R.string.tonight_nothing_ready), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = Dimens.s),
                )
            }
            val from = next?.wine?.drinkFrom
            if (next != null && from != null) {
                Text(
                    stringResource(R.string.tonight_ready_from, from.toString(), next.wine.displayName),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Offers the monthly reminder once, where it is useful: while bottles have a drinking window
 * and reminders were never turned on or off. Asks for the notification permission; when
 * notifications are off for Encave, says so and opens their settings.
 */
@Composable
private fun ReminderOffer(cellar: Cellar, onTurnedOn: () -> Unit) {
    val context = LocalContext.current
    var offered by remember { mutableStateOf(Reminders.wasOffered(context) || Reminders.isEnabled(context)) }
    var refused by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && Reminders.canNotify(context)) {
            Reminders.setEnabled(context, true)
            offered = true
            onTurnedOn()
        } else {
            Reminders.markOffered(context)
            refused = true
        }
    }
    val hasWindow = remember(cellar) {
        cellar.bottlesInCellar.any { bottle -> cellar.wines[bottle.wineId]?.let { it.drinkFrom != null && it.drinkUntil != null } == true }
    }
    if (!refused && (offered || !hasWindow)) return
    Card(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s).testTag("tonight.reminders"),
        shape = RoundedCornerShape(Dimens.radiusCard),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.NotificationsActive, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(if (refused) R.string.reminders_refused else R.string.tonight_reminders_offer),
                    Modifier.padding(start = Dimens.m).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                if (refused) {
                    OpenNotificationSettingsButton()
                } else {
                    Button(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else if (Reminders.canNotify(context)) {
                                Reminders.setEnabled(context, true)
                                offered = true
                                onTurnedOn()
                            } else {
                                Reminders.markOffered(context)
                                refused = true
                            }
                        },
                        modifier = Modifier.testTag("tonight.reminders.on"),
                    ) { Text(stringResource(R.string.action_turn_on)) }
                }
                TextButton(
                    onClick = {
                        Reminders.markOffered(context)
                        offered = true
                        refused = false
                    },
                    modifier = Modifier.testTag("tonight.reminders.later"),
                ) { Text(stringResource(R.string.action_not_now)) }
            }
        }
    }
}

@Composable
private fun SurpriseCard(
    group: WineGroup,
    cellar: Cellar,
    year: Int,
    onOpen: () -> Unit,
    onDrink: () -> Unit,
    onShowInRack: (Bottle) -> Unit,
    onShuffle: (() -> Unit)?,
) {
    // The bottle Drink opens first: a free one, as in the drink sheet.
    val bottle = group.bottles.bottleToOpen
    Card(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s),
        shape = RoundedCornerShape(Dimens.radiusCard),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                Text(
                    stringResource(R.string.tonight_surprise_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = Dimens.s).semantics { heading() },
                )
            }
            // Its picture (else its bottle end) beside the wine, as on Apple.
            Row(
                Modifier.clickable(onClick = onOpen).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(Dimens.l), verticalAlignment = Alignment.CenterVertically,
            ) {
                WineThumbnail(group.wine, width = 56.dp)
                Column {
                    Text(group.wine.displayName, style = SerifDisplay)
                    // The style in words, not only by the bottle's color.
                    Text(group.wine.styleLine(), style = MaterialTheme.typography.bodyMedium)
                    MaturityLabel(Maturity.of(group.wine, year), short = true, modifier = Modifier.padding(top = Dimens.xs))
                }
            }
            val location = bottle?.let { cellar.displayLocation(it) } ?: stringResource(R.string.location_none)
            val rackLabel = stringResource(R.string.action_show_in_rack)
            if (bottle?.slot != null) {
                // Opens the rack on this bottle.
                TextButton(
                    onClick = { onShowInRack(bottle) },
                    contentPadding = PaddingValues(horizontal = 0.dp),
                    // The card's text color: the primary color is too faint on its container.
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                    modifier = Modifier.semantics { onClick(label = rackLabel) { onShowInRack(bottle); true } }.testTag("action.surprise_rack"),
                ) {
                    Icon(cellar.locationIcon(bottle), contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(location, Modifier.padding(start = Dimens.s), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                // Not a button: the same icon, in the card's text color, as on Apple.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(bottle?.let { cellar.locationIcon(it) } ?: BottlePlaceIcons.NotInStorage, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(location, Modifier.padding(start = Dimens.s), style = MaterialTheme.typography.bodyMedium)
                }
            }
            // A filled button: a tonal one is hardly visible on the card's own container color.
            val drink: @Composable (Modifier) -> Unit = { width ->
                Button(onClick = onDrink, modifier = width.testTag("action.surprise_drink")) {
                    ActionContent(stringResource(R.string.action_drink), Icons.Outlined.LocalBar)
                }
            }
            if (onShuffle == null) {
                drink(Modifier.fillMaxWidth())
            } else {
                ActionButtonPair(
                    first = drink,
                    second = { width ->
                        OutlinedButton(
                            onClick = onShuffle,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)),
                            modifier = width.testTag("action.surprise_next"),
                        ) {
                            ActionContent(stringResource(R.string.action_surprise), Icons.Outlined.Casino)
                        }
                    },
                )
            }
        }
    }
}

/** Pick a dish, see the best bottles for it with the reasons. */
@Composable
fun PairingScreen(viewModel: AppViewModel, navigator: AppNavigator) {
    val cellar by viewModel.cellar.collectAsStateWithLifecycle()
    // The last dish chosen, on this device; red meat at first, as on the iPhone.
    val dish by viewModel.preferences.pairingDish.collectAsStateWithLifecycle()
    val suggestions = remember(cellar, dish) { cellar.pairings(dish, viewModel.year) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pairing_title)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = Dimens.xxl)) {
            item {
                Text(
                    stringResource(R.string.pairing_pick), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s).semantics { heading() },
                )
                FlowRow(Modifier.padding(horizontal = Dimens.l), horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    DishCategory.entries.forEach { option ->
                        FilterChip(
                            selected = dish == option,
                            onClick = { viewModel.preferences.setPairingDish(option) },
                            label = { Text(stringResource(option.labelRes)) },
                        )
                    }
                }
            }
            if (suggestions.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.pairing_none), Modifier.padding(Dimens.l),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(suggestions, key = { it.id.toString() }) { suggestion ->
                WineGroupRow(
                    group = suggestion.group, cellar = cellar, year = viewModel.year,
                    onClick = { navigator.open(WineDetailRoute(suggestion.id.toString())) },
                    extra = {
                        // Words rather than a score that reads like a precise measure.
                        if (suggestion.score >= PairingSuggestion.GREAT_MATCH) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Text(
                                    stringResource(R.string.pairing_great_match),
                                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = Dimens.xs),
                                )
                            }
                        }
                        val reasons = suggestion.reasons.map { stringResource(it.labelRes) }
                        Text(reasons.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    },
                )
                HorizontalDivider(Modifier.padding(start = 56.dp))
            }
        }
    }
}
