package app.encave.ui.discoveries

import app.encave.core.expectedOrBundledProfile
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalBottomSheet
import app.encave.ui.drink.AromaGameSheet
import app.encave.ui.drink.TastingFields
import app.encave.ui.drink.TastingFormState
import app.encave.core.Wine
import app.encave.core.Tasting
import app.encave.ui.components.ActionContent
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.R
import app.encave.ui.components.readableWidth
import app.encave.core.Discovery
import app.encave.core.discoveryTags
import app.encave.ui.AppNavigator
import app.encave.ui.AppViewModel
import app.encave.ui.add.PhotoSection
import app.encave.ui.add.WineEssentials
import app.encave.ui.add.WineFormState
import app.encave.ui.add.WineMoreDetails
import app.encave.ui.add.knownValues
import app.encave.ui.components.ConfirmDialog
import app.encave.ui.components.Hint
import app.encave.ui.help.HelpLink
import app.encave.ui.help.HelpTopic
import app.encave.ui.components.Formats
import app.encave.ui.components.LocalSnackbarHostState
import app.encave.ui.components.WhenLoaded
import app.encave.ui.components.rememberRetained
import app.encave.ui.components.icon
import app.encave.ui.components.labelRes
import app.encave.ui.drink.DateDialog
import app.encave.ui.theme.Dimens
import java.time.Instant

/**
 * Note a wine tasted elsewhere (restaurant, friends, a fair; the cellar does not change): photo (read by AI when set up),
 * the wine, where, your impression, tags and the shopping list. Also edits one.
 */
@Composable
fun DiscoveryEditorScreen(viewModel: AppViewModel, navigator: AppNavigator, discoveryId: String?) {
    WhenLoaded(viewModel) { DiscoveryEditorForm(viewModel, navigator, discoveryId) }
}

@Composable
private fun DiscoveryEditorForm(viewModel: AppViewModel, navigator: AppNavigator, discoveryId: String?) {
    val cellar by viewModel.cellar.collectAsStateWithLifecycle()
    val existing = remember(discoveryId) { discoveryId?.let { id -> cellar.discoveries.values.firstOrNull { it.id.toString() == id } } }
    // Removed meanwhile (on another device): nothing to edit, and saving would add it again.
    if (discoveryId != null && existing == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { navigator.back() }
        return
    }
    val wine = existing?.let { cellar.wines[it.wineId] }
    val form = app.encave.ui.add.rememberWineForm("discovery.form", wine, viewModel.year)
    val known = remember(cellar) { cellar.knownValues() }
    var setting by rememberSaveable { mutableStateOf(existing?.setting) }
    var place by rememberSaveable { mutableStateOf(existing?.place.orEmpty()) }
    var date by rememberSaveable { mutableStateOf(existing?.date ?: app.encave.core.ReferenceDates.now()) }
    var pickingDate by remember { mutableStateOf(false) }
    // The rating, the note and the tasting sheet, as in the Drink sheet: the stars, score and
    // note are saved in the discovery's own fields, the rest in its tasting.
    val tasting = rememberSaveable(saver = TastingFormState.Saver) {
        TastingFormState(
            (existing?.tasting ?: Tasting(date = existing?.date ?: app.encave.core.ReferenceDates.now()))
                .copy(rating = existing?.rating, score100 = existing?.score100, note = existing?.note),
        )
    }
    var playingGame by rememberSaveable { mutableStateOf(false) }
    /** After Save, with aromas noted: how they compare with what to expect. */
    var comparison by remember { mutableStateOf<Pair<String, app.encave.core.AromaComparison>?>(null) }
    // The wine as typed, for what to expect and the game (without taking a grape or tag still being typed).
    val typed = (wine ?: Wine(name = "")).copy(
        id = form.id, name = form.name.trim(), producer = form.producer.trim().ifEmpty { null }, vintage = form.vintage,
        color = form.color, region = form.region.trim().ifEmpty { null }, country = form.country.trim().ifEmpty { null },
        appellation = form.appellation.trim().ifEmpty { null }, grapes = form.grapes.toList(),
    )
    val expected = typed.expectedOrBundledProfile
    val tags = rememberRetained("discovery.tags") { mutableStateListOf<String>().apply { existing?.tags?.let { addAll(it) } } }
    var pendingTag by rememberSaveable { mutableStateOf("") }
    var wantsToBuy by rememberSaveable { mutableStateOf(existing?.wantsToBuy ?: false) }
    var price by rememberSaveable { mutableStateOf(existing?.price?.toPlainString().orEmpty()) }
    // Back, the back gesture and ✕ ask first when something was entered.
    val snapshot = form.snapshot() + listOf(setting, place, date, tasting.toTasting(), tags.toList(), pendingTag, wantsToBuy, price)
    val start = rememberRetained("discovery.start") { snapshot }
    val leave = app.encave.ui.components.rememberDiscardGuard(snapshot != start) { navigator.back() }

    fun addTags() {
        val all = Discovery.normalized(tags + pendingTag.split(","))
        tags.clear()
        tags.addAll(all)
        pendingTag = ""
    }

    fun save() {
        form.showErrors = true
        if (form.nameMissing || form.vintageInvalid) return
        addTags()
        val record = form.build()
        val priceValue = price.replace(",", ".").trim().toBigDecimalOrNull()
        val base = existing ?: Discovery(wineId = record.id)
        // The stars, score and note in the discovery's own fields (older versions read them);
        // the full note and the guided sheet in its tasting, dated like it.
        val written = tasting.toTasting()
        val discovery = base.copy(
            date = date, setting = setting, place = place.trim().ifEmpty { null }, rating = written.rating,
            score100 = app.encave.core.Score.kept(written.score100, written.rating), note = written.note,
            tasting = written.copy(date = date, rating = null, score100 = null, note = null, occasion = null).takeIf { it.isDetailed },
            tags = tags.toList(), price = priceValue,
            currency = if (priceValue == null) null else base.currency ?: cellar.settings.currency,
            wantsToBuy = wantsToBuy, boughtAt = if (wantsToBuy && !base.wantsToBuy) null else base.boughtAt,
        )
        if (existing == null) {
            viewModel.addDiscovery(discovery, record, form.newPhoto)
        } else {
            viewModel.updateDiscovery(discovery, record, form.newPhoto, form.photoRemoved, keepsExpected = !form.expectedChanged)
        }
        // Aromas noted: how they compare with what to expect, then back.
        val expectedAromas = record.expectedOrBundledProfile?.aromas.orEmpty()
        if (tasting.aromas.isNotEmpty() && expectedAromas.isNotEmpty()) {
            comparison = record.displayName to app.encave.core.AromaComparison.of(expectedAromas, tasting.aromas)
        } else {
            navigator.back()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // Close and Save at the edges of the form, not of a tablet.
                modifier = Modifier.fillMaxWidth().readableWidth().fillMaxWidth(),
                title = { Text(wine?.displayName ?: stringResource(R.string.discovery_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                    }
                },
                actions = { TextButton(onClick = ::save) { Text(stringResource(R.string.action_save)) } },
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).readableWidth()
                .padding(horizontal = Dimens.l, vertical = Dimens.s),
            verticalArrangement = Arrangement.spacedBy(Dimens.m),
        ) {
            Header(R.string.photo_section)
            PhotoSection(viewModel, form)
            Header(R.string.add_section_wine)
            WineEssentials(form, known)
            WineMoreDetails(form, known, showsTags = false)
            app.encave.ui.add.WineDetailsSection(form, collapsible = true, initiallyExpanded = wine?.hasMoreDetails == true)

            Header(R.string.discovery_where)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                Discovery.Setting.entries.forEach { option ->
                    FilterChip(
                        selected = setting == option,
                        onClick = { setting = if (setting == option) null else option },
                        label = { Text(stringResource(option.labelRes)) },
                        leadingIcon = { Icon(option.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
            }
            OutlinedTextField(
                value = place, onValueChange = { place = it },
                label = { Text(stringResource(R.string.discovery_place)) },
                placeholder = { Text(stringResource(R.string.discovery_place_hint)) },
                supportingText = { Text(stringResource(R.string.help_field_discovery_place)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            AssistChip(
                onClick = { pickingDate = true },
                label = { Text(Formats.day(date)) },
                leadingIcon = { Icon(Icons.Outlined.CalendarMonth, contentDescription = stringResource(R.string.field_date)) },
            )

            Hint(stringResource(R.string.help_where_footer))

            Header(R.string.discovery_impression)
            // What the AI (or the bundled profile) expects from the wine: one tap away, so it
            // does not give the game away, as on Apple.
            if (expected != null && (!expected.aromas.isNullOrEmpty() || expected.structure != null)) {
                var showsExpected by rememberSaveable { mutableStateOf(false) }
                app.encave.ui.drink.ShowMoreButton(
                    showsExpected, R.string.expected_show, R.string.expected_hide, Modifier.testTag("discovery.show_expected"),
                ) { showsExpected = !showsExpected }
                if (showsExpected) app.encave.ui.cellar.ExpectedProfileContent(expected, language = "")
            }
            // The same tasting as for a bottle of the cellar, with the game before it.
            if (!expected?.aromas.isNullOrEmpty()) {
                OutlinedButton(onClick = { playingGame = true }, modifier = Modifier.testTag("discovery.aroma_game")) {
                    Text(stringResource(R.string.game_title))
                }
                Hint(stringResource(R.string.game_drink_footer))
            }
            TastingFields(tasting, color = form.color, expected = expected, showsOccasion = false)

            Header(R.string.discovery_tags)
            val chosen = tags.map { it.lowercase() }.toSet()
            val shown = tags.toList() + cellar.discoveryTags.filter { it.lowercase() !in chosen }.take(12)
            if (shown.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    shown.forEach { tag ->
                        val selected = tag in tags
                        FilterChip(
                            selected = selected,
                            onClick = { if (selected) tags.remove(tag) else tags.add(tag) },
                            label = { Text(tag) },
                            leadingIcon = {
                                Icon(if (selected) Icons.Filled.Check else Icons.Outlined.Tag, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = pendingTag,
                onValueChange = { text -> pendingTag = text; if (text.contains(",")) addTags() },
                label = { Text(stringResource(R.string.discovery_add_tag)) },
                placeholder = { Text(stringResource(R.string.discovery_tag_hint)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Sentences),
                keyboardActions = KeyboardActions(onDone = { addTags() }),
                supportingText = { Text(stringResource(R.string.help_tags_footer)) },
            )

            Row(
                Modifier.fillMaxWidth().toggleable(wantsToBuy, role = Role.Switch) { wantsToBuy = it }.padding(vertical = Dimens.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.ShoppingCart, contentDescription = null)
                Text(stringResource(R.string.discovery_to_buy), Modifier.weight(1f).padding(start = Dimens.l), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = wantsToBuy, onCheckedChange = null)
            }
            OutlinedTextField(
                value = price,
                onValueChange = { text -> price = text.filter { it.isDigit() || it == '.' || it == ',' } },
                label = { Text(stringResource(R.string.discovery_price_seen)) },
                supportingText = { Text(stringResource(R.string.help_field_price_seen)) },
                suffix = { Text(existing?.currency ?: cellar.settings.currency) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Text(
                stringResource(R.string.discovery_to_buy_footer),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (existing != null) {
                // One entry, with Undo: no question first (core DeletionRule).
                OutlinedButton(
                    onClick = {
                        viewModel.deleteDiscovery(existing.id)
                        navigator.back()
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.l).testTag("action.delete_discovery"),
                ) {
                    ActionContent(stringResource(R.string.discovery_delete), Icons.Outlined.Delete)
                }
            }
            HelpLink(HelpTopic.Journal, R.string.help_link_journal)
        }
    }
    if (pickingDate) {
        DateDialog(initial = date, onPick = { picked -> picked?.let { date = it } }, onDismiss = { pickingDate = false }, allowClear = false)
    }
    if (playingGame) {
        AromaGameSheet(typed, bottleId = null, onDismiss = { playingGame = false }, copyFooter = R.string.game_copy_footer_save) { aromas, levels ->
            tasting.aromas = aromas
            levels.forEach { (attribute, level) -> tasting.setLevel(attribute, level) }
            tasting.expanded = true
        }
    }
    comparison?.let { (name, shown) ->
        ModalBottomSheet(onDismissRequest = { comparison = null; navigator.back() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Dimens.xl).padding(bottom = Dimens.l).navigationBarsPadding(),
            ) { app.encave.ui.cellar.AromaComparisonContent(name, shown, onDone = { comparison = null; navigator.back() }) }
        }
    }
}

@Composable
private fun Header(id: Int) {
    Text(
        stringResource(id), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = Dimens.s).semantics { heading() },
    )
}
