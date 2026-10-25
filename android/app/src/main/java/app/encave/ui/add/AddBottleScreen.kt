package app.encave.ui.add

import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import app.encave.ui.components.rememberDiscardGuard
import kotlinx.coroutines.launch
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlinx.coroutines.delay
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
import app.encave.core.CellarExport
import app.encave.core.PurchaseInfo
import app.encave.core.Rack
import app.encave.core.Slot
import app.encave.core.Wine
import app.encave.ui.AppNavigator
import app.encave.data.PendingDocument
import app.encave.ui.AppViewModel
import app.encave.ui.cellar.PendingDocumentsSection
import app.encave.core.AddFormLayout
import app.encave.core.AddFormSection
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
import app.encave.ui.components.spokenSizeLabel
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
    val scope = rememberCoroutineScope()
    val known = remember(cellar) { cellar.knownValues() }
    val routeWine = remember(route.wineId) { route.wineId?.let { runCatching { UUID.fromString(it) }.getOrNull() } }
    var existingId by rememberSaveable { mutableStateOf(routeWine?.toString()) }
    val existing = existingId?.let { id -> cellar.wines[UUID.fromString(id)] }
    // Kept while the activity is recreated (rotation, folding), with the photo being added.
    val form = rememberRetained("add.form") { WineFormState(null, year) }
    val formStart = rememberRetained("add.formStart") { form.snapshot() }
    var search by rememberSaveable { mutableStateOf("") }
    var moreDetails by rememberSaveable { mutableStateOf(false) }
    var quantity by rememberSaveable { mutableStateOf(1) }
    var sizeML by rememberSaveable { mutableStateOf(750) }

    val routeRack = route.rackId?.let { id -> cellar.racks.firstOrNull { it.id.toString() == id } }
    // The rack used last on this device, or the first one with room; positions stay automatic.
    val start = remember { initialLocation(cellar, routeRack, viewModel.preferences.lastAddLocation) }
    var inRack by rememberSaveable { mutableStateOf(start.rackId != null) }
    var rackId by rememberSaveable { mutableStateOf(start.rackId?.toString()) }
    val rack = rackId?.let { id -> cellar.racks.firstOrNull { it.id.toString() == id } }
    // Slots chosen by hand; until then the first free slots are suggested.
    val pickedSlots = rememberRetained("add.slots") {
        mutableStateListOf<Slot>().apply {
            if (routeRack != null && route.row >= 0 && route.column >= 0) add(Slot(routeRack.id, route.row, route.column, route.depth))
        }
    }
    val startSlots = rememberRetained("add.startSlots") { pickedSlots.toList() }
    var slotsTouched by rememberSaveable { mutableStateOf(pickedSlots.isNotEmpty()) }
    var storage by rememberSaveable { mutableStateOf(start.storage) }
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
    // Barcode read on the bottle: recognizes wines already in the cellar, or is kept with the new one.
    var scanning by rememberSaveable { mutableStateOf(route.scan) }
    var barcode by rememberSaveable { mutableStateOf<String?>(null) }
    var barcodeDismissed by rememberSaveable { mutableStateOf(false) }
    // Producers often keep one barcode for every vintage: all are offered, newest first.
    val barcodeMatches = barcode?.let { cellar.wines(it) }.orEmpty()
    // "It's a different wine": the wine of the cellar the form matched, not offered again.
    var dismissedMatchId by rememberSaveable { mutableStateOf<String?>(null) }
    // Documents picked here: attached to the wine when the bottles are added. Kept while the
    // activity is recreated; their temporary copies go if the screen is left without adding.
    val picked = rememberRetained("add.documents") { PickedDocuments(viewModel::discardDocuments) }
    var pendingDocuments by picked::documents
    var saving by remember { mutableStateOf(false) }
    val essentials = remember { BringIntoViewRequester() }
    val nameFocus = remember { FocusRequester() }
    val vintageFocus = remember { FocusRequester() }

    val freeSlots = rack?.let { cellar.freeSlots(it) } ?: emptyList()
    val slots: List<Slot> = when {
        ordered || !inRack || rack == null -> emptyList()
        slotsTouched -> pickedSlots.filter { it.rackId == rack.id && it in freeSlots }.take(quantity)
        else -> freeSlots.take(quantity)
    }

    // A wine of the cellar the new one would duplicate (same name, producer and vintage).
    val sameWine = if (existing != null) null else remember(form.name, form.producer, form.vintage, cellar) {
        cellar.wine(matching = Wine(id = form.id, name = form.name, producer = form.producer.trim().ifEmpty { null }, vintage = form.vintage))
    }?.takeIf { match -> match.id.toString() != dismissedMatchId && barcodeMatches.none { it.id == match.id } }

    val isDirty = existingId != routeWine?.toString() || form.snapshot() != formStart || quantity != 1 || sizeML != 750 ||
        price.isNotBlank() || purchaseDate != null || place.isNotBlank() || receivedFrom.isNotBlank() || ordered ||
        pickedSlots.toList() != startSlots || barcode != null || pendingDocuments.isNotEmpty() ||
        storage != start.storage || rackId != start.rackId?.toString() || inRack != (start.rackId != null)
    // Back, the back gesture and ✕ ask first when something was entered.
    val leave = rememberDiscardGuard(isDirty && !saving) { navigator.back() }

    fun save() {
        if (saving) return
        // A wine of the cellar without a label photo takes this one, and the AI's label frame for it.
        val chosen = existing?.let { if (it.photoId == null && form.newPhoto != null) it.copy(labelFrame = form.labelFrame) else it } ?: run {
            form.showErrors = true
            if (form.nameMissing || form.vintageInvalid) {
                // The field in error may be off screen: go to it.
                scope.launch {
                    essentials.bringIntoView()
                    runCatching { if (form.nameMissing) nameFocus.requestFocus() else vintageFocus.requestFocus() }
                }
                return
            }
            form.build()
        }
        val wine = barcode?.let { chosen.addingBarcode(it) } ?: chosen
        val purchase = PurchaseInfo(
            date = purchaseDate,
            // To the cent, as the CSV export writes it.
            price = price.replace(",", ".").trim().toBigDecimalOrNull()?.let(CellarExport::roundedPrice),
            currency = currency,
            place = place.trim().ifEmpty { null },
            receivedFrom = receivedFrom.trim().ifEmpty { null },
            storageNote = if (!ordered && (!inRack || rack == null)) storage.trim().ifEmpty { null } else null,
            sizeML = sizeML,
            isOrdered = ordered,
            expectedAt = if (ordered && hasExpectedDate) expectedAt else null,
        )
        val location = if (inRack && rack != null) "rack:${rack.id}" else "other:${storage.trim()}"
        saving = true
        viewModel.addBottles(
            wine, quantity, slots, purchase,
            // The label photo, for a new wine or one that has none yet.
            photo = form.newPhoto?.takeIf { existing == null || existing.photoId == null },
            boughtDiscoveryId = route.discoveryId?.let { runCatching { UUID.fromString(it) }.getOrNull() },
            pendingDocuments = pendingDocuments,
        ) { ok ->
            saving = false
            if (ok) {
                if (!ordered) viewModel.preferences.lastAddLocation = location
                picked.added = true
                navigator.back()
            } else {
                // A place may have been taken meanwhile (a sync): free places are suggested again.
                slotsTouched = false
                pickedSlots.clear()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // Close and Add at the edges of the form, not of a tablet.
                modifier = Modifier.fillMaxWidth().readableWidth().fillMaxWidth(),
                title = {
                    Text(
                        existing?.let { stringResource(R.string.add_title_existing, it.displayName) }
                            ?: stringResource(R.string.action_add_bottle),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                    }
                },
                actions = {
                    // Barcodes are read and matched on the device (zxing-cpp); a label photo is read
                    // only by the optional AI recognition.
                    IconButton(onClick = { scanning = true }, modifier = Modifier.testTag("action.scan_barcode")) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = stringResource(R.string.action_scan_barcode))
                    }
                    TextButton(onClick = ::save, enabled = !saving) { Text(stringResource(R.string.action_add)) }
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
                if (barcodeMatches.isNotEmpty() && barcodeMatches.none { it.id == existing?.id } && !barcodeDismissed) {
                    BarcodeMatchCard(
                        cellar, barcodeMatches,
                        onUse = { existingId = it.id.toString() },
                        onAnotherVintage = if (existing == null) ({ wine ->
                            form.fillFrom(wine)
                            barcodeDismissed = true
                            scope.launch {
                                essentials.bringIntoView()
                                runCatching { vintageFocus.requestFocus() }
                            }
                        }) else null,
                        onDismiss = { barcodeDismissed = true },
                    )
                } else if (existing == null || code !in existing.barcodes.orEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            stringResource(R.string.barcode_kept, code), style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).testTag("barcode.kept"),
                        )
                        IconButton(onClick = { barcode = null; barcodeDismissed = false }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.barcode_remove))
                        }
                    }
                }
            }
            // Where the bottles go: right under the quantity, near the top of the form.
            val location: @Composable () -> Unit = {
                if (!ordered) LocationSection(
                    cellar = cellar,
                    inRack = inRack && cellar.racks.isNotEmpty() && rack != null,
                    onInRack = { on ->
                        inRack = on
                        if (on && rack == null) rackId = (cellar.racks.firstOrNull { cellar.freeSlots(it).isNotEmpty() } ?: cellar.racks.firstOrNull())?.id?.toString()
                    },
                    rack = rack,
                    onRack = { rackId = it.id.toString(); slotsTouched = false; pickedSlots.clear() },
                    slots = slots,
                    quantity = quantity,
                    automatic = !slotsTouched,
                    onToggleCell = { place ->
                        if (!slotsTouched) {
                            pickedSlots.clear()
                            slotsTouched = true
                        }
                        // A tap takes the next free place in the cell (on a fridge drawn front and back, the place
                        // tapped); once it is full of picks, it clears them.
                        val next = freeSlots.firstOrNull { it in place && it !in pickedSlots }
                        if (next != null) {
                            pickedSlots.add(next)
                            if (pickedSlots.size > quantity) quantity = pickedSlots.size
                        } else {
                            pickedSlots.removeAll { it in place }
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
                PendingDocumentsSection(viewModel, pendingDocuments) { pendingDocuments = it }
            } else {
                // The wines of the cellar: a search, and with "by hand" the name typed too.
                val cellarWines: @Composable (byName: Boolean) -> Unit = { byName ->
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
                        val needle = if (byName && search.isBlank()) form.name else search
                        val suggestions = remember(needle, cellar) { cellar.wineSuggestions(needle, limit = 5) }
                        suggestions.forEach { wine ->
                            ListItem(
                                headlineContent = { Text(wine.displayName) },
                                supportingContent = { Text(wine.origin()) },
                                leadingContent = { WineColorDot(wine.color, size = 16.dp) },
                                modifier = Modifier.clickable {
                                    existingId = wine.id.toString()
                                    search = ""
                                }.testTag("add.cellar_wine"),
                            )
                        }
                        if (suggestions.isNotEmpty()) {
                            Hint(stringResource(R.string.help_field_existing))
                            HorizontalDivider()
                        }
                    }
                }
                val photo: @Composable () -> Unit = {
                    Header(R.string.photo_section)
                    PhotoSection(viewModel, form, onScanBarcode = { scanning = true })
                }
                val wineFields: @Composable () -> Unit = {
                    Header(R.string.add_section_wine)
                    WineEssentials(form, known, Modifier.bringIntoViewRequester(essentials), nameFocus = nameFocus, vintageFocus = vintageFocus)
                }
                val sameWineCard: @Composable () -> Unit = {
                    sameWine?.let { match ->
                        SameWineCard(
                            match, cellar.bottlesOf(match.id).size,
                            onUse = { existingId = match.id.toString() },
                            onDifferent = { dismissedMatchId = match.id.toString() },
                        )
                    }
                }
                if (route.scan) {
                    // Opened to scan: the order it has always had, the label photo before the name.
                    cellarWines(false)
                    photo()
                    wineFields()
                    sameWineCard()
                    QuantityRow(quantity) { quantity = it }
                    location()
                    PendingDocumentsSection(viewModel, pendingDocuments) { pendingDocuments = it }
                } else {
                    // By hand (core AddFormLayout.byHand): the wine first, its name the first field,
                    // then the bottles, where they go and their documents; the label photo after.
                    for (section in AddFormLayout.byHand) {
                        when (section) {
                            AddFormSection.Wine -> wineFields()
                            AddFormSection.CellarWines -> if (sameWine != null) sameWineCard() else cellarWines(true)
                            AddFormSection.Bottles -> QuantityRow(quantity) { quantity = it }
                            AddFormSection.Location -> location()
                            AddFormSection.Documents -> PendingDocumentsSection(viewModel, pendingDocuments) { pendingDocuments = it }
                            AddFormSection.Label -> photo()
                        }
                    }
                }
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
            Button(onClick = ::save, enabled = !saving, modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.l).testTag("action.add_confirm")) {
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
                barcodeDismissed = false
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

/** Where the form puts the bottles first: a rack (null: elsewhere) and the note for elsewhere. */
internal data class InitialLocation(val rackId: UUID?, val storage: String)

/**
 * The place of the rack opened from, else where the last bottles went on this device
 * ([last]: "rack:<id>" or "other:<where>") if that rack still has room, else the first
 * rack with room.
 */
internal fun initialLocation(cellar: Cellar, routeRack: Rack?, last: String?): InitialLocation {
    if (routeRack != null) return InitialLocation(routeRack.id, "")
    fun hasRoom(rack: Rack) = cellar.freeSlots(rack).isNotEmpty()
    val firstWithRoom = cellar.racks.firstOrNull(::hasRoom)?.id
    return when {
        last != null && last.startsWith("rack:") -> {
            val id = last.removePrefix("rack:")
            InitialLocation(cellar.racks.firstOrNull { it.id.toString() == id && hasRoom(it) }?.id ?: firstWithRoom, "")
        }
        last != null && last.startsWith("other:") -> InitialLocation(null, last.removePrefix("other:"))
        else -> InitialLocation(firstWithRoom, "")
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
private fun Header(id: Int, modifier: Modifier = Modifier) {
    Text(
        stringResource(id), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = Dimens.s).semantics { heading() },
    )
}

/**
 * A scanned barcode belongs to wines of the cellar: offer to add bottles of one. Producers
 * often keep one barcode for every vintage: each is listed, newest first, and Another
 * vintage starts a new wine with the same details.
 */
@Composable
private fun BarcodeMatchCard(
    cellar: Cellar,
    wines: List<Wine>,
    onUse: (Wine) -> Unit,
    onAnotherVintage: ((Wine) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val first = wines.first()
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.testTag("barcode.match"),
    ) {
        Column(Modifier.padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.barcode_match_title), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                IconButton(onClick = onDismiss, modifier = Modifier.testTag("barcode.match.dismiss")) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_dismiss))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                WineColorDot(first.color, size = 20.dp)
                Column(Modifier.weight(1f)) {
                    Text(if (wines.size == 1) first.displayName else first.name, style = MaterialTheme.typography.titleMedium)
                    Text(first.origin(), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (wines.size == 1) {
                Button(onClick = { onUse(first) }, modifier = Modifier.testTag("action.use_wine")) {
                    ActionContent(stringResource(R.string.action_use_this_wine), Icons.Filled.Check)
                }
            } else {
                wines.take(4).forEach { wine ->
                    val count = cellar.bottlesOf(wine.id).size
                    ListItem(
                        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        headlineContent = { Text(wine.vintage?.toString() ?: stringResource(R.string.non_vintage)) },
                        supportingContent = { Text(pluralStringResource(R.plurals.bottle_count, count, count)) },
                        trailingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.action_use_this_wine)) { onUse(wine) }
                            .testTag("action.use_wine.${wine.vintage ?: "nv"}"),
                    )
                }
            }
            if (onAnotherVintage != null) {
                OutlinedButton(
                    onClick = { onAnotherVintage(first) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onTertiaryContainer),
                    modifier = Modifier.testTag("action.another_vintage"),
                ) {
                    ActionContent(stringResource(R.string.action_another_vintage), Icons.Filled.Add)
                }
            }
        }
    }
}

/** The name typed is a wine already in the cellar: add to it, so its bottles and tastings stay together. */
@Composable
private fun SameWineCard(wine: Wine, bottles: Int, onUse: () -> Unit, onDifferent: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.testTag("add.same_wine"),
    ) {
        Column(
            Modifier.padding(Dimens.l).semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(Dimens.s),
        ) {
            Text(stringResource(R.string.barcode_match_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                WineColorDot(wine.color, size = 20.dp)
                Column(Modifier.weight(1f)) {
                    Text(wine.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(pluralStringResource(R.plurals.widget_bottles_in_cellar, bottles, bottles), style = MaterialTheme.typography.bodySmall)
                }
            }
            Hint(stringResource(R.string.same_wine_footer))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                Button(onClick = onUse, modifier = Modifier.testTag("add.same_wine.use")) {
                    ActionContent(stringResource(R.string.action_add_to_this_wine), Icons.Filled.Add)
                }
                TextButton(
                    onClick = onDifferent,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onTertiaryContainer),
                    modifier = Modifier.testTag("add.same_wine.different"),
                ) {
                    Text(stringResource(R.string.action_different_wine))
                }
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
            supportingContent = { Text(listOf(stringResource(wine.color.labelRes), wine.origin()).filter { it.isNotEmpty() }.joinToString(app.encave.ui.components.SEPARATOR)) },
            trailingContent = onChange?.let {
                {
                    TextButton(onClick = it, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) {
                        Text(stringResource(R.string.action_change))
                    }
                }
            },
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
    /** The first free places, until the user taps some. */
    automatic: Boolean,
    onToggleCell: (app.encave.ui.racks.RackPlace) -> Unit,
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
                if (automatic) stringResource(R.string.add_auto_slots) else stringResource(R.string.add_pick_slots, slots.size, quantity),
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
                onCellClick = { place, _ -> onToggleCell(place) },
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
            val spoken = spokenSizeLabel(ml)
            FilterChip(
                selected = sizeML == ml, onClick = { onChange(ml) }, label = { Text(stringResource(label)) },
                modifier = Modifier.semantics { contentDescription = spoken },
            )
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
fun EditWineScreen(viewModel: AppViewModel, navigator: AppNavigator, wineId: String, field: WineFormField? = null) {
    WhenLoaded(viewModel) { EditWineForm(viewModel, navigator, wineId, field) }
}

@Composable
private fun EditWineForm(viewModel: AppViewModel, navigator: AppNavigator, wineId: String, field: WineFormField?) {
    val cellar by viewModel.cellar.collectAsStateWithLifecycle()
    val wine = remember(wineId) { runCatching { UUID.fromString(wineId) }.getOrNull()?.let { cellar.wines[it] } }
    val form = rememberRetained("editWine.form") { WineFormState(wine, viewModel.year) }
    val formStart = rememberRetained("editWine.formStart") { form.snapshot() }
    // Back, the back gesture and ✕ ask first when something was changed.
    val leave = rememberDiscardGuard(form.snapshot() != formStart) { navigator.back() }
    val known = remember(cellar) { cellar.knownValues() }
    fun save() {
        form.showErrors = true
        if (wine == null || form.nameMissing || form.vintageInvalid) return
        viewModel.updateWine(form.build(), photo = form.newPhoto, removePhoto = form.photoRemoved)
        navigator.back()
    }
    Scaffold(
        modifier = Modifier.testTag("wine.edit"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.edit_wine_title)) },
                navigationIcon = {
                    IconButton(onClick = leave) {
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
            // Opened from a detail of the wine's page: scroll to its part of the form.
            val essentials = remember { BringIntoViewRequester() }
            val window = remember { BringIntoViewRequester() }
            val origin = remember { BringIntoViewRequester() }
            LaunchedEffect(field) {
                val requester = when (field) {
                    WineFormField.Name, WineFormField.Producer, WineFormField.Vintage, WineFormField.Style -> essentials
                    WineFormField.Window -> window
                    WineFormField.Appellation, WineFormField.Region, WineFormField.Country, WineFormField.Grapes -> origin
                    // Tags and notes: done by WineMoreDetails.
                    WineFormField.Tags, WineFormField.Notes, null -> null
                } ?: return@LaunchedEffect
                delay(FIELD_TARGET_DELAY_MS)
                requester.bringIntoView()
            }
            Header(R.string.photo_section)
            PhotoSection(viewModel, form)
            WineEssentials(form, known, Modifier.bringIntoViewRequester(essentials))
            Header(R.string.detail_window, Modifier.bringIntoViewRequester(window))
            DrinkingWindowField(form)
            Header(R.string.add_more_details, Modifier.bringIntoViewRequester(origin))
            WineMoreDetails(form, known, target = field)
        }
    }
}

/**
 * How many bottles: near the top of the form, right after the label photo or the chosen wine.
 * A case of 6 or 12 is one tap; the stepper also repeats while held.
 */
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
            Text(stringResource(R.string.quantity_case), style = MaterialTheme.typography.bodyMedium)
            listOf(6, 12).forEach { size ->
                val label = pluralStringResource(R.plurals.bottle_count, size, size)
                FilterChip(
                    selected = quantity == size, onClick = { onChange(size) },
                    label = { Text(size.toString()) },
                    modifier = Modifier.semantics { contentDescription = label }.testTag("add.case.$size"),
                )
            }
        }
        Hint("${pluralStringResource(R.plurals.bottle_count, quantity, quantity)} · ${stringResource(R.string.help_field_quantity)}")
    }
}

/** Documents picked in Add Bottles; their temporary copies go when the screen is left without adding. */
internal class PickedDocuments(private val discard: (List<PendingDocument>) -> Unit) : AutoCloseable {
    var documents by mutableStateOf(listOf<PendingDocument>())
    var added = false

    override fun close() {
        if (!added) discard(documents)
    }
}
