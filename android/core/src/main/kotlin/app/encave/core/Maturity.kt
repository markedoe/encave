package app.encave.core

/**
 * Where a wine is in its drinking window.
 *
 * Declared in urgency order (most urgent first), so the natural enum order
 * matches the Swift `Comparable` conformance used by "Tonight".
 */
enum class Maturity(val raw: String) {
    /** Last year of the window: drink soon. */
    DrinkSoon("drinkSoon"),

    /** Inside the window, not in its last year. */
    Ready("ready"),

    /** Window has passed. */
    PastPeak("pastPeak"),

    /** Window not reached yet. */
    Young("young"),

    /** No window known. */
    Unknown("unknown");

    companion object {
        fun of(wine: Wine, year: Int): Maturity {
            val from = wine.drinkFrom ?: return Unknown
            val until = wine.drinkUntil ?: return Unknown
            if (from > until) return Unknown
            return when {
                year < from -> Young
                year > until -> PastPeak
                year == until -> DrinkSoon
                else -> Ready
            }
        }
    }
}
