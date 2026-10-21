package app.encave.core

import java.time.Instant
import java.util.Locale
import java.util.UUID

/** Which entries of the Journal's diary are shown. */
enum class DiaryFilter {
    /** Everything, newest first. */
    All,

    /** Bottles of the cellar that were opened. */
    Cellar,

    /** Wines tasted elsewhere (restaurant, friends, a fair). */
    Elsewhere,

    /** Wines tasted elsewhere that are on the shopping list. */
    ToBuy;

    /** Whether this filter shows tastings of bottles from the cellar. */
    val showsTastings: Boolean get() = this == All || this == Cellar

    /** Whether this filter shows wines tasted elsewhere (all of them, or those to buy). */
    val showsElsewhere: Boolean get() = this != Cellar
}

/** One entry of the diary: a bottle of the cellar that was opened, or a wine tasted elsewhere. */
sealed class DiaryEntry {
    abstract val id: UUID
    abstract val wineId: UUID

    /** When it was tasted. A bottle opened without a tasting (imported) uses the day it was added. */
    abstract val date: Instant
    abstract val rating: Int?

    data class Opened(val bottle: Bottle) : DiaryEntry() {
        override val id: UUID get() = bottle.id
        override val wineId: UUID get() = bottle.wineId
        override val date: Instant get() = bottle.tasting?.date ?: bottle.addedAt
        override val rating: Int? get() = bottle.tasting?.rating
    }

    data class Elsewhere(val discovery: Discovery) : DiaryEntry() {
        override val id: UUID get() = discovery.id
        override val wineId: UUID get() = discovery.wineId
        override val date: Instant get() = discovery.date
        override val rating: Int? get() = discovery.rating
    }
}

/**
 * Opened bottles whose wine (name, producer, origin, grapes, tags) or tasting
 * (note, occasion, full note, food) contains [query], most recent first.
 */
fun Cellar.tastings(query: String): List<Bottle> {
    val needle = query.trim()
    if (needle.isEmpty()) return consumedBottles
    val folded = Wine.fold(needle)
    return consumedBottles.filter { bottle ->
        if (wines[bottle.wineId]?.matches(needle) == true) return@filter true
        val tasting = bottle.tasting ?: return@filter false
        listOfNotNull(tasting.note, tasting.occasion, tasting.appearance, tasting.nose, tasting.palate, tasting.finish, tasting.foodPairing)
            .any { Wine.fold(it).contains(folded) }
    }
}

/**
 * The Journal's diary: opened bottles and wines tasted elsewhere, newest first
 * (same date: by id, so every device shows the same order).
 *
 * [query] looks in the wine, the tasting note and, for wines tasted elsewhere, the place,
 * note and tags. [tag] keeps only the wines tasted elsewhere that have it. Mirrors CaveKit.
 */
fun Cellar.diary(filter: DiaryFilter = DiaryFilter.All, query: String = "", tag: String? = null): List<DiaryEntry> {
    val entries = ArrayList<DiaryEntry>()
    if (filter.showsTastings && tag == null) entries += tastings(query).map { DiaryEntry.Opened(it) }
    if (filter.showsElsewhere) {
        entries += discoveries(DiscoveryFilter(query = query, tag = tag, toBuyOnly = filter == DiaryFilter.ToBuy))
            .map { DiaryEntry.Elsewhere(it) }
    }
    return entries.sortedWith(compareByDescending<DiaryEntry> { it.date }.thenBy { it.id.toString().uppercase(Locale.ROOT) })
}
