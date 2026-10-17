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

/** What a monthly reminder says about the bottles in the cellar. */
data class DrinkingDigest(
    /** Bottles in the last year of their window. */
    val drinkSoon: Int = 0,
    /** Bottles whose window opens this year. */
    val newlyReady: Int = 0,
    /** Bottles past their window. */
    val pastPeak: Int = 0,
    /** The most urgent wine, to name in the reminder. */
    val firstWine: String? = null,
) {
    val isEmpty: Boolean get() = drinkSoon == 0 && newlyReady == 0 && pastPeak == 0
}

/** Counts for a reminder in [year], computed on the device. */
fun Cellar.drinkingDigest(year: Int): DrinkingDigest {
    var soon = 0
    var ready = 0
    var past = 0
    val urgent = mutableListOf<Pair<Maturity, String>>()
    for (bottle in bottlesInCellar) {
        val wine = wines[bottle.wineId] ?: continue
        when (Maturity.of(wine, year)) {
            Maturity.DrinkSoon -> {
                soon++
                urgent += Maturity.DrinkSoon to wine.displayName
            }
            Maturity.PastPeak -> {
                past++
                urgent += Maturity.PastPeak to wine.displayName
            }
            Maturity.Ready -> if (wine.drinkFrom == year) ready++
            else -> Unit
        }
    }
    val first = urgent.sortedWith(compareBy<Pair<Maturity, String>>({ it.first }, { it.second })).firstOrNull()?.second
    return DrinkingDigest(soon, ready, past, first)
}

/**
 * How fast to drink the cellar so that bottles are opened before their window ends,
 * from the drinking windows alone.
 */
data class DrinkDownPlan(
    /** From the current year, up to the last year a window ends (at most 15 years). */
    val years: List<Year>,
    /** Bottles already past their window. */
    val pastPeak: Int,
    /** Bottles without a drinking window, left out of the pace. */
    val withoutWindow: Int,
    /** Bottles a month to open everything with a window before it ends (0 if none). */
    val bottlesPerMonth: Double,
) {
    /** Bottles whose window ends in [year]. */
    data class Year(val year: Int, val leaving: Int)

    val isEmpty: Boolean get() = years.all { it.leaving == 0 } && pastPeak == 0
}

/**
 * The pace that keeps up with every window: for each year Y, the bottles whose window
 * ends by Y (past-peak ones count now) over the months left until the end of Y; the plan
 * takes the largest.
 */
fun Cellar.drinkDownPlan(year: Int, month: Int, horizon: Int = 15): DrinkDownPlan {
    val leaving = HashMap<Int, Int>()
    var pastPeak = 0
    var withoutWindow = 0
    for (bottle in bottlesInCellar) {
        val until = wines[bottle.wineId]?.drinkUntil
        if (until == null) {
            withoutWindow++
            continue
        }
        if (until < year) pastPeak++ else leaving[until] = (leaving[until] ?: 0) + 1
    }
    val last = minOf(year + horizon - 1, leaving.keys.maxOrNull() ?: year)
    val years = (year..maxOf(year, last)).map { DrinkDownPlan.Year(it, leaving[it] ?: 0) }
    var cumulative = pastPeak
    var pace = 0.0
    for (item in years) {
        cumulative += item.leaving
        // Months left, counting the current one: 13 - month this year, then 12 a year.
        val months = (13 - month + 12 * (item.year - year)).toDouble()
        if (cumulative > 0) pace = maxOf(pace, cumulative / months)
    }
    return DrinkDownPlan(years, pastPeak, withoutWindow, pace)
}
