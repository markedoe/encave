package app.encave.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the home screen widgets show: a small summary of the cellar for a year,
 * written by the app to its own storage and read by the widgets. It holds counts
 * and a few names, nothing else. Android only: the Apple app has no widgets yet.
 */
@Serializable
data class WidgetSnapshot(
    /** Bumped when the format changes in a way older widgets cannot read. */
    val version: Int = CURRENT_VERSION,
    val year: Int,
    /** Bottles in the cellar (not on order, not drunk). */
    val bottles: Int = 0,
    /** Wines with bottles in the cellar. */
    val wines: Int = 0,
    val drinkSoon: Int = 0,
    val ready: Int = 0,
    val young: Int = 0,
    val pastPeak: Int = 0,
    /** Bottles bought but not delivered yet. */
    val onOrder: Int = 0,
    /** The bottles to open tonight, the most urgent first. */
    val tonight: List<Pick> = emptyList(),
) {
    /** A wine to open tonight, with its bottles left for tonight. */
    @Serializable
    data class Pick(
        /** The wine's id (UUID string), for its link. */
        @SerialName("wineID") val wineId: String,
        val name: String,
        val vintage: Int? = null,
        val color: WineColor? = null,
        val maturity: Maturity,
        /** Bottles of the wine not kept for an occasion. */
        val bottles: Int,
        /** "Main rack · B3", the storage note, or null. */
        val place: String? = null,
    )

    /** Nothing in the cellar yet: the widgets invite to add bottles. */
    val isEmpty: Boolean get() = bottles == 0 && onOrder == 0

    /** Stable JSON: an unchanged cellar gives the same text. */
    fun encoded(): String = json.encodeToString(serializer(), this)

    companion object {
        const val CURRENT_VERSION = 1

        /** How many picks a snapshot keeps (the largest widget shows them all). */
        const val PICK_LIMIT = 6

        /** File name of the snapshot in the app's storage. */
        const val FILE_NAME = "widget-snapshot.json"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

        /**
         * The summary of [cellar] in [year]. Bottles kept for an occasion count in
         * the totals but are never suggested for tonight, as in the Tonight section.
         */
        fun of(
            cellar: Cellar,
            year: Int,
            pickLimit: Int = PICK_LIMIT,
            /** A bottle's place as the lists name it (the app's names, translated). */
            place: (Bottle) -> String? = { cellar.locationLabel(it) },
        ): WidgetSnapshot {
            val inCellar = cellar.bottlesInCellar
            val counts = HashMap<Maturity, Int>()
            val wineIds = HashSet<java.util.UUID>()
            for (bottle in inCellar) {
                val wine = cellar.wines[bottle.wineId] ?: continue
                wineIds += wine.id
                val maturity = Maturity.of(wine, year)
                counts[maturity] = (counts[maturity] ?: 0) + 1
            }
            val urgent = setOf(Maturity.DrinkSoon, Maturity.Ready, Maturity.PastPeak)
            val collator = Cellar.collator()
            // Within a section, the window that closes first comes first.
            val byClosing = compareBy<WineGroup> { it.wine.drinkUntil ?: Int.MAX_VALUE }
                .then { a, b -> Cellar.nameOrder(a.wine, b.wine, collator) }
            val picks = cellar.groupsByMaturity(year)
                .filter { it.maturity in urgent }
                .flatMap { section ->
                    section.groups.sortedWith(byClosing).map { group ->
                        Pick(
                            wineId = group.wine.id.toString(),
                            name = group.wine.name,
                            vintage = group.wine.vintage,
                            color = group.wine.color,
                            maturity = section.maturity,
                            bottles = group.count,
                            place = group.bottles.firstNotNullOfOrNull(place),
                        )
                    }
                }
                .take(maxOf(0, pickLimit))
            return WidgetSnapshot(
                year = year,
                bottles = inCellar.size,
                wines = wineIds.size,
                drinkSoon = counts[Maturity.DrinkSoon] ?: 0,
                ready = counts[Maturity.Ready] ?: 0,
                young = counts[Maturity.Young] ?: 0,
                pastPeak = counts[Maturity.PastPeak] ?: 0,
                onOrder = cellar.bottlesOnOrder.size,
                tonight = picks,
            )
        }

        /** Reads a snapshot; null when the text is missing, damaged or from a newer format. */
        fun decode(text: String?): WidgetSnapshot? {
            if (text.isNullOrBlank()) return null
            val snapshot = runCatching { json.decodeFromString(serializer(), text) }.getOrNull() ?: return null
            return snapshot.takeIf { it.version <= CURRENT_VERSION }
        }
    }
}
