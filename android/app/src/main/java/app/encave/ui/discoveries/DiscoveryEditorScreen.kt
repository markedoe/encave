package app.encave.ui.discoveries

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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.R
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
import app.encave.ui.components.Formats
import app.encave.ui.components.LocalSnackbarHostState
import app.encave.ui.components.RatingInput
import app.encave.ui.components.icon
import app.encave.ui.components.labelRes
import app.encave.ui.drink.DateDialog
import app.encave.ui.theme.Dimens
import java.time.Instant

/**
 * Remember a wine tasted or seen elsewhere: photo (read by AI when set up),
 * the wine, where, your impression, tags and the shopping list. Also edits one.
 */
@Composable
fun DiscoveryEditorScreen(viewModel: AppViewModel, navigator: AppNavigator, discoveryId: String?) {
    val cellar by viewModel.cellar.collectAsStateWithLifecycle()
    val existing = remember(discoveryId) { discoveryId?.let { id -> cellar.discoveries.values.firstOrNull { it.id.toString() == id } } }
    val wine = existing?.let { cellar.wines[it.wineId] }
    val form = remember(existing) { WineFormState(wine, viewModel.year) }
    val known = remember(cellar) { cellar.knownValues() }
    var setting by rememberSaveable { mutableStateOf(existing?.setting) }
    var place by rememberSaveable { mutableStateOf(existing?.place.orEmpty()) }
    var date by remember { mutableStateOf(existing?.date ?: Instant.now()) }
    var pickingDate by remember { mutableStateOf(false) }
    var rating by rememberSaveable { mutableStateOf(existing?.rating) }
    var note by rememberSaveable { mutableStateOf(existing?.note.orEmpty()) }
    val tags = remember { mutableStateListOf<String>().apply { existing?.tags?.let { addAll(it) } } }
    var pendingTag by rememberSaveable { mutableStateOf("") }
    var wantsToBuy by rememberSaveable { mutableStateOf(existing?.wantsToBuy ?: false) }
    var price by rememberSaveable { mutableStateOf(existing?.price?.toPlainString().orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }

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
        val discovery = base.copy(
            date = date, setting = setting, place = place.trim().ifEmpty { null }, rating = rating,
            note = note.trim().ifEmpty { null }, tags = tags.toList(), price = priceValue,
            currency = if (priceValue == null) null else base.currency ?: cellar.settings.currency,
            wantsToBuy = wantsToBuy, boughtAt = if (wantsToBuy && !base.wantsToBuy) null else base.boughtAt,
        )
        if (existing == null) {
            viewModel.addDiscovery(discovery, record, form.newPhoto)
        } else {
            viewModel.updateDiscovery(discovery, record, form.newPhoto, form.photoRemoved)
        }
        navigator.back()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (existing == null) R.string.discovery_new else R.string.discovery_title)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                    }
                },
                actions = { TextButton(onClick = ::save) { Text(stringResource(R.string.action_save)) } },
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.l, vertical = Dimens.s),
            verticalArrangement = Arrangement.spacedBy(Dimens.m),
        ) {
            Header(R.string.photo_section)
            PhotoSection(viewModel, form)
            Header(R.string.add_section_wine)
            WineEssentials(form, known)
            WineMoreDetails(form, known)

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
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            AssistChip(
                onClick = { pickingDate = true },
                label = { Text(Formats.day(date)) },
                leadingIcon = { Icon(Icons.Outlined.CalendarMonth, contentDescription = stringResource(R.string.field_date)) },
            )

            Header(R.string.discovery_impression)
            RatingInput(rating, onChange = { rating = it })
            OutlinedTextField(
                value = note, onValueChange = { note = it },
                label = { Text(stringResource(R.string.field_notes)) },
                placeholder = { Text(stringResource(R.string.discovery_note_hint)) },
                minLines = 3, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )

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
                suffix = { Text(existing?.currency ?: cellar.settings.currency) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Text(
                stringResource(R.string.discovery_to_buy_footer),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (existing != null) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.l),
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null)
                    Text(stringResource(R.string.discovery_delete), Modifier.padding(start = Dimens.s))
                }
            }
        }
    }
    if (pickingDate) {
        DateDialog(initial = date, onPick = { picked -> picked?.let { date = it } }, onDismiss = { pickingDate = false }, allowClear = false)
    }
    if (confirmDelete && existing != null) {
        ConfirmDialog(
            title = stringResource(R.string.discovery_delete_title),
            body = wine?.displayName.orEmpty(),
            confirm = stringResource(R.string.discovery_delete),
            onConfirm = {
                viewModel.deleteDiscovery(existing.id)
                navigator.back()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun Header(id: Int) {
    Text(
        stringResource(id), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = Dimens.s).semantics { heading() },
    )
}
