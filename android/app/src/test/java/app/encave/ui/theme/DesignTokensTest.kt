package app.encave.ui.theme

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
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
