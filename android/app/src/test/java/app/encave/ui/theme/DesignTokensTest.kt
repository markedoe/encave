package app.encave.ui.theme

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** DesignTokens.kt must mirror design/tokens.json exactly. */
class DesignTokensTest {
    private val tokens: JsonObject by lazy {
        val path = System.getProperty("encave.tokens") ?: "../../design/tokens.json"
        Json.parseToJsonElement(File(path).readText()).jsonObject
    }

    private fun argb(hex: String): Long = 0xFF000000 or hex.removePrefix("#").toLong(16)

    private fun themed(name: String): DesignTokens.ThemedColor {
        val value = tokens.getValue("color").jsonObject.getValue(name).jsonObject.getValue("\$value").jsonObject
        return DesignTokens.ThemedColor(
            light = argb(value.getValue("light").jsonPrimitive.content),
            dark = argb(value.getValue("dark").jsonPrimitive.content),
        )
    }

    @Test
    fun accentMatches() {
        assertEquals(themed("accent"), DesignTokens.accent)
    }

    @Test
    fun warningMatches() {
        assertEquals(themed("warning"), DesignTokens.warning)
    }

    @Test
    fun errorMatches() {
        assertEquals(themed("error"), DesignTokens.error)
    }

    @Test
    fun tileColorsMatch() {
        assertEquals(themed("tile-background"), DesignTokens.tileBackground)
        assertEquals(themed("tile-text"), DesignTokens.tileText)
    }

    private fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun contrast(a: Long, b: Long): Double {
        val (hi, lo) = luminance(a).let { x -> luminance(b).let { y -> maxOf(x, y) to minOf(x, y) } }
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun tileTextReadsOnTheTileInLightAndDark() {
        val background = DesignTokens.tileBackground
        val text = DesignTokens.tileText
        assertTrue(contrast(text.light, background.light) >= 4.5)
        assertTrue(contrast(text.dark, background.dark) >= 4.5)
    }

    @Test
    fun studioBackdropMatches() {
        assertEquals(themed("studio-base"), DesignTokens.studioBase)
        assertEquals(themed("studio-candle"), DesignTokens.studioCandle)
        val names = tokens.getValue("color").jsonObject.keys
            .filter { it.startsWith("studio-") && it != "studio-base" && it != "studio-candle" }
        assertEquals(names.toSet(), DesignTokens.studioTints.keys)
        names.forEach { assertEquals(it, themed(it), DesignTokens.studioTints[it]) }
    }

    @Test
    fun wineColorsMatch() {
        val names = tokens.getValue("color").jsonObject.keys.filter { it.startsWith("wine-") }
        assertEquals(names.toSet(), DesignTokens.wineColors.keys)
        names.forEach { assertEquals(it, themed(it), DesignTokens.wineColors[it]) }
    }

    @Test
    fun maturityColorsMatch() {
        val names = tokens.getValue("color").jsonObject.keys.filter { it.startsWith("maturity-") }
        assertEquals(names.toSet(), DesignTokens.maturityColors.keys)
        names.forEach { assertEquals(it, themed(it), DesignTokens.maturityColors[it]) }
    }

    @Test
    fun spacingRadiusAndTouchTargetMatch() {
        val spacing = tokens.getValue("spacing").jsonObject
        assertEquals(
            listOf("xs", "s", "m", "l", "xl", "xxl").map { spacing.getValue(it).jsonPrimitive.int },
            with(DesignTokens.Spacing) { listOf(XS, S, M, L, XL, XXL) },
        )
        val radius = tokens.getValue("radius").jsonObject
        assertEquals(
            listOf("chip", "cell", "card").map { radius.getValue(it).jsonPrimitive.int },
            with(DesignTokens.Radius) { listOf(CHIP, CELL, CARD) },
        )
        assertEquals(tokens.getValue("touchTarget").jsonObject.getValue("android").jsonPrimitive.int, DesignTokens.TOUCH_TARGET)
    }

    private fun resourceColor(folder: String, name: String): Long {
        val colors = File("src/main/res/$folder/colors.xml").readText()
        val hex = Regex("""name="$name">#([0-9A-Fa-f]{6})<""").find(colors)
        return argb(checkNotNull(hex) { "$folder/colors.xml has no literal $name" }.groupValues[1])
    }

    @Test
    fun launcherBackgroundIsTheAccent() {
        assertEquals(DesignTokens.accent.light, resourceColor("values", "ic_launcher_background"))
    }

    @Test
    fun launchWindowMatches() {
        val token = themed("launch-background")
        assertEquals(token, DesignTokens.launchBackground)
        assertEquals(token.light, resourceColor("values", "window_background"))
        assertEquals(token.dark, resourceColor("values-night", "window_background"))
    }
}
