package app.encave.ui.theme

/**
 * Mirror of design/tokens.json, the single source of truth shared with the Apple app.
 * DesignTokensTest fails if the two drift apart. Never write colors anywhere else.
 */
object DesignTokens {
    /** A color with its light and dark variants, as 0xAARRGGBB. */
    data class ThemedColor(val light: Long, val dark: Long)

    val accent = ThemedColor(light = 0xFF8E1E3A, dark = 0xFFE0607A)

    /** Launch window (window_background in the values and values-night colors.xml), checked by DesignTokensTest. */
    val launchBackground = ThemedColor(light = 0xFFFEFCFC, dark = 0xFF120F10)
    /** Text and icons of a warning, always next to an icon or words. */
    val warning = ThemedColor(light = 0xFF8F5300, dark = 0xFFF2B04A)
    /** Errors and destructive actions, always with words. */
    val error = ThemedColor(light = 0xFFB3261E, dark = 0xFFFF6B60)

    /** Studio backdrop behind label photos and covers: warm cream, or warm near-black in dark theme. */
    val studioBase = ThemedColor(light = 0xFFE8DED2, dark = 0xFF141010)
    /** Warm candle light of the out-of-focus discs on the studio backdrop. */
    val studioCandle = ThemedColor(light = 0xFFFFC478, dark = 0xFFFFC478)
    /** Wine color of the studio backdrop (wash and light discs), keyed by token name ("studio-red"...); white as straw gold. */
    val studioTints: Map<String, ThemedColor> = linkedMapOf(
        "studio-red" to ThemedColor(light = 0xFF8C1C2B, dark = 0xFFC8454F),
        "studio-white" to ThemedColor(light = 0xFFDEB45A, dark = 0xFFDEB45A),
        "studio-rose" to ThemedColor(light = 0xFFF0A0AA, dark = 0xFFE58A95),
        "studio-sparkling" to ThemedColor(light = 0xFFE6CF8E, dark = 0xFFE6CF8E),
        "studio-dessert" to ThemedColor(light = 0xFFC8902E, dark = 0xFFD9A441),
        "studio-fortified" to ThemedColor(light = 0xFF6B2D2D, dark = 0xFFA2524A),
        "studio-orange" to ThemedColor(light = 0xFFE39B4C, dark = 0xFFEBA35A),
    )

    /** Keyed by token name ("wine-red"...). */
    val wineColors: Map<String, ThemedColor> = linkedMapOf(
        "wine-red" to ThemedColor(light = 0xFF8C1C2B, dark = 0xFFC8454F),
        "wine-white" to ThemedColor(light = 0xFFC9BE4E, dark = 0xFFC7BD55),
        "wine-rose" to ThemedColor(light = 0xFFF0A0AA, dark = 0xFFE58A95),
        "wine-sparkling" to ThemedColor(light = 0xFFF3E6C4, dark = 0xFFEFE3C2),
        "wine-dessert" to ThemedColor(light = 0xFFC8902E, dark = 0xFFD9A441),
        "wine-fortified" to ThemedColor(light = 0xFF6B2D2D, dark = 0xFFA2524A),
        "wine-orange" to ThemedColor(light = 0xFFE39B4C, dark = 0xFFEBA35A),
    )

    /** Maturity symbols, keyed by token name ("maturity-ready"...). Always shown with words. */
    val maturityColors: Map<String, ThemedColor> = linkedMapOf(
        "maturity-drink-soon" to ThemedColor(light = 0xFFA65A00, dark = 0xFFF5A54A),
        "maturity-ready" to ThemedColor(light = 0xFF2E7D32, dark = 0xFF81C784),
        "maturity-past-peak" to ThemedColor(light = 0xFFB3261E, dark = 0xFFF2B8B5),
        "maturity-keep" to ThemedColor(light = 0xFF00695C, dark = 0xFF4DB6AC),
    )

    /** Spacing in dp. */
    object Spacing {
        const val XS = 4
        const val S = 8
        const val M = 12
        const val L = 16
        const val XL = 24
        const val XXL = 32
    }

    /** Corner radius in dp. */
    object Radius {
        const val CHIP = 6
        const val CELL = 10
        const val CARD = 16
    }

    /** Minimum touch target in dp. */
    const val TOUCH_TARGET = 48
}
