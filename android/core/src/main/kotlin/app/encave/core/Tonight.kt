package app.encave.core

import java.text.Normalizer
import java.time.LocalDate
import java.util.UUID

/** The bottle a quick "Drink" opens: one not kept for an occasion when there is one. */
val List<Bottle>.bottleToOpen: Bottle? get() = firstOrNull { it.reservedFor == null } ?: firstOrNull()

/**
 * One bottle per place to choose from when opening a wine, the free ones first. Bottles
 * without a rack position, with the same storage note and kept for the same occasion (or
 * none) are interchangeable, so only the first is listed.
 */
val List<Bottle>.drinkChoices: List<Bottle>
    get() {
        val distinct = distinctBy { bottle ->
            val place = bottle.slot?.let { "${it.rackId}/${it.row}/${it.column}/${it.depth}" } ?: "note:${bottle.storageNote.orEmpty()}"
            place + "|" + bottle.reservedFor?.lowercase().orEmpty()
        }
        return distinct.filter { it.reservedFor == null } + distinct.filter { it.reservedFor != null }
    }

/**
 * The wines Tonight suggests, best first: those to drink soon, then those ready; within
 * each, the earliest end of window, then more bottles, then the name. Bottles kept for an
 * occasion are left out, as in Tonight's list.
 */
fun Cellar.tonightCandidates(year: Int): List<WineGroup> = tonightCandidatesWithMaturity(year).map { it.second }

private fun Cellar.tonightCandidatesWithMaturity(year: Int): List<Pair<Maturity, WineGroup>> {
    val collator = Cellar.collator()
    return groupsByMaturity(year)
        .filter { it.maturity == Maturity.DrinkSoon || it.maturity == Maturity.Ready }
        .flatMap { section -> section.groups.map { section.maturity to it } }
        .sortedWith { a, b ->
            when {
                a.first != b.first -> a.first.compareTo(b.first)
                (a.second.wine.drinkUntil ?: Int.MAX_VALUE) != (b.second.wine.drinkUntil ?: Int.MAX_VALUE) ->
                    (a.second.wine.drinkUntil ?: Int.MAX_VALUE).compareTo(b.second.wine.drinkUntil ?: Int.MAX_VALUE)
                a.second.count != b.second.count -> b.second.count.compareTo(a.second.count)
                else -> Cellar.nameOrder(a.second.wine, b.second.wine, collator)
            }
        }
}

/**
 * Tonight's suggestions for a day, in the order "Another idea" goes through them: those to
 * drink soon, then those ready, each in the day's order ([tonightRank]), so the first is the
 * same all day and does not change when other wines are drunk. [kept] (the first one shown today, remembered by the app)
 * stays first while it is a candidate, so a wine added later does not take its place either.
 */
fun Cellar.tonightPicks(year: Int, day: LocalDate, kept: UUID? = null): List<WineGroup> {
    val candidates = tonightCandidatesWithMaturity(year)
    val ranks = candidates.associate { it.second.wine.id to tonightRank(it.second.wine.id, day) }
    // A stable sort: equal ranks (never in practice) keep the best-first order.
    val ranked = candidates
        .sortedWith(compareBy<Pair<Maturity, WineGroup>> { it.first }.thenBy { ranks.getValue(it.second.wine.id) })
        .map { it.second }
    val first = ranked.firstOrNull { it.wine.id == kept } ?: return ranked
    return listOf(first) + (ranked - first)
}

/**
 * A wine's place among Tonight's suggestions on a day: the 64-bit FNV-1a hash of the UTF-8
 * text "<lowercase wine id>|<yyyy-MM-dd>". The same on Apple (fixtures/tonight-vectors.json).
 * The day is the local date, in the ISO (Gregorian) calendar, as on Apple.
 */
fun tonightRank(wineId: UUID, day: LocalDate): ULong {
    var hash = 0xcbf29ce484222325uL
    for (byte in "${wineId.toString().lowercase()}|$day".toByteArray(Charsets.UTF_8)) {
        hash = (hash xor byte.toUByte().toULong()) * 0x100000001b3uL
    }
    return hash
}

/** The young wine whose window opens first (free bottles only), to say when something will be ready. */
fun Cellar.nextToBeReady(year: Int): WineGroup? {
    val collator = Cellar.collator()
    return groupsByMaturity(year).firstOrNull { it.maturity == Maturity.Young }?.groups?.minWithOrNull { a, b ->
        val from = (a.wine.drinkFrom ?: Int.MAX_VALUE).compareTo(b.wine.drinkFrom ?: Int.MAX_VALUE)
        if (from != 0) from else Cellar.nameOrder(a.wine, b.wine, collator)
    }
}

/** Bottles in the cellar kept for an occasion. */
val Cellar.reservedBottleCount: Int get() = bottlesInCellar.count { it.reservedFor != null }

/**
 * The occasions offered as chips in a tasting note, the same on every device. The occasion
 * is stored as typed text, so a chip matches its label in any language.
 */
enum class Occasion(val english: String, val french: String) {
    DinnerAtHome("Dinner at home", "Dîner à la maison"),
    WithFriends("With friends", "Entre amis"),
    Celebration("Celebration", "Fête"),
    Restaurant("Restaurant", "Restaurant"),
    Weeknight("Weeknight", "En semaine"),
    Tasting("Tasting", "Dégustation"),
    ;

    companion object {
        /** The chip an occasion's text stands for, ignoring case, accents and spaces around. */
        fun matching(text: String?): Occasion? {
            val needle = fold(text ?: return null)
            if (needle.isEmpty()) return null
            return entries.firstOrNull { fold(it.english) == needle || fold(it.french) == needle }
        }

        internal fun fold(text: String): String =
            Normalizer.normalize(text.trim(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
    }
}
