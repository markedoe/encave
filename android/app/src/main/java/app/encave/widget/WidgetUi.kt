package app.encave.widget

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceComposable
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.components.TitleBar
import androidx.glance.color.ColorProviders
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.encave.MainActivity
import app.encave.R
import app.encave.core.Maturity
import app.encave.ui.components.labelRes
import app.encave.ui.theme.DesignTokens
import app.encave.ui.theme.brandScheme

/** The Encave burgundy, from the design tokens, when dynamic color is off or unavailable. */
private val brandColors: ColorProviders by lazy { ColorProviders(light = brandScheme(false), dark = brandScheme(true)) }

/** Material You colors when the app uses them (Android 12+), the Encave colors otherwise; light and dark. */
@Composable
fun EncaveGlanceTheme(dynamicColor: Boolean, content: @Composable @GlanceComposable () -> Unit) {
    val colors = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) GlanceTheme.colors else brandColors
    GlanceTheme(colors = colors, content = content)
}

internal object WidgetDimens {
    val xs = DesignTokens.Spacing.XS.dp
    val s = DesignTokens.Spacing.S.dp
    val m = DesignTokens.Spacing.M.dp
    val icon = 16.dp
}

/** Opens a place in the app: encave://wine/<id>, encave://tonight or encave://add. */
fun openLink(context: Context, link: String): Action =
    actionStartActivity(Intent(Intent.ACTION_VIEW, link.toUri(), context, MainActivity::class.java))

@get:DrawableRes
val Maturity.widgetIcon: Int
    get() = when (this) {
        Maturity.DrinkSoon -> R.drawable.ic_widget_drink_soon
        Maturity.Ready -> R.drawable.ic_widget_ready
        Maturity.PastPeak -> R.drawable.ic_widget_past_peak
        Maturity.Young -> R.drawable.ic_widget_young
        Maturity.Unknown -> R.drawable.ic_widget_unknown
    }

/** Same roles as in the app; always with a symbol and words, never color alone. */
val Maturity.widgetTint: ColorProvider
    @Composable get() = when (this) {
        Maturity.DrinkSoon -> GlanceTheme.colors.tertiary
        Maturity.Ready -> GlanceTheme.colors.primary
        Maturity.PastPeak -> GlanceTheme.colors.error
        Maturity.Young -> GlanceTheme.colors.secondary
        Maturity.Unknown -> GlanceTheme.colors.onSurfaceVariant
    }

/** The widget frame: a Material title bar with the section's symbol, then the content. */
@Composable
fun WidgetFrame(
    title: String,
    @DrawableRes icon: Int,
    onClick: Action,
    content: @Composable @GlanceComposable () -> Unit,
) {
    Scaffold(
        titleBar = { TitleBar(startIcon = ImageProvider(icon), title = title, iconColor = GlanceTheme.colors.primary) },
        modifier = GlanceModifier.clickable(onClick),
        horizontalPadding = WidgetDimens.m,
    ) {
        Column(GlanceModifier.fillMaxSize().padding(bottom = WidgetDimens.m)) { content() }
    }
}

/** A symbol and a short text on one line, for maturity, place and counts. */
@Composable
fun IconLine(@DrawableRes icon: Int, text: String, tint: ColorProvider, textColor: ColorProvider = GlanceTheme.colors.onSurfaceVariant, modifier: GlanceModifier = GlanceModifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(
            ImageProvider(icon), contentDescription = null, modifier = GlanceModifier.size(WidgetDimens.icon),
            colorFilter = androidx.glance.ColorFilter.tint(tint),
        )
        Spacer(GlanceModifier.width(WidgetDimens.xs))
        Text(text, maxLines = 1, style = TextStyle(fontSize = 12.sp, color = textColor))
    }
}

/** Nothing in the cellar yet: an invitation to add bottles, which opens Add bottles. */
@Composable
fun EmptyCellar(title: String, @DrawableRes icon: Int) {
    val context = LocalContext.current
    val heading = context.getString(R.string.widget_empty_title)
    val body = context.getString(R.string.widget_empty_body)
    WidgetFrame(title, icon, openLink(context, MainActivity.LINK_ADD)) {
        Column(GlanceModifier.semantics { contentDescription = "$heading. $body" }) {
            Text(heading, maxLines = 3, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSurface))
            Spacer(GlanceModifier.height(WidgetDimens.xs))
            Text(body, maxLines = 2, style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant))
        }
    }
}

/** "Drink soon" for a maturity, in the app's words. */
fun Maturity.label(context: Context): String = context.getString(labelRes)
