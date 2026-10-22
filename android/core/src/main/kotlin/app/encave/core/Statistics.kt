package app.encave.core

import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Figures for the Insights screen. */
data class CellarStatistics(
    val bottleCount: Int,
    val wineCount: Int,
    val consumedCount: Int,
    val averageRating: Double?,
    /** Sum of purchase prices of bottles in the cellar, per currency. */
    val valueByCurrency: Map<String, BigDecimal>,
    val byColor: List<ColorSlice>,
    val byCountry: List<Slice>,
    val byRegion: List<Slice>,
    /** Decades of vintage ("2010s"), oldest first; non-vintage ("NV") last. */
    val byVintageDecade: List<Slice>,
    val byMaturity: Map<Maturity, Int>,
    /** Bottles opened per month over the last 12 months, oldest first. */
    val consumptionByMonth: List<MonthCount>,
    val topGrapes: List<Slice>,
    /** Sum of the current prices of bottles in the cellar, per currency, for wines that have one. */
    val estimatedValueByCurrency: Map<String, BigDecimal> = emptyMap(),
    /** How many bottles [estimatedValueByCurrency] covers. */
    val pricedBottleCount: Int = 0,
) {
    data class Slice(val label: String, val count: Int)
    data class ColorSlice(val color: WineColor?, val count: Int)
    data class MonthCount(val month: YearMonth, val count: Int)

    companion object {
        const val NON_VINTAGE = "NV"

        /** The first year of a decade label ("2010s" gives 2010), for the apps to name it in words; else null. */
        fun decadeStart(label: String): Int? = label.takeIf { it.endsWith("s") }?.dropLast(1)?.toIntOrNull()

        /**
         * Whole percentages of [counts] that add up to 100 (largest remainder): each share
         * rounded down, then the points left over go to the largest remainders, the first
         * one on a tie. All zeros (or none) give zeros.
         */
        fun percentages(counts: List<Int>): List<Int> {
            val total = counts.sum()
            if (total <= 0) return counts.map { 0 }
            val floors = counts.map { it * 100 / total }
            val left = 100 - floors.sum()
            val bonus = counts.indices.sortedByDescending { (counts[it] * 100) % total }.take(left).toSet()
            return floors.mapIndexed { index, floor -> if (index in bonus) floor + 1 else floor }
        }
    }
}

fun Cellar.statistics(year: Int, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): CellarStatistics {
    val inCellar = bottlesInCellar
    val winesInCellar = inCellar.mapNotNull { wines[it.wineId] }

    fun slices(values: List<String?>, limit: Int? = null): List<CellarStatistics.Slice> {
        val sorted = values.filterNotNull().filter { it.isNotEmpty() }
            .groupingBy { it }.eachCount()
            .map { CellarStatistics.Slice(it.key, it.value) }
            .sortedWith(compareByDescending<CellarStatistics.Slice> { it.count }.thenBy { it.label })
        return if (limit != null) sorted.take(limit) else sorted
    }

    val value = linkedMapOf<String, BigDecimal>()
    for (bottle in inCellar) {
        val price = bottle.purchasePrice ?: continue
        val currency = bottle.currency ?: settings.currency
        value[currency] = (value[currency] ?: BigDecimal.ZERO) + price
    }

    val estimated = linkedMapOf<String, BigDecimal>()
    var priced = 0
    for (bottle in inCellar) {
        val wine = wines[bottle.wineId] ?: continue
        val price = wine.currentPrice ?: continue
        val currency = wine.currentPriceCurrency ?: settings.currency
        estimated[currency] = (estimated[currency] ?: BigDecimal.ZERO) + price
        priced += 1
    }

    val byColor = winesInCellar.groupingBy { it.color }.eachCount()
        .map { CellarStatistics.ColorSlice(it.key, it.value) }
        .sortedByDescending { it.count }

    val byDecade = winesInCellar
        .map { wine -> wine.vintage?.let { "${it / 10 * 10}s" } ?: CellarStatistics.NON_VINTAGE }
        .groupingBy { it }.eachCount()
        .map { CellarStatistics.Slice(it.key, it.value) }
        .sortedWith(compareBy<CellarStatistics.Slice> { it.label == CellarStatistics.NON_VINTAGE }.thenBy { it.label })

    val byMaturity = winesInCellar.groupingBy { Maturity.of(it, year) }.eachCount()

    val consumed = consumedBottles
    val ratings = consumed.mapNotNull { it.tasting?.rating }
    val current = YearMonth.from(now.atZone(zone))
    val monthly = (11 downTo 0).map { offset ->
        val month = current.minusMonths(offset.toLong())
        val count = consumed.count { bottle ->
            val date = bottle.tasting?.date ?: return@count false
            YearMonth.from(date.atZone(zone)) == month
        }
        CellarStatistics.MonthCount(month, count)
    }

    return CellarStatistics(
        bottleCount = inCellar.size,
        wineCount = inCellar.map { it.wineId }.toSet().size,
        consumedCount = consumed.size,
        averageRating = if (ratings.isEmpty()) null else ratings.sum().toDouble() / ratings.size,
        valueByCurrency = value,
        byColor = byColor,
        byCountry = slices(winesInCellar.map { it.country }),
        byRegion = slices(winesInCellar.map { it.region }, limit = 8),
        byVintageDecade = byDecade,
        byMaturity = byMaturity,
        consumptionByMonth = monthly,
        topGrapes = slices(winesInCellar.flatMap { it.grapes }, limit = 8),
        estimatedValueByCurrency = estimated,
        pricedBottleCount = priced,
    )
}

/** "Your year in wine": what was opened in a year, from the journal. */
data class YearInWine(
    val year: Int,
    val bottlesOpened: Int,
    val differentWines: Int,
    /** Best rated bottle of the year (highest rating, then the latest): its name and rating. */
    val favorite: Pair<String, Int>?,
    val topRegion: String?,
    val topColor: WineColor?,
    val topOccasion: String?,
    /** Wines discovered elsewhere that year. */
    val discoveries: Int,
    /** Month with the most bottles opened (1–12). */
    val busiestMonth: Int?,
) {
    val isEmpty: Boolean get() = bottlesOpened == 0 && discoveries == 0
}

fun Cellar.yearInWine(year: Int, zone: ZoneId = ZoneId.systemDefault()): YearInWine {
    fun yearOf(instant: Instant) = instant.atZone(zone).year
    val opened = consumedBottles.filter { bottle -> bottle.tasting?.date?.let { yearOf(it) == year } == true }
    val openedWines = opened.mapNotNull { wines[it.wineId] }

    // The most frequent value; on a tie, the smallest as text (as CaveKit picks).
    fun <T> top(values: List<T>, text: (T) -> String = { it.toString() }): T? =
        values.groupBy { it }.entries.sortedWith(compareByDescending<Map.Entry<T, List<T>>> { it.value.size }.thenBy { text(it.key) })
            .firstOrNull()?.key

    val favorite = opened.mapNotNull { bottle -> bottle.tasting?.rating?.let { bottle to it } }
        .maxWithOrNull(compareBy<Pair<Bottle, Int>>({ it.second }, { it.first.tasting?.date ?: Instant.MIN }))
        ?.let { (bottle, rating) -> wines[bottle.wineId]?.let { it.displayName to rating } }
    val months = opened.mapNotNull { it.tasting?.date }.map { it.atZone(zone).monthValue }
    return YearInWine(
        year = year,
        bottlesOpened = opened.size,
        differentWines = opened.map { it.wineId }.toSet().size,
        favorite = favorite,
        topRegion = top(openedWines.mapNotNull { it.region ?: it.country }),
        topColor = top(openedWines.mapNotNull { it.color }) { it.raw },
        // A chip counts the same in any language: its English label, shown translated.
        topOccasion = top(opened.mapNotNull { it.tasting?.occasion }.filter { it.isNotEmpty() }.map { Occasion.matching(it)?.english ?: it }),
        discoveries = discoveries.values.count { yearOf(it.date) == year },
        busiestMonth = top(months),
    )
}
