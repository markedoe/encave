package app.encave.core

import java.text.Normalizer

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
fun Cellar.tonightCandidates(year: Int): List<WineGroup> {
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
        .map { it.second }
}

/**
 * Where Tonight's suggestions start on a given day of the year (1…366): a new wine each
 * day, the same one all day. "Another idea" goes on from there.
 */
fun tonightStartIndex(dayOfYear: Int, count: Int): Int = if (count > 0) (maxOf(dayOfYear, 1) - 1) % count else 0

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
