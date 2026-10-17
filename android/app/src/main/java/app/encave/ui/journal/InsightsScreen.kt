package app.encave.ui.journal

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
import app.encave.core.CellarStatistics
import app.encave.core.Maturity
import app.encave.core.statistics
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

/**
 * Cellar figures. Every chart shows its numbers as text next to the marks and
 * is read by TalkBack as a list of "label: value", so nothing relies on color.
 */
@Composable
fun InsightsScreen(viewModel: AppViewModel, navigator: AppNavigator) {
    val cellar by viewModel.cellar.collectAsStateWithLifecycle()
    val stats = remember(cellar) { cellar.statistics(viewModel.year) }
    Scaffold(
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
        if (stats.bottleCount == 0 && stats.consumedCount == 0) {
            EmptyState(Icons.Outlined.Insights, stringResource(R.string.action_insights), stringResource(R.string.insights_empty), Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(Dimens.l),
            verticalArrangement = Arrangement.spacedBy(Dimens.l),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.m), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
                StatTile(stringResource(R.string.insights_bottles), stats.bottleCount.toString())
                StatTile(stringResource(R.string.insights_wines), stats.wineCount.toString())
                StatTile(stringResource(R.string.insights_opened), stats.consumedCount.toString())
                StatTile(
                    stringResource(R.string.insights_avg_rating),
                    stats.averageRating?.let { stringResource(R.string.insights_rating_value, Formats.decimal(it)) } ?: "–",
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
                ChartCard(stringResource(R.string.insights_by_decade)) {
                    Bars(stats.byVintageDecade.map { BarItem(if (it.label == CellarStatistics.NON_VINTAGE) nv else it.label, it.count) })
                }
            }
            if (stats.topGrapes.isNotEmpty()) ChartCard(stringResource(R.string.insights_grapes)) { Bars(stats.topGrapes.map { BarItem(it.label, it.count) }) }
            ChartCard(stringResource(R.string.insights_by_month)) { MonthColumns(stats.consumptionByMonth) }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String) {
    Card(
        Modifier.widthIn(min = 140.dp).semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(Dimens.radiusCard),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(Dimens.l)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineMedium)
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
    val description = stats.byColor.zip(labels).joinToString(", ") { (slice, label) -> "$label ${slice.count}" }
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
            stats.byColor.zip(labels).forEach { (slice, label) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    WineColorDot(slice.color)
                    Text("$label · ${slice.count} (${100 * slice.count / total} %)", style = MaterialTheme.typography.bodyMedium)
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
            val description = stringResource(R.string.chart_item, item.label, item.value)
            Column(Modifier.clearAndSetSemantics { contentDescription = description }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                    item.icon?.invoke()
                    Text(item.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.value.toString(), style = MaterialTheme.typography.labelLarge)
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
    val description = months.joinToString(", ") {
        "${it.month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale)} ${it.month.year}: ${it.count}"
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
