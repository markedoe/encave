package app.encave.ui.journal

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.material.icons.outlined.Share
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import app.encave.core.Occasion
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.R
import app.encave.ui.components.readableWidth
import app.encave.core.CellarStatistics
import app.encave.core.Maturity
import app.encave.core.statistics
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import app.encave.ui.AppNavigator
import app.encave.ui.AppViewModel
import app.encave.ui.components.EmptyState
import app.encave.ui.components.Formats
import app.encave.ui.components.WineColorDot
import app.encave.ui.components.icon
import app.encave.ui.components.labelRes
import app.encave.ui.theme.Dimens
import app.encave.ui.theme.LocalWinePalette
import java.time.format.TextStyle
import java.time.ZoneId
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.WineBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import app.encave.core.Cellar
import app.encave.core.DrinkDownPlan
import app.encave.core.drinkDownPlan
import app.encave.core.yearInWine
import app.encave.ui.components.tint

/**
 * Cellar figures. Every chart shows its numbers as text next to the marks and
 * is read by TalkBack as a list of "label: value", so nothing relies on color.
 */
@Composable
fun InsightsScreen(viewModel: AppViewModel, navigator: AppNavigator) {
    val cellar by viewModel.cellar.collectAsStateWithLifecycle()
    val stats = remember(cellar) { cellar.statistics(viewModel.year) }
    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(app.encave.ui.components.LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.action_insights)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        if (stats.bottleCount == 0 && stats.consumedCount == 0 && cellar.discoveries.isEmpty()) {
            EmptyState(Icons.Outlined.Insights, stringResource(R.string.insights_empty_title), stringResource(R.string.insights_empty), Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).readableWidth(900.dp).padding(Dimens.l),
            verticalArrangement = Arrangement.spacedBy(Dimens.l),
        ) {
            YearInWineCard(cellar)
            // Two tiles a row on phones, four on wider screens, filling the width.
            val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Dimens.m), verticalArrangement = Arrangement.spacedBy(Dimens.m),
                maxItemsInEachRow = if (wide) 4 else 2,
            ) {
                StatTile(Formats.integer(stats.bottleCount), pluralStringResource(R.plurals.stat_bottles_in_cellar, stats.bottleCount))
                StatTile(Formats.integer(stats.wineCount), pluralStringResource(R.plurals.stat_different_wines, stats.wineCount))
                StatTile(Formats.integer(stats.consumedCount), pluralStringResource(R.plurals.stat_bottles_opened, stats.consumedCount))
                StatTile(
                    stats.averageRating?.let { stringResource(R.string.insights_rating_value, Formats.decimal(it)) } ?: "–",
                    stringResource(R.string.insights_avg_rating),
                )
            }
            ChartCard(stringResource(R.string.insights_value)) {
                if (stats.valueByCurrency.isEmpty()) {
                    Text(stringResource(R.string.insights_value_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    stats.valueByCurrency.toSortedMap().forEach { (code, amount) ->
                        Text(Formats.money(amount, code), style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
            if (stats.estimatedValueByCurrency.isNotEmpty()) {
                ChartCard(stringResource(R.string.insights_estimated)) {
                    stats.estimatedValueByCurrency.toSortedMap().forEach { (code, amount) ->
                        Text(Formats.money(amount, code), style = MaterialTheme.typography.headlineSmall)
                    }
                    Text(
                        stringResource(R.string.insights_estimated_count, stats.pricedBottleCount, stats.bottleCount),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            DrinkDownCard(cellar, viewModel.year)
            if (stats.byColor.isNotEmpty()) ChartCard(stringResource(R.string.insights_by_style)) { StyleDonut(stats) }
            if (stats.byMaturity.isNotEmpty()) {
                ChartCard(stringResource(R.string.insights_by_maturity)) {
                    Bars(
                        Maturity.entries.filter { (stats.byMaturity[it] ?: 0) > 0 }.map { m ->
                            BarItem(stringResource(m.labelRes), stats.byMaturity.getValue(m)) {
                                Icon(m.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        },
                    )
                }
            }
            if (stats.byCountry.isNotEmpty()) ChartCard(stringResource(R.string.insights_by_country)) { Bars(stats.byCountry.map { BarItem(it.label, it.count) }) }
            if (stats.byRegion.isNotEmpty()) ChartCard(stringResource(R.string.insights_by_region)) { Bars(stats.byRegion.map { BarItem(it.label, it.count) }) }
            if (stats.byVintageDecade.isNotEmpty()) {
                val nv = stringResource(R.string.insights_nv)
                val resources = LocalResources.current
                ChartCard(stringResource(R.string.insights_by_decade)) {
                    Bars(
                        stats.byVintageDecade.map {
                            // "2010s" from the core: "the 2010s" in the user's language ("années 2010").
                            val decade = CellarStatistics.decadeStart(it.label)
                            val label = when {
                                it.label == CellarStatistics.NON_VINTAGE -> nv
                                decade != null -> resources.getString(R.string.insights_decade, decade)
                                else -> it.label
                            }
                            BarItem(label, it.count)
                        },
                    )
                }
            }
            if (stats.topGrapes.isNotEmpty()) ChartCard(stringResource(R.string.insights_grapes)) { Bars(stats.topGrapes.map { BarItem(it.label, it.count) }) }
            ChartCard(stringResource(R.string.insights_by_month)) { MonthColumns(stats.consumptionByMonth) }
        }
    }
}

@Composable
/** A figure above its caption, as in the cellar summary. */
private fun androidx.compose.foundation.layout.FlowRowScope.StatTile(value: String, caption: String) {
    Card(
        Modifier.weight(1f).semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(Dimens.radiusCard),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(Dimens.l)) {
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Text(caption, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChartCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.radiusCard),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

/** Donut of bottles by style in the wine token colors, with a labeled legend (count and share). */
@Composable
private fun StyleDonut(stats: CellarStatistics) {
    val palette = LocalWinePalette.current
    val gap = MaterialTheme.colorScheme.surfaceContainerLow
    val total = stats.byColor.sumOf { it.count }.coerceAtLeast(1)
    val labels = stats.byColor.map { stringResource(it.color.labelRes) }
    val resources = LocalResources.current
    val description = stats.byColor.zip(labels).joinToString(", ") { (slice, label) ->
        resources.getQuantityString(R.plurals.chart_item_bottles, slice.count, label, slice.count)
    }
    // Whole percentages that add up to 100, written the user's way ("12 %" in French, "12%" in English).
    val shares = CellarStatistics.percentages(stats.byColor.map { it.count })
    val percent = java.text.NumberFormat.getPercentInstance(LocalConfiguration.current.locales[0])
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.xl)) {
        Canvas(Modifier.size(140.dp).clearAndSetSemantics { contentDescription = description }) {
            val stroke = size.minDimension * 0.18f
            val inset = stroke / 2
            var start = -90f
            stats.byColor.forEach { slice ->
                val sweep = 360f * slice.count / total
                drawArc(
                    palette[slice.color], startAngle = start, sweepAngle = sweep, useCenter = false,
                    topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(stroke, cap = StrokeCap.Butt),
                )
                // 2dp surface gap between segments.
                if (stats.byColor.size > 1) {
                    drawArc(
                        gap, startAngle = start - 0.8f, sweepAngle = 1.6f, useCenter = false,
                        topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(stroke * 1.05f),
                    )
                }
                start += sweep
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.s), modifier = Modifier.clearAndSetSemantics { }) {
            stats.byColor.zip(labels).forEachIndexed { index, (slice, label) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    WineColorDot(slice.color)
                    val share = percent.format(shares[index] / 100.0)
                    Text("$label · ${Formats.integer(slice.count)} ($share)", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private class BarItem(val label: String, val value: Int, val icon: (@Composable () -> Unit)? = null)

/** Horizontal bars in one hue (magnitude), each with its label and value as text. */
@Composable
private fun Bars(items: List<BarItem>) {
    val max = items.maxOfOrNull { it.value }?.coerceAtLeast(1) ?: 1
    val bar = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
        items.forEach { item ->
            val description = pluralStringResource(R.plurals.chart_item_bottles, item.value, item.label, item.value)
            Column(Modifier.clearAndSetSemantics { contentDescription = description }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                    item.icon?.invoke()
                    Text(item.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Formats.integer(item.value), style = MaterialTheme.typography.labelLarge)
                }
                Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                    val radius = CornerRadius(4.dp.toPx())
                    drawRoundRect(track, size = size, cornerRadius = radius)
                    drawRoundRect(bar, size = Size(size.width * item.value / max, size.height), cornerRadius = radius)
                }
            }
        }
    }
}

/** Twelve columns, oldest month first, with month initials and values. */
@Composable
private fun MonthColumns(months: List<CellarStatistics.MonthCount>) {
    val max = months.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
    val bar = MaterialTheme.colorScheme.primary
    val baseline = MaterialTheme.colorScheme.outlineVariant
    val locale = LocalConfiguration.current.locales[0]
    val resources = LocalResources.current
    // "October 2026: 3 bottles", with the unit.
    val description = months.joinToString(", ") {
        val name = "${it.month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale)} ${it.month.year}"
        resources.getQuantityString(R.plurals.chart_item_bottles, it.count, name, it.count)
    }
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        months.forEach { month ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Text(if (month.count > 0) month.count.toString() else "", style = MaterialTheme.typography.labelSmall)
                Box(Modifier.height(96.dp).width(14.dp), contentAlignment = Alignment.BottomCenter) {
                    Canvas(Modifier.fillMaxSize()) {
                        val height = size.height * month.count / max
                        if (height > 0f) {
                            drawRoundRect(
                                bar, topLeft = Offset(0f, size.height - height), size = Size(size.width, height),
                                cornerRadius = CornerRadius(4.dp.toPx()),
                            )
                        } else {
                            drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height), 2.dp.toPx())
                        }
                    }
                }
                Text(
                    month.month.month.getDisplayName(TextStyle.NARROW_STANDALONE, locale),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** How many bottles a month to open so none passes its window, and when windows end. */
@Composable
private fun DrinkDownCard(cellar: Cellar, year: Int) {
    val month = remember { java.time.LocalDate.now().monthValue }
    val plan = remember(cellar, year) { cellar.drinkDownPlan(year, month) }
    if (plan.isEmpty) return
    ChartCard(stringResource(R.string.insights_drink_down)) {
        // "1.4", "3", "12": one decimal only for small paces that are not whole.
        val rounded = Math.round(plan.bottlesPerMonth * 10) / 10.0
        val whole = rounded == Math.rint(rounded)
        val pace = Formats.decimal(rounded, if (rounded < 10 && !whole) 1 else 0)
        Column(Modifier.semantics(mergeDescendants = true) {}.testTag("insights.drinkdown")) {
            Text(
                when {
                    rounded < 0.75 -> stringResource(R.string.insights_pace_less)
                    rounded <= 1 -> stringResource(R.string.insights_pace_one)
                    else -> stringResource(R.string.insights_pace, pace)
                },
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                stringResource(R.string.insights_pace_detail),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (plan.pastPeak > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                Icon(Maturity.PastPeak.icon, contentDescription = null, tint = Maturity.PastPeak.tint, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.insights_past_peak, plan.pastPeak), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (plan.years.size > 1 || (plan.years.firstOrNull()?.leaving ?: 0) > 0) {
            Text(stringResource(R.string.insights_windows_ending), style = MaterialTheme.typography.titleSmall)
            YearColumns(plan.years)
        }
        if (plan.withoutWindow > 0) {
            Text(
                stringResource(R.string.insights_without_window, plan.withoutWindow),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One column per year with its value; read by TalkBack as "year: bottles". */
@Composable
private fun YearColumns(years: List<DrinkDownPlan.Year>) {
    val max = years.maxOfOrNull { it.leaving }?.coerceAtLeast(1) ?: 1
    val bar = MaterialTheme.colorScheme.primary
    val baseline = MaterialTheme.colorScheme.outlineVariant
    val short = years.size > 8
    val resources = LocalResources.current
    // "2027: 3 bottles", with the unit.
    val description = years.joinToString(", ") {
        resources.getQuantityString(R.plurals.chart_item_bottles, it.leaving, it.year.toString(), it.leaving)
    }
    Row(
        Modifier.fillMaxWidth().testTag("insights.drinkdown.chart").clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        years.forEach { item ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Text(if (item.leaving > 0) item.leaving.toString() else "", style = MaterialTheme.typography.labelSmall)
                Box(Modifier.height(120.dp).width(16.dp), contentAlignment = Alignment.BottomCenter) {
                    Canvas(Modifier.fillMaxSize()) {
                        val height = size.height * item.leaving / max
                        if (height > 0f) {
                            drawRoundRect(
                                bar, topLeft = Offset(0f, size.height - height), size = Size(size.width, height),
                                cornerRadius = CornerRadius(3.dp.toPx()),
                            )
                        } else {
                            drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height), 2.dp.toPx())
                        }
                    }
                }
                Text(
                    if (short) "’" + (item.year % 100).toString().padStart(2, '0') else item.year.toString(),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
        }
    }
}

/** "Your year in wine": what was opened in a year, with a choice of year. */
@Composable
private fun YearInWineCard(cellar: Cellar) {
    val zone = ZoneId.systemDefault()
    val years = remember(cellar) {
        (cellar.consumedBottles.mapNotNull { it.tasting?.date } + cellar.discoveries.values.map { it.date })
            .map { it.atZone(zone).year }.distinct().sortedDescending()
    }
    if (years.isEmpty()) return
    var chosen by rememberSaveable { mutableStateOf<Int?>(null) }
    val shown = chosen?.takeIf { it in years } ?: years.first()
    val recap = remember(cellar, shown) { cellar.yearInWine(shown, zone) }
    val locale = LocalConfiguration.current.locales[0]
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The figures and facts, recorded to share them as an image.
    val layer = rememberGraphicsLayer()
    val shareTitle = stringResource(R.string.insights_year_share_title, shown.toString())
    val madeWith = stringResource(R.string.insights_year_made_with)
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current.density
    Card(
        Modifier.fillMaxWidth().testTag("insights.year"),
        shape = RoundedCornerShape(Dimens.radiusCard),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(R.string.insights_year_title), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(start = Dimens.s).semantics { heading() },
                )
                if (years.size > 1) {
                    Box {
                        val chooseLabel = stringResource(R.string.insights_year_choose)
                        TextButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "$chooseLabel, $shown" }) {
                            Text(shown.toString())
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            years.forEach { option ->
                                DropdownMenuItem(text = { Text(option.toString()) }, onClick = { chosen = option; menu = false })
                            }
                        }
                    }
                } else {
                    Text(shown.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(
                    onClick = {
                        scope.launch {
                            val facts = layer.toImageBitmap().asAndroidBitmap()
                            val uri = withContext(Dispatchers.IO) {
                                YearShareImage.write(
                                    context, facts, shareTitle, madeWith, density,
                                    background = colors.primaryContainer.toArgb(), text = colors.onPrimaryContainer.toArgb(),
                                    file = "year-in-wine-$shown.png",
                                )
                            }
                            uri?.let { YearShareImage.share(context, it, shareTitle) }
                        }
                    },
                    modifier = Modifier.testTag("insights.year.share"),
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.insights_year_share))
                }
            }
            Column(
                Modifier.drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                },
                verticalArrangement = Arrangement.spacedBy(Dimens.m),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.xl)) {
                    YearFigure(recap.bottlesOpened, pluralStringResource(R.plurals.stat_bottles_opened, recap.bottlesOpened))
                    YearFigure(recap.differentWines, pluralStringResource(R.plurals.stat_different_wines, recap.differentWines))
                    if (recap.discoveries > 0) YearFigure(recap.discoveries, pluralStringResource(R.plurals.stat_tasted_elsewhere, recap.discoveries))
                }
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    recap.favorite?.let { (name, rating) -> YearFact(Icons.Outlined.Star, androidx.compose.ui.res.pluralStringResource(R.plurals.insights_year_favorite, rating, name, rating)) }
                    recap.topRegion?.let { YearFact(Icons.Outlined.Map, stringResource(R.string.insights_year_region, it)) }
                    recap.topColor?.let { YearFact(Icons.Outlined.WineBar, stringResource(R.string.insights_year_style, stringResource(it.labelRes))) }
                    recap.topOccasion?.let { occasion ->
                        // A chip, counted the same in every language, shown in this one.
                        val name = Occasion.matching(occasion)?.let { stringResource(it.labelRes) } ?: occasion
                        YearFact(Icons.Outlined.Group, stringResource(R.string.insights_year_occasion, name))
                    }
                    recap.busiestMonth?.let { month ->
                        val name = java.time.Month.of(month).getDisplayName(TextStyle.FULL_STANDALONE, locale)
                        YearFact(Icons.Outlined.CalendarMonth, stringResource(R.string.insights_year_month, name))
                    }
                }
            }
        }
    }
}

/** "Your year in wine" as a PNG to share: a title, the figures and facts as shown, and where it comes from. */
private object YearShareImage {
    fun write(
        context: Context,
        facts: Bitmap,
        title: String,
        footer: String,
        density: Float,
        background: Int,
        text: Int,
        file: String,
    ): Uri? = runCatching {
        val margin = (20 * density).toInt()
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = text
            textSize = 20 * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = text
            alpha = 170
            textSize = 12 * density
        }
        val titleHeight = (titlePaint.fontSpacing + 8 * density).toInt()
        val footerHeight = (footerPaint.fontSpacing + 8 * density).toInt()
        val width = facts.width + 2 * margin
        val height = margin + titleHeight + facts.height + footerHeight + margin
        val image = createBitmap(width, height)
        Canvas(image).apply {
            drawColor(background)
            drawText(title, margin.toFloat(), margin - titlePaint.ascent(), titlePaint)
            drawBitmap(facts.copy(Bitmap.Config.ARGB_8888, false), margin.toFloat(), (margin + titleHeight).toFloat(), null)
            drawText(footer, margin.toFloat(), (margin + titleHeight + facts.height + 8 * density) - footerPaint.ascent(), footerPaint)
        }
        val folder = File(context.cacheDir, "share").apply { mkdirs() }
        val target = File(folder, file)
        target.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FileProvider.getUriForFile(context, "${context.packageName}.files", target)
    }.getOrNull()

    fun share(context: Context, uri: Uri, title: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(Intent.createChooser(send, title)) }
    }
}

@Composable
private fun YearFigure(value: Int, caption: String) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(Formats.integer(value), style = MaterialTheme.typography.headlineMedium)
        Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun YearFact(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
