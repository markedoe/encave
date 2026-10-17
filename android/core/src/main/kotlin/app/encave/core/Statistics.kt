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
) {
    data class Slice(val label: String, val count: Int)
    data class ColorSlice(val color: WineColor?, val count: Int)
    data class MonthCount(val month: YearMonth, val count: Int)

    companion object {
        const val NON_VINTAGE = "NV"
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
    )
}
