package app.encave.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.height
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.encave.EncaveApplication
import app.encave.MainActivity
import app.encave.R
import app.encave.core.Maturity
import app.encave.core.WidgetSnapshot

/** "Cellar": how many bottles, how many to drink soon or ready, and on order. Opens Tonight. */
class CellarWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single
    override val previewSizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as EncaveApplication
        WidgetData.current(app)
        provideContent {
            val snapshot by app.widgetSnapshots.snapshot.collectAsState()
            val dynamicColor by app.preferences.dynamicColor.collectAsState()
            EncaveGlanceTheme(dynamicColor) { CellarContent(snapshot) }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val snapshot = WidgetData.preview(context)
        provideContent { EncaveGlanceTheme(dynamicColor = true) { CellarContent(snapshot) } }
    }
}

class CellarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CellarWidget()
}

@Composable
fun CellarContent(snapshot: WidgetSnapshot?) {
    val context = LocalContext.current
    val title = context.getString(R.string.nav_cellar)
    if (snapshot == null || snapshot.isEmpty) {
        EmptyCellar(title, R.drawable.ic_widget_cellar)
        return
    }
    val resources = context.resources
    val bottles = resources.getQuantityString(R.plurals.bottle_count, snapshot.bottles, snapshot.bottles)
    val soon = context.getString(R.string.widget_drink_soon, snapshot.drinkSoon)
    val ready = context.getString(R.string.widget_ready, snapshot.ready)
    val onOrder = context.getString(R.string.widget_on_order, snapshot.onOrder).takeIf { snapshot.onOrder > 0 }
    val inCellar = resources.getQuantityString(R.plurals.widget_bottles_in_cellar, snapshot.bottles, snapshot.bottles)
    val description = listOfNotNull(inCellar, soon, ready, onOrder).joinToString(", ")
    WidgetFrame(title, R.drawable.ic_widget_cellar, openLink(context, MainActivity.LINK_TONIGHT)) {
        Column(GlanceModifier.semantics { contentDescription = description }) {
            Text(
                bottles, maxLines = 1,
                style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
            )
            Spacer(GlanceModifier.height(WidgetDimens.xs))
            IconLine(Maturity.DrinkSoon.widgetIcon, soon, Maturity.DrinkSoon.widgetTint)
            Spacer(GlanceModifier.height(2.dp))
            IconLine(Maturity.Ready.widgetIcon, ready, Maturity.Ready.widgetTint)
            if (onOrder != null) {
                Spacer(GlanceModifier.height(2.dp))
                IconLine(R.drawable.ic_widget_on_order, onOrder, GlanceTheme.colors.onSurfaceVariant)
            }
        }
    }
}
