package app.encave.ui.theme

/**
 * Mirror of design/tokens.json, the single source of truth shared with the Apple app.
 * DesignTokensTest fails if the two drift apart. Never write colors anywhere else.
 */
object DesignTokens {
    /** A color with its light and dark variants, as 0xAARRGGBB. */
    data class ThemedColor(val light: Long, val dark: Long)

    val accent = ThemedColor(light = 0xFF8E1E3A, dark = 0xFFE0607A)

    /** Keyed by token name ("wine-red"...). */
    val wineColors: Map<String, ThemedColor> = linkedMapOf(
        "wine-red" to ThemedColor(light = 0xFF8C1C2B, dark = 0xFFC8454F),
        "wine-white" to ThemedColor(light = 0xFFE6D27C, dark = 0xFFD9C46A),
        "wine-rose" to ThemedColor(light = 0xFFF0A0AA, dark = 0xFFE58A95),
        "wine-sparkling" to ThemedColor(light = 0xFFF2E2A0, dark = 0xFFE6D27C),
        "wine-dessert" to ThemedColor(light = 0xFFC8902E, dark = 0xFFD9A441),
        "wine-fortified" to ThemedColor(light = 0xFF6B2D2D, dark = 0xFFA2524A),
        "wine-orange" to ThemedColor(light = 0xFFE39B4C, dark = 0xFFEBA35A),
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
