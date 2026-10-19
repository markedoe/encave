package app.encave.ui.add

import app.encave.ui.components.ActionContent
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.R
import app.encave.ui.components.readableWidth
import app.encave.core.Cellar
import app.encave.core.PurchaseInfo
import app.encave.core.Rack
import app.encave.core.Slot
import app.encave.core.Wine
import app.encave.ui.AppNavigator
import app.encave.ui.AppViewModel
import app.encave.ui.components.Formats
import app.encave.ui.components.Hint
import app.encave.ui.help.HelpLink
import app.encave.ui.help.HelpTopic
import app.encave.ui.components.LocalSnackbarHostState
import app.encave.ui.components.WhenLoaded
import app.encave.ui.components.rememberRetained
import app.encave.ui.components.Stepper
import app.encave.ui.components.WineColorDot
import app.encave.ui.components.bottleSizes
import app.encave.ui.components.commonCurrencies
import app.encave.ui.components.labelRes
import app.encave.ui.components.origin
import app.encave.ui.drink.DateDialog
import app.encave.ui.navigation.AddBottleRoute
import app.encave.ui.racks.RackGrid
import app.encave.ui.theme.Dimens
import java.time.Instant
import java.util.UUID

/** Wine fields known in the cellar, for autocompletion. */
fun Cellar.knownValues() = KnownValues(
    producers = knownValues { it.producer },
    regions = knownValues { it.region },
    countries = knownValues { it.country },
    appellations = knownValues { it.appellation },
    grapes = wines.values.flatMap { it.grapes }.distinct().sortedWith(Cellar.collator()),
    tags = wineTags,
)

/**
 * Add bottles: pick a wine already in the cellar or type a new one, then
 * quantity and location. Purchase details and the rest are collapsed, so a
 * new bottle takes a name and one tap.
 */
@Composable
fun AddBottleScreen(viewModel: AppViewModel, navigator: AppNavigator, route: AddBottleRoute) {
    WhenLoaded(viewModel) { AddBottleForm(viewModel, navigator, route) }
}

@Composable
private fun AddBottleForm(viewModel: AppViewModel, navigator: AppNavigator, route: AddBottleRoute) {
    val cellar by viewModel.cellar.collectAsStateWithLifecycle()
    val year = viewModel.year
    val known = remember(cellar) { cellar.knownValues() }
    val routeWine = remember(route.wineId) { route.wineId?.let { runCatching { UUID.fromString(it) }.getOrNull() } }
    var existingId by rememberSaveable { mutableStateOf(routeWine?.toString()) }
    val existing = existingId?.let { id -> cellar.wines[UUID.fromString(id)] }
    // Kept while the activity is recreated (rotation, folding), with the photo being added.
    val form = rememberRetained("add.form") { WineFormState(null, year) }
    var search by rememberSaveable { mutableStateOf("") }
    var moreDetails by rememberSaveable { mutableStateOf(false) }
    var quantity by rememberSaveable { mutableStateOf(1) }
    var sizeML by rememberSaveable { mutableStateOf(750) }

    val routeRack = route.rackId?.let { id -> cellar.racks.firstOrNull { it.id.toString() == id } }
    var inRack by rememberSaveable { mutableStateOf(cellar.racks.isNotEmpty()) }
    var rackId by rememberSaveable { mutableStateOf((routeRack ?: cellar.racks.firstOrNull())?.id?.toString()) }
    val rack = rackId?.let { id -> cellar.racks.firstOrNull { it.id.toString() == id } }
    // Slots chosen by hand; until then the first free slots are suggested.
    val pickedSlots = rememberRetained("add.slots") {
        mutableStateListOf<Slot>().apply {
            if (routeRack != null && route.row >= 0 && route.column >= 0) add(Slot(routeRack.id, route.row, route.column, route.depth))
        }
    }
    var slotsTouched by rememberSaveable { mutableStateOf(pickedSlots.isNotEmpty()) }
    var storage by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("") }
    var currency by rememberSaveable { mutableStateOf(cellar.settings.currency) }
    var purchaseDate by rememberSaveable { mutableStateOf<Instant?>(null) }
    var pickingDate by remember { mutableStateOf(false) }
    var place by rememberSaveable { mutableStateOf("") }
    var receivedFrom by rememberSaveable { mutableStateOf("") }
    // Bought but not delivered yet (en primeur, or an order on its way).
    var ordered by rememberSaveable { mutableStateOf(false) }
    var hasExpectedDate by rememberSaveable { mutableStateOf(false) }
    var expectedAt by rememberSaveable { mutableStateOf<Instant?>(null) }
    var pickingExpected by remember { mutableStateOf(false) }
    // Barcode read on the bottle: recognizes a wine already in the cellar, or is kept with the new one.
    var scanning by rememberSaveable { mutableStateOf(route.scan) }
    var barcode by rememberSaveable { mutableStateOf<String?>(null) }
    val barcodeMatch = barcode?.let { cellar.wine(it) }

    val freeSlots = rack?.let { cellar.freeSlots(it) } ?: emptyList()
    val slots: List<Slot> = when {
        ordered || !inRack || rack == null -> emptyList()
        slotsTouched -> pickedSlots.filter { it.rackId == rack.id && it in freeSlots }.take(quantity)
        else -> freeSlots.take(quantity)
    }

    fun save() {
        val chosen = existing ?: run {
            form.showErrors = true
            if (form.nameMissing || form.vintageInvalid) return
            form.build()
        }
        val wine = barcode?.let { chosen.addingBarcode(it) } ?: chosen
        val purchase = PurchaseInfo(
            date = purchaseDate,
            price = price.replace(",", ".").trim().toBigDecimalOrNull(),
            currency = currency,
            place = place.trim().ifEmpty { null },
            receivedFrom = receivedFrom.trim().ifEmpty { null },
            storageNote = if (!ordered && (!inRack || rack == null)) storage.trim().ifEmpty { null } else null,
            sizeML = sizeML,
            isOrdered = ordered,
            expectedAt = if (ordered && hasExpectedDate) expectedAt else null,
        )
        viewModel.addBottles(
            wine, quantity, slots, purchase,
            photo = if (existing == null) form.newPhoto else null,
            boughtDiscoveryId = route.discoveryId?.let { runCatching { UUID.fromString(it) }.getOrNull() },
        )
        navigator.back()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        existing?.let { stringResource(R.string.add_title_existing, it.displayName) }
                            ?: stringResource(R.string.action_add_bottle),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                    }
                },
                actions = {
                    // Barcodes are read and matched on the device (zxing-cpp); a label photo is read
                    // only by the optional AI recognition.
                    IconButton(onClick = { scanning = true }, modifier = Modifier.testTag("action.scan_barcode")) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = stringResource(R.string.action_scan_barcode))
                    }
                    TextButton(onClick = ::save) { Text(stringResource(R.string.action_add)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .readableWidth()
                .padding(horizontal = Dimens.l, vertical = Dimens.s),
            verticalArrangement = Arrangement.spacedBy(Dimens.m),
        ) {
            barcode?.let { code ->
                if (barcodeMatch != null && existing?.id != barcodeMatch.id) {
                    BarcodeMatchCard(barcodeMatch, onUse = { existingId = barcodeMatch.id.toString() })
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            stringResource(R.string.barcode_kept, code), style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).testTag("barcode.kept"),
                        )
                        IconButton(onClick = { barcode = null }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear_search))
                        }
                    }
                }
            }
            // Where the bottles go: right under the quantity, near the top of the form.
            val location: @Composable () -> Unit = {
                if (!ordered) LocationSection(
                    cellar = cellar,
                    inRack = inRack && cellar.racks.isNotEmpty(),
                    onInRack = { inRack = it },
                    rack = rack,
                    onRack = { rackId = it.id.toString(); slotsTouched = false; pickedSlots.clear() },
                    slots = slots,
                    quantity = quantity,
                    onToggleCell = { cell ->
                        if (!slotsTouched) {
                            pickedSlots.clear()
                            slotsTouched = true
                        }
                        // A tap takes the next free place in the cell; once the cell is full of picks, it clears them.
                        val next = freeSlots.firstOrNull { it.cell == cell && it !in pickedSlots }
                        if (next != null) {
                            pickedSlots.add(next)
                            if (pickedSlots.size > quantity) quantity = pickedSlots.size
                        } else {
                            pickedSlots.removeAll { it.cell == cell }
                        }
                    },
                    storage = storage,
                    onStorage = { storage = it },
                )
            }
            // Wine: existing one, or search + new wine form.
            if (existing != null) {
                ExistingWineCard(existing, onChange = if (routeWine == null) ({ existingId = null }) else null)
                QuantityRow(quantity) { quantity = it }
                location()
            } else {
                if (cellar.wines.isNotEmpty()) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        label = { Text(stringResource(R.string.add_search_hint)) },
                        supportingText = { Text(stringResource(R.string.help_field_search)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val suggestions = remember(search, cellar) { cellar.wineSuggestions(search, limit = 5) }
                    suggestions.forEach { wine ->
                        ListItem(
                            headlineContent = { Text(wine.displayName) },
                            supportingContent = { Text(wine.origin()) },
                            leadingContent = { WineColorDot(wine.color, size = 16.dp) },
                            modifier = Modifier.clickable {
                                existingId = wine.id.toString()
                                search = ""
                            },
                        )
                    }
                    if (suggestions.isNotEmpty()) {
                        Hint(stringResource(R.string.help_field_existing))
                        HorizontalDivider()
                    }
                }
                Header(R.string.photo_section)
                PhotoSection(viewModel, form)
                QuantityRow(quantity) { quantity = it }
                location()
                Header(R.string.add_section_wine)
                WineEssentials(form, known)
            }

            Header(R.string.add_section_bottles)
            Hint(stringResource(R.string.help_bottles_footer))

            OrderSection(
                ordered = ordered,
                onOrdered = { ordered = it },
                hasDate = hasExpectedDate,
                onHasDate = { on ->
                    hasExpectedDate = on
                    if (on && expectedAt == null) expectedAt = Instant.now().plus(java.time.Duration.ofDays(14))
                },
                date = expectedAt,
                onPickDate = { pickingExpected = true },
            )

            TextButton(onClick = { moreDetails = !moreDetails }) {
                Icon(if (moreDetails) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                Text(stringResource(if (moreDetails) R.string.add_fewer_details else R.string.add_more_details), Modifier.padding(start = Dimens.s))
            }
            AnimatedVisibility(moreDetails) {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
                    SizeField(sizeML) { sizeML = it }
                    Header(R.string.add_section_purchase)
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.m), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = price,
                            onValueChange = { text -> price = text.filter { it.isDigit() || it == '.' || it == ',' } },
                            label = { Text(stringResource(R.string.field_price)) },
                            supportingText = { Text(stringResource(R.string.help_field_price)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                        CurrencyField(currency, { currency = it }, Modifier.weight(0.8f))
                    }
                    AssistChip(
                        onClick = { pickingDate = true },
                        label = { Text(purchaseDate?.let(Formats::day) ?: stringResource(R.string.field_purchase_date)) },
                        leadingIcon = { Icon(Icons.Outlined.CalendarMonth, contentDescription = null) },
                    )
                    OutlinedTextField(
                        value = place, onValueChange = { place = it }, label = { Text(stringResource(R.string.field_purchase_place)) },
                        supportingText = { Text(stringResource(R.string.help_field_place)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    )
                    OutlinedTextField(
                        value = receivedFrom, onValueChange = { receivedFrom = it }, label = { Text(stringResource(R.string.field_received_from)) },
                        supportingText = { Text(stringResource(R.string.help_field_received)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    )
                    Hint(stringResource(R.string.help_purchase_footer))
                    if (existing == null) {
                        Header(R.string.detail_window)
                        DrinkingWindowField(form)
                        Header(R.string.add_more_details)
                        WineMoreDetails(form, known)
                    }
                }
            }
            if (existing == null && !moreDetails) {
                // The estimated window is still applied when details are collapsed.
                form.window?.let {
                    Text(
                        "${stringResource(R.string.detail_window)} · ${stringResource(R.string.detail_window_range, it.first, it.last)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(onClick = ::save, modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.l).testTag("action.add_confirm")) {
                ActionContent(pluralStringResource(R.plurals.bottles_added_action, quantity, quantity))
            }
            HelpLink(HelpTopic.Adding, R.string.help_link_adding)
        }
    }
    if (pickingDate) {
        DateDialog(initial = purchaseDate ?: Instant.now(), onPick = { purchaseDate = it }, onDismiss = { pickingDate = false })
    }
    if (scanning) {
        BarcodeScannerDialog(
            onScan = { code ->
                scanning = false
                barcode = code
            },
            onDismiss = { scanning = false },
        )
    }
    if (pickingExpected) {
        DateDialog(
            initial = expectedAt ?: Instant.now(), onPick = { if (it != null) expectedAt = it },
            onDismiss = { pickingExpected = false }, allowClear = false,
        )
    }
}

/** "Not delivered yet" and, when known, the expected delivery date. Bottles on order get no place. */
@Composable
private fun OrderSection(
    ordered: Boolean,
    onOrdered: (Boolean) -> Unit,
    hasDate: Boolean,
    onHasDate: (Boolean) -> Unit,
    date: Instant?,
    onPickDate: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().toggleable(ordered, role = Role.Switch, onValueChange = onOrdered).testTag("add.ordered"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.LocalShipping, contentDescription = null)
        Column(Modifier.weight(1f).padding(horizontal = Dimens.l)) {
            Text(stringResource(R.string.add_not_delivered), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.add_not_delivered_help),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = ordered, onCheckedChange = null)
    }
    if (ordered) {
        Row(
            Modifier.fillMaxWidth().toggleable(hasDate, role = Role.Switch, onValueChange = onHasDate).testTag("add.expected"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.add_expected_delivery), Modifier.weight(1f).padding(start = 40.dp), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = hasDate, onCheckedChange = null)
        }
        if (hasDate && date != null) {
            AssistChip(
                onClick = onPickDate,
                label = { Text(Formats.day(date)) },
                leadingIcon = { Icon(Icons.Outlined.CalendarMonth, contentDescription = null) },
                modifier = Modifier.padding(start = 40.dp),
            )
        }
        Hint(stringResource(R.string.add_order_footer), Modifier.testTag("add.order.footer"))
    }
}

@Composable
private fun Header(id: Int) {
    Text(
        stringResource(id), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = Dimens.s).semantics { heading() },
    )
}

/** A scanned barcode belongs to a wine of the cellar: offer to add bottles of it. */
@Composable
private fun BarcodeMatchCard(wine: Wine, onUse: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.testTag("barcode.match"),
    ) {
        Column(Modifier.padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
            Text(stringResource(R.string.barcode_match_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                WineColorDot(wine.color, size = 20.dp)
                Column(Modifier.weight(1f)) {
                    Text(wine.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(wine.origin(), style = MaterialTheme.typography.bodySmall)
                }
            }
            Button(onClick = onUse, modifier = Modifier.testTag("action.use_wine")) {
                ActionContent(stringResource(R.string.action_use_this_wine), Icons.Filled.Check)
            }
        }
    }
}

@Composable
private fun ExistingWineCard(wine: Wine, onChange: (() -> Unit)?) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        ListItem(
            colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            leadingContent = { WineColorDot(wine.color, size = 20.dp) },
            headlineContent = { Text(wine.displayName, style = MaterialTheme.typography.titleMedium) },
            supportingContent = { Text(listOf(stringResource(wine.color.labelRes), wine.origin()).filter { it.isNotEmpty() }.joinToString(" · ")) },
            trailingContent = onChange?.let { { TextButton(onClick = it) { Text(stringResource(R.string.action_change)) } } },
        )
    }
}

@Composable
private fun LocationSection(
    cellar: Cellar,
    inRack: Boolean,
    onInRack: (Boolean) -> Unit,
    rack: Rack?,
    onRack: (Rack) -> Unit,
    slots: List<Slot>,
    quantity: Int,
    onToggleCell: (app.encave.core.RackCell) -> Unit,
    storage: String,
    onStorage: (String) -> Unit,
) {
    Text(stringResource(R.string.field_location), style = MaterialTheme.typography.titleSmall)
    Hint(stringResource(R.string.help_location_footer))
    if (cellar.racks.isEmpty()) {
        Text(stringResource(R.string.add_no_racks), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(selected = inRack, onClick = { onInRack(true) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                Text(stringResource(R.string.add_location_rack))
            }
            SegmentedButton(selected = !inRack, onClick = { onInRack(false) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                Text(stringResource(R.string.add_location_other))
            }
        }
    }
    if (inRack && rack != null) {
        if (cellar.racks.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                cellar.racks.forEach { option ->
                    FilterChip(selected = option.id == rack.id, onClick = { onRack(option) }, label = { Text(option.name) })
                }
            }
        }
        val free = cellar.freeSlots(rack)
        if (free.isEmpty()) {
            Text(stringResource(R.string.add_rack_full), color = MaterialTheme.colorScheme.error)
        } else {
            Text(
                stringResource(R.string.add_pick_slots, slots.size, quantity),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val chooseLabel = stringResource(R.string.slot_choose)
            RackGrid(
                rack = rack,
                cellar = cellar,
                selected = slots.toSet(),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                cellSize = 48.dp,
                clickLabel = { chooseLabel },
                onCellClick = { cell, _ -> onToggleCell(cell) },
            )
        }
    } else {
        OutlinedTextField(
            value = storage, onValueChange = onStorage,
            label = { Text(stringResource(R.string.field_storage)) },
            supportingText = { Text(stringResource(R.string.help_field_storage)) },
            placeholder = { Text(stringResource(R.string.field_storage_hint)) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
    }
}

@Composable
private fun SizeField(sizeML: Int, onChange: (Int) -> Unit) {
    Text(stringResource(R.string.field_size), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
        bottleSizes.forEach { (ml, label) ->
            FilterChip(selected = sizeML == ml, onClick = { onChange(ml) }, label = { Text(stringResource(label)) })
        }
    }
    Hint(stringResource(R.string.help_field_size))
}

@Composable
fun CurrencyField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val options = (listOf(value) + commonCurrencies).distinct()
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.field_currency)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { code ->
                DropdownMenuItem(text = { Text(code) }, onClick = { onChange(code); expanded = false })
            }
        }
    }
}

/** Edit the wine record itself (all its bottles share it). */
@Composable
fun EditWineScreen(viewModel: AppViewModel, navigator: AppNavigator, wineId: String) {
    WhenLoaded(viewModel) { EditWineForm(viewModel, navigator, wineId) }
}

@Composable
private fun EditWineForm(viewModel: AppViewModel, navigator: AppNavigator, wineId: String) {
    val cellar by viewModel.cellar.collectAsStateWithLifecycle()
    val wine = remember(wineId) { runCatching { UUID.fromString(wineId) }.getOrNull()?.let { cellar.wines[it] } }
    val form = rememberRetained("editWine.form") { WineFormState(wine, viewModel.year) }
    val known = remember(cellar) { cellar.knownValues() }
    fun save() {
        form.showErrors = true
        if (wine == null || form.nameMissing || form.vintageInvalid) return
        viewModel.updateWine(form.build(), photo = form.newPhoto, removePhoto = form.photoRemoved)
        navigator.back()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.edit_wine_title)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                    }
                },
                actions = { TextButton(onClick = ::save, enabled = wine != null) { Text(stringResource(R.string.action_save)) } },
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
    ) { padding ->
        if (wine == null) {
            Text(stringResource(R.string.wine_not_found), Modifier.padding(padding).padding(Dimens.l))
            return@Scaffold
        }
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).readableWidth()
                .padding(horizontal = Dimens.l, vertical = Dimens.s),
            verticalArrangement = Arrangement.spacedBy(Dimens.m),
        ) {
            Header(R.string.photo_section)
            PhotoSection(viewModel, form)
            WineEssentials(form, known)
            Header(R.string.detail_window)
            DrinkingWindowField(form)
            Header(R.string.add_more_details)
            WineMoreDetails(form, known)
        }
    }
}

/** How many bottles: near the top of the form, right after the label photo or the chosen wine. */
@Composable
private fun QuantityRow(quantity: Int, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.field_quantity), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Stepper(
                value = quantity, onChange = onChange, range = 1..48,
                decreaseLabel = stringResource(R.string.quantity_decrease),
                increaseLabel = stringResource(R.string.quantity_increase),
                valueText = quantity.toString(),
            )
        }
        Hint("${pluralStringResource(R.plurals.bottle_count, quantity, quantity)} · ${stringResource(R.string.help_field_quantity)}")
    }
}
