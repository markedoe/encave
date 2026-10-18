package app.encave.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {
    private fun roles(s: ColorScheme): Map<String, Color> = mapOf(
        "primary" to s.primary, "onPrimary" to s.onPrimary, "primaryContainer" to s.primaryContainer,
        "onPrimaryContainer" to s.onPrimaryContainer, "inversePrimary" to s.inversePrimary,
        "secondary" to s.secondary, "onSecondary" to s.onSecondary, "secondaryContainer" to s.secondaryContainer,
        "onSecondaryContainer" to s.onSecondaryContainer, "tertiary" to s.tertiary, "onTertiary" to s.onTertiary,
        "tertiaryContainer" to s.tertiaryContainer, "onTertiaryContainer" to s.onTertiaryContainer,
        "background" to s.background, "onBackground" to s.onBackground, "surface" to s.surface, "onSurface" to s.onSurface,
        "surfaceVariant" to s.surfaceVariant, "onSurfaceVariant" to s.onSurfaceVariant, "surfaceTint" to s.surfaceTint,
        "inverseSurface" to s.inverseSurface, "inverseOnSurface" to s.inverseOnSurface,
        "outline" to s.outline, "outlineVariant" to s.outlineVariant,
        "surfaceBright" to s.surfaceBright, "surfaceDim" to s.surfaceDim,
        "surfaceContainer" to s.surfaceContainer, "surfaceContainerHigh" to s.surfaceContainerHigh,
        "surfaceContainerHighest" to s.surfaceContainerHighest, "surfaceContainerLow" to s.surfaceContainerLow,
        "primaryFixed" to s.primaryFixed, "primaryFixedDim" to s.primaryFixedDim, "onPrimaryFixed" to s.onPrimaryFixed,
        "onPrimaryFixedVariant" to s.onPrimaryFixedVariant, "secondaryFixed" to s.secondaryFixed,
        "secondaryFixedDim" to s.secondaryFixedDim, "onSecondaryFixed" to s.onSecondaryFixed,
        "onSecondaryFixedVariant" to s.onSecondaryFixedVariant, "tertiaryFixed" to s.tertiaryFixed,
        "tertiaryFixedDim" to s.tertiaryFixedDim, "onTertiaryFixed" to s.onTertiaryFixed,
        "onTertiaryFixedVariant" to s.onTertiaryFixedVariant,
    )

    /** Every role of the Encave scheme comes from the tokens, none from Material's purple baseline. */
    @Test
    fun noRoleKeepsMaterialDefaults() {
        for (dark in listOf(false, true)) {
            val baseline = roles(if (dark) darkColorScheme() else lightColorScheme())
            val ours = roles(brandScheme(dark))
            val kept = ours.filter { (role, color) -> color == baseline[role] && color != Color.White && color != Color.Black }.keys
            assertTrue("dark=$dark keeps Material defaults: $kept", kept.isEmpty())
        }
    }
}
