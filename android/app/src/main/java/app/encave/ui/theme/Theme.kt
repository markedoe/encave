package app.encave.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.encave.core.WineColor

/** Wine style colors for the current theme. Always paired with a text label. */
@Immutable
data class WinePalette(private val colors: Map<WineColor, Color>, val unknown: Color) {
    operator fun get(color: WineColor?): Color = color?.let { colors[it] } ?: unknown
}

val LocalWinePalette = staticCompositionLocalOf { WinePalette(emptyMap(), Color.Gray) }

/** Maturity symbol colors for the current theme (design/tokens.json). Always paired with words. */
@Immutable
data class MaturityPalette(val drinkSoon: Color, val ready: Color, val pastPeak: Color, val keep: Color)

private fun maturityPalette(dark: Boolean): MaturityPalette {
    fun token(name: String) = DesignTokens.maturityColors.getValue(name).resolve(dark)
    return MaturityPalette(token("maturity-drink-soon"), token("maturity-ready"), token("maturity-past-peak"), token("maturity-keep"))
}

val LocalMaturityPalette = staticCompositionLocalOf { maturityPalette(dark = false) }

private fun DesignTokens.ThemedColor.resolve(dark: Boolean) = Color(if (dark) this.dark else light)

private fun winePalette(dark: Boolean, unknown: Color) = WinePalette(
    WineColor.entries.associateWith { color ->
        DesignTokens.wineColors.getValue("wine-${color.raw}").resolve(dark)
    },
    unknown,
)

/**
 * Burgundy fallback when dynamic color is off or unavailable. Every role is
 * derived from the accent token by mixing with white or black, so no color
 * is written here.
 */
internal fun brandScheme(dark: Boolean): ColorScheme {
    val accent = DesignTokens.accent.resolve(dark)
    val ink = if (dark) Color.White else Color.Black
    val paper = if (dark) Color.Black else Color.White
    fun tint(amount: Float) = lerp(paper, accent, amount)
    // Dark surfaces: a grey of the given lightness (colors mix in Oklab, so 0.16 is a deep
    // grey, as Material's dark surfaces), warmed by a little accent. Pure black would hide
    // cards and sheets against the background.
    fun tone(lightness: Float, accentAmount: Float) = lerp(lerp(Color.Black, Color.White, lightness), accent, accentAmount)
    // The other theme's accent, for the inverse roles (snackbar actions).
    val otherAccent = DesignTokens.accent.resolve(!dark)
    val dessert = DesignTokens.wineColors.getValue("wine-dessert").resolve(dark)
    val scheme = if (dark) {
        darkColorScheme(
            primary = accent, onPrimary = lerp(accent, Color.Black, 0.8f),
            primaryContainer = lerp(accent, Color.Black, 0.6f), onPrimaryContainer = lerp(accent, Color.White, 0.8f),
            secondary = lerp(accent, Color.White, 0.35f), onSecondary = lerp(accent, Color.Black, 0.85f),
            secondaryContainer = tone(0.22f, 0.28f), onSecondaryContainer = lerp(accent, Color.White, 0.85f),
            tertiary = lerp(DesignTokens.wineColors.getValue("wine-dessert").resolve(true), Color.White, 0.2f),
            background = tone(0.16f, 0.03f), onBackground = lerp(ink, accent, 0.06f),
            surface = tone(0.16f, 0.03f), onSurface = lerp(ink, accent, 0.06f),
            surfaceVariant = tone(0.30f, 0.10f), onSurfaceVariant = lerp(ink, accent, 0.25f).copy(alpha = 0.85f),
            surfaceContainerLowest = tone(0.12f, 0.02f), surfaceContainerLow = tone(0.19f, 0.04f), surfaceContainer = tone(0.22f, 0.05f),
            surfaceContainerHigh = tone(0.26f, 0.06f), surfaceContainerHighest = tone(0.30f, 0.07f),
            outline = lerp(ink, accent, 0.3f).copy(alpha = 0.6f), outlineVariant = tone(0.36f, 0.10f),
        )
    } else {
        lightColorScheme(
            primary = accent, onPrimary = Color.White,
            primaryContainer = tint(0.16f), onPrimaryContainer = lerp(accent, Color.Black, 0.55f),
            secondary = lerp(accent, Color.Gray, 0.45f), onSecondary = Color.White,
            secondaryContainer = tint(0.1f), onSecondaryContainer = lerp(accent, Color.Black, 0.6f),
            tertiary = lerp(DesignTokens.wineColors.getValue("wine-dessert").resolve(false), Color.Black, 0.35f),
            background = tint(0.015f), onBackground = lerp(ink, accent, 0.08f),
            surface = tint(0.015f), onSurface = lerp(ink, accent, 0.08f),
            surfaceVariant = tint(0.07f), onSurfaceVariant = lerp(ink, accent, 0.2f).copy(alpha = 0.78f),
            surfaceContainerLowest = Color.White, surfaceContainerLow = tint(0.03f), surfaceContainer = tint(0.045f),
            surfaceContainerHigh = tint(0.06f), surfaceContainerHighest = tint(0.08f),
            outline = lerp(ink, accent, 0.2f).copy(alpha = 0.5f), outlineVariant = tint(0.14f),
        )
    }
    // Every other role too, so none keeps Material's default purple.
    return if (dark) {
        scheme.copy(
            onTertiary = lerp(dessert, Color.Black, 0.8f),
            tertiaryContainer = lerp(dessert, Color.Black, 0.6f), onTertiaryContainer = lerp(dessert, Color.White, 0.8f),
            inversePrimary = otherAccent, inverseSurface = lerp(Color.White, accent, 0.06f), inverseOnSurface = tone(0.16f, 0.03f),
            surfaceTint = accent, surfaceBright = tone(0.30f, 0.07f), surfaceDim = tone(0.12f, 0.02f),
            primaryFixed = lerp(accent, Color.White, 0.8f), primaryFixedDim = lerp(accent, Color.White, 0.6f),
            onPrimaryFixed = lerp(accent, Color.Black, 0.8f), onPrimaryFixedVariant = lerp(accent, Color.Black, 0.5f),
            secondaryFixed = lerp(accent, Color.White, 0.85f), secondaryFixedDim = lerp(accent, Color.White, 0.65f),
            onSecondaryFixed = lerp(accent, Color.Black, 0.85f), onSecondaryFixedVariant = lerp(accent, Color.Black, 0.55f),
            tertiaryFixed = lerp(dessert, Color.White, 0.8f), tertiaryFixedDim = lerp(dessert, Color.White, 0.6f),
            onTertiaryFixed = lerp(dessert, Color.Black, 0.8f), onTertiaryFixedVariant = lerp(dessert, Color.Black, 0.5f),
        )
    } else {
        scheme.copy(
            onTertiary = Color.White,
            tertiaryContainer = lerp(paper, dessert, 0.18f), onTertiaryContainer = lerp(dessert, Color.Black, 0.6f),
            inversePrimary = otherAccent, inverseSurface = lerp(lerp(Color.Black, Color.White, 0.2f), accent, 0.04f),
            inverseOnSurface = lerp(Color.White, accent, 0.06f),
            surfaceTint = accent, surfaceBright = tint(0.01f), surfaceDim = tint(0.12f),
            primaryFixed = tint(0.2f), primaryFixedDim = tint(0.4f),
            onPrimaryFixed = lerp(accent, Color.Black, 0.7f), onPrimaryFixedVariant = lerp(accent, Color.Black, 0.3f),
            secondaryFixed = tint(0.12f), secondaryFixedDim = tint(0.3f),
            onSecondaryFixed = lerp(accent, Color.Black, 0.75f), onSecondaryFixedVariant = lerp(accent, Color.Black, 0.4f),
            tertiaryFixed = lerp(paper, dessert, 0.2f), tertiaryFixedDim = lerp(paper, dessert, 0.4f),
            onTertiaryFixed = lerp(dessert, Color.Black, 0.7f), onTertiaryFixedVariant = lerp(dessert, Color.Black, 0.3f),
        )
    }
}

private val baseTypography = Typography()

/** Display styles use the system serif for wine names ("hero" headers). */
val SerifDisplay = baseTypography.headlineLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)

object Dimens {
    val xs = DesignTokens.Spacing.XS.dp
    val s = DesignTokens.Spacing.S.dp
    val m = DesignTokens.Spacing.M.dp
    val l = DesignTokens.Spacing.L.dp
    val xl = DesignTokens.Spacing.XL.dp
    val xxl = DesignTokens.Spacing.XXL.dp
    val radiusChip = DesignTokens.Radius.CHIP.dp
    val radiusCell = DesignTokens.Radius.CELL.dp
    val radiusCard = DesignTokens.Radius.CARD.dp
    val touchTarget = DesignTokens.TOUCH_TARGET.dp
}

@Composable
fun EncaveTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> brandScheme(darkTheme)
    }
    CompositionLocalProvider(
        LocalWinePalette provides winePalette(darkTheme, scheme.outlineVariant),
        LocalMaturityPalette provides maturityPalette(darkTheme),
    ) {
        MaterialTheme(colorScheme = scheme, typography = baseTypography, content = content)
    }
}
