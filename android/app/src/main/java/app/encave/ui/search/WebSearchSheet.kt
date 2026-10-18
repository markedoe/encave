package app.encave.ui.search

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.EncaveApplication
import app.encave.R
import app.encave.core.search.FoundPrice
import app.encave.core.search.PriceExtractor
import app.encave.core.search.WebSearchError
import app.encave.core.search.WebSearchResult
import app.encave.data.search.WebSearchClient
import app.encave.data.search.WebSearchPreferences
import app.encave.ui.components.Formats
import app.encave.ui.theme.Dimens
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The web search settings of the app. */
@Composable
fun webSearchPreferences(): WebSearchPreferences = (LocalContext.current.applicationContext as EncaveApplication).webSearch

/** For "Find current price": the bottle size, the cellar currency, the price kept now and how to save one. */
data class PriceOptions(
    val sizeML: Int,
    val currency: String,
    val current: BigDecimal?,
    val onSave: (BigDecimal, String) -> Unit,
)

/** What went wrong, in words. */
@Composable
fun webSearchErrorText(error: Throwable): String = when (error) {
    WebSearchError.MissingKey -> stringResource(R.string.ai_err_missing_key)
    WebSearchError.InvalidServerUrl -> stringResource(R.string.search_err_server_url)
    WebSearchError.Unauthorized -> stringResource(R.string.search_err_unauthorized)
    WebSearchError.JsonFormatDisabled -> stringResource(R.string.search_err_json)
    WebSearchError.RateLimited -> stringResource(R.string.search_err_rate)
    WebSearchError.InvalidResponse -> stringResource(R.string.search_err_invalid)
    is WebSearchError.Server ->
        if (error.status == 0) stringResource(R.string.search_err_network) else stringResource(R.string.search_err_server, error.status)
    else -> stringResource(R.string.search_err_network)
}

internal fun openLink(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
}

/**
 * "Search online" and "Find current price": shows the search terms, sends them only when
 * the user taps Search, and lists the pages found with links to the browser. With [price],
 * also lists every price found with its site, suggests the middle one in the cellar
 * currency and keeps a price only when the user saves it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebSearchSheet(initialQuery: String, onDismiss: () -> Unit, price: PriceOptions? = null) {
    val preferences = webSearchPreferences()
    val settings by preferences.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(initialQuery) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var results by remember { mutableStateOf<List<WebSearchResult>>(emptyList()) }
    var prices by remember { mutableStateOf<List<FoundPrice>>(emptyList()) }
    var currency by remember { mutableStateOf(price?.currency.orEmpty()) }
    var priceText by remember { mutableStateOf(price?.current?.toPlainString().orEmpty()) }
    val priceValue = priceText.trim().replace(",", ".").toBigDecimalOrNull()?.takeIf { it.signum() > 0 }

    fun search() {
        val terms = query.trim()
        if (terms.isEmpty() || searching || !settings.isEnabled) return
        searching = true
        error = null
        val current = settings
        scope.launch {
            val key = withContext(Dispatchers.IO) { preferences.apiKey(current.service) }
            runCatching { WebSearchClient.search(terms, current, key, count = if (price != null) 20 else 10) }
                .onSuccess { found ->
                    results = found
                    if (price != null) {
                        prices = PriceExtractor.prices(found, price.sizeML)
                        PriceExtractor.suggested(prices, currency)?.let { priceText = it.toPlainString() }
                    }
                }
                .onFailure {
                    results = emptyList()
                    prices = emptyList()
                    error = it
                }
            searched = true
            searching = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().testTag("webSearch.sheet")) {
            item {
                Column(Modifier.padding(horizontal = Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
                    Text(
                        stringResource(if (price != null) R.string.search_find_price else R.string.search_online),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.search_terms)) },
                        supportingText = { Text(stringResource(R.string.search_terms_footer, settings.service.brand.orEmpty())) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("webSearch.query"),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search() }),
                    )
                    Button(
                        onClick = ::search,
                        enabled = !searching && query.isNotBlank() && settings.isEnabled,
                        modifier = Modifier.testTag("webSearch.search"),
                    ) {
                        if (searching) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.search_searching), Modifier.padding(start = Dimens.s))
                        } else {
                            Icon(Icons.Outlined.Search, contentDescription = null)
                            Text(stringResource(R.string.search_action), Modifier.padding(start = Dimens.s))
                        }
                    }
                    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        error?.let { problem ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Text(webSearchErrorText(problem), color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (searched && error == null && results.isEmpty() && !searching) {
                            Text(stringResource(R.string.search_no_results), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            if (price != null) {
                item {
                    PriceField(
                        priceText = priceText, onPriceText = { priceText = it }, currency = currency,
                        hint = when {
                            !searched -> null
                            prices.count { it.currency == currency } == 1 -> stringResource(R.string.price_only_one, currency)
                            prices.count { it.currency == currency } > 1 ->
                                stringResource(R.string.price_middle, prices.count { it.currency == currency }, currency)
                            prices.isNotEmpty() -> stringResource(R.string.price_none_in_currency, currency)
                            else -> null
                        },
                        canSave = priceValue != null,
                        onSave = {
                            priceValue?.let { price.onSave(it, currency) }
                            onDismiss()
                        },
                    )
                }
                if (searched && error == null && !searching) {
                    item { SheetHeader(stringResource(R.string.price_found)) }
                    if (prices.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.price_none_found),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = Dimens.l),
                            )
                        }
                    }
                    items(prices, key = { "p" + it.id }) { found ->
                        FoundPriceRow(
                            found, selected = priceValue?.compareTo(found.amount) == 0 && currency == found.currency,
                            onUse = {
                                priceText = found.amount.toPlainString()
                                currency = found.currency
                            },
                            onOpen = { openLink(context, found.url) },
                        )
                    }
                    item {
                        Text(
                            stringResource(R.string.price_found_footer),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Dimens.l),
                        )
                    }
                }
            }

            if (results.isNotEmpty()) {
                item { SheetHeader(stringResource(R.string.search_results)) }
                items(results, key = { "r" + it.url }) { result -> ResultRow(result) { openLink(context, result.url) } }
                item {
                    Text(
                        stringResource(R.string.search_links_footer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Dimens.l),
                    )
                }
            }
            item { Spacer(Modifier.height(Dimens.xxl)) }
        }
    }
}

@Composable
private fun SheetHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = Dimens.l).padding(top = Dimens.l, bottom = Dimens.xs).semantics { heading() },
    )
}

@Composable
private fun PriceField(
    priceText: String,
    onPriceText: (String) -> Unit,
    currency: String,
    hint: String?,
    canSave: Boolean,
    onSave: () -> Unit,
) {
    Column(Modifier.padding(horizontal = Dimens.l).padding(top = Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
        SheetTitle(stringResource(R.string.price_today))
        OutlinedTextField(
            value = priceText,
            onValueChange = onPriceText,
            label = { Text(stringResource(R.string.price_per_bottle, currency)) },
            supportingText = { Text(hint ?: stringResource(R.string.price_footer)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("price.value"),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        OutlinedButton(onClick = onSave, enabled = canSave, modifier = Modifier.testTag("price.save")) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null)
            Text(stringResource(R.string.price_save), Modifier.padding(start = Dimens.s))
        }
    }
}

@Composable
private fun SheetTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
}

/** A price found: the amount, the words around it and the site. Tap to use it. */
@Composable
private fun FoundPriceRow(found: FoundPrice, selected: Boolean, onUse: () -> Unit, onOpen: () -> Unit) {
    ListItem(
        modifier = Modifier.selectable(selected = selected, onClick = onUse, role = Role.RadioButton),
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        headlineContent = { Text(Formats.money(found.amount, found.currency), style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            Column {
                Text(found.excerpt, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(found.domain, color = MaterialTheme.colorScheme.primary)
            }
        },
        trailingContent = {
            IconButton(onClick = onOpen) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.search_open_in_browser))
            }
        },
    )
}

/** A page found: title, snippet and site, opening in the browser. */
@Composable
private fun ResultRow(result: WebSearchResult, onOpen: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.search_open_in_browser), onClick = onOpen),
        leadingContent = { Icon(Icons.Outlined.Public, contentDescription = null) },
        headlineContent = { Text(result.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                if (result.snippet.isNotEmpty()) Text(result.snippet, maxLines = 5, overflow = TextOverflow.Ellipsis)
                Text(result.domain, color = MaterialTheme.colorScheme.primary)
            }
        },
    )
}
