package app.encave.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.encave.EncaveApplication
import app.encave.MainActivity
import app.encave.R
import app.encave.core.WidgetSnapshot

/** "Tonight": the bottles to open now, the most urgent first. A row opens its wine. */
class TonightWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, LARGE))
    override val previewSizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as EncaveApplication
        WidgetData.current(app)
        provideContent {
            val snapshot by app.widgetSnapshots.snapshot.collectAsState()
            val dynamicColor by app.preferences.dynamicColor.collectAsState()
            EncaveGlanceTheme(dynamicColor) { TonightContent(snapshot) }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val snapshot = WidgetData.preview(context)
        provideContent { EncaveGlanceTheme(dynamicColor = true) { TonightContent(snapshot) } }
    }

    companion object {
        /** About 2 × 2 cells: the first bottle. */
        val SMALL = DpSize(110.dp, 110.dp)
        /** Wider: a list of bottles with their place. */
        val WIDE = DpSize(220.dp, 110.dp)
        val LARGE = DpSize(220.dp, 260.dp)
    }
}

class TonightWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TonightWidget()
}

@Composable
fun TonightContent(snapshot: WidgetSnapshot?) {
    val context = LocalContext.current
    val title = context.getString(R.string.nav_tonight)
    if (snapshot == null || snapshot.isEmpty) {
        EmptyCellar(title, R.drawable.ic_widget_tonight)
        return
    }
    val compact = LocalSize.current.width < TonightWidget.WIDE.width
    val first = snapshot.tonight.firstOrNull()
    // The small widget is one tap target: the first bottle, or the Tonight section.
    val open = if (compact && first != null) MainActivity.wineLink(first.wineId) else MainActivity.LINK_TONIGHT
    WidgetFrame(title, R.drawable.ic_widget_tonight, openLink(context, open)) {
        when {
            first == null -> Text(
                context.getString(R.string.widget_tonight_none), maxLines = 4,
                style = TextStyle(fontSize = 14.sp, color = GlanceTheme.colors.onSurfaceVariant),
            )
            compact -> PickRow(first, compact = true)
            else -> LazyColumn {
                items(snapshot.tonight, itemId = { it.wineId.hashCode().toLong() }) { pick ->
                    Column(GlanceModifier.fillMaxWidth().clickable(openLink(context, MainActivity.wineLink(pick.wineId)))) {
                        PickRow(pick, compact = false)
                        Spacer(GlanceModifier.height(WidgetDimens.s))
                    }
                }
            }
        }
    }
}

/**
 * A wine to open: name and vintage, maturity (symbol and words, never color alone),
 * and, unless compact, where it is and how many bottles. Read as one sentence by TalkBack.
 */
@Composable
fun PickRow(pick: WidgetSnapshot.Pick, compact: Boolean) {
    val context = LocalContext.current
    val maturity = pick.maturity.label(context)
    val count = context.resources.getQuantityString(R.plurals.bottle_count, pick.bottles, pick.bottles)
    val name = listOfNotNull(pick.name, pick.vintage?.toString()).joinToString(" ")
    val description = listOfNotNull(name, maturity, pick.place.takeUnless { compact }, count).joinToString(", ")
    Column(GlanceModifier.fillMaxWidth().semantics { contentDescription = description }) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                name, maxLines = if (compact) 3 else 1, modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSurface),
            )
            if (!compact) {
                Spacer(GlanceModifier.width(WidgetDimens.xs))
                Text(
                    context.getString(R.string.widget_times, pick.bottles), maxLines = 1,
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSurfaceVariant),
                )
            }
        }
        Spacer(GlanceModifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconLine(pick.maturity.widgetIcon, maturity, pick.maturity.widgetTint, textColor = pick.maturity.widgetTint)
            val place = pick.place
            if (!compact && place != null) {
                Spacer(GlanceModifier.width(WidgetDimens.s))
                IconLine(R.drawable.ic_widget_place, place, GlanceTheme.colors.onSurfaceVariant)
            }
        }
    }
}
