package app.encave.core

import java.util.UUID

/**
 * The numbers the app shows about the cellar, counted the same way on every screen:
 * bottles, never the internal wine records (a wine tasted elsewhere, or whose bottles were
 * all drunk, is still a record but holds nothing in the cellar). Mirrors CaveKit's CellarCounts.
 */
data class CellarCounts(
    /** Bottles in the cellar (not opened, given away or on order). */
    val bottles: Int,
    /** Bottles in the cellar whose wine is still young: to keep. */
    val bottlesToKeep: Int,
    /** Bottles in the cellar to drink soon (the last year of their window). */
    val bottlesToDrinkSoon: Int,
    /** Wines with at least one bottle in the cellar, when a count of wines is really meant. */
    val winesWithBottles: Int,
) {
    companion object {
        fun of(cellar: Cellar, year: Int): CellarCounts {
            val inCellar = cellar.bottlesInCellar
            var keep = 0
            var soon = 0
            for (bottle in inCellar) {
                val wine = cellar.wines[bottle.wineId] ?: continue
                when (Maturity.of(wine, year)) {
                    Maturity.Young -> keep++
                    Maturity.DrinkSoon -> soon++
                    else -> Unit
                }
            }
            return CellarCounts(inCellar.size, keep, soon, inCellar.mapTo(HashSet()) { it.wineId }.size)
        }
    }
}

fun Cellar.counts(year: Int): CellarCounts = CellarCounts.of(this, year)

/** Bottles in the cellar, without working out maturities (backups, previous versions). */
val Cellar.bottlesInCellarCount: Int get() = bottles.values.count { it.status == Bottle.Status.InCellar }

/** Wines with at least one bottle in the cellar. */
val Cellar.winesWithBottlesCount: Int
    get() = bottles.values.filter { it.status == Bottle.Status.InCellar }.mapTo(HashSet()) { it.wineId }.size

/** The bottles the groups show. */
val List<WineGroup>.bottleCount: Int get() = sumOf { it.count }

/** What is being deleted, for [DeletionRule]. */
enum class DeletionKind { Bottle, Wine, Tasting, Discovery, SavedSearch, Storage }

/** How a deletion is guarded: one rule on every platform and screen. */
enum class DeletionGuard {
    /** Done at once, with an Undo snackbar. */
    Undo,

    /** Asked first, in a destructive alert naming the thing and its count. */
    Confirm,
}

object DeletionRule {
    /**
     * A single thing that can be undone goes at once, with Undo. Several things, a storage,
     * and anything that cannot be undone are confirmed first. [count] is how many things go:
     * for a wine, its bottles (in the cellar, on order or opened, as their tastings go with them).
     */
    fun guarding(kind: DeletionKind, count: Int, undoable: Boolean): DeletionGuard =
        if (!undoable || kind == DeletionKind.Storage || count > 1) DeletionGuard.Confirm else DeletionGuard.Undo
}

/** Bottle records (in the cellar, on order, opened or gone) deleted with these wines. */
fun Cellar.bottleRecordCount(wineIds: Set<UUID>): Int = bottles.values.count { it.wineId in wineIds }

/** Deleting these wines: one wine with at most one bottle goes with Undo; several wines, or a wine with several bottles, are confirmed. */
fun Cellar.deletionGuard(wineIds: Set<UUID>): DeletionGuard =
    DeletionRule.guarding(DeletionKind.Wine, maxOf(wineIds.size, bottleRecordCount(wineIds)), undoable = true)

/** How the figures at the top of the Cellar are laid out for a font scale. */
enum class StatTileLayout {
    /** Side by side tiles, each with a large figure. */
    Tiles,

    /** A short list, one line a figure, so the tiles do not fill the first screen at the largest font scales. */
    Compact;

    companion object {
        /** From this font scale the figures become a short list (the first accessibility Dynamic Type size on Apple). */
        const val COMPACT_SCALE = 1.5f

        fun of(fontScale: Float): StatTileLayout = if (fontScale >= COMPACT_SCALE) Compact else Tiles
    }
}

/** The parts of the Add Bottles form, for a new wine. */
enum class AddFormSection {
    /** The label photo (and scan). */
    Label,

    /** The wine fields, the name first. */
    Wine,

    /** A wine of the cellar the name typed matches, and others whose name is close. */
    CellarWines,

    /** How many bottles. */
    Bottles,

    /** Where they go. */
    Location,

    /** Invoices and other documents. */
    Documents,
}

object AddFormLayout {
    /**
     * Adding by hand, without a scan: the wine first (its name the first field), then its
     * bottles, where they go and their documents; the label photo can still be added after.
     * With a scan, each app keeps the order it has, the label first.
     */
    val byHand: List<AddFormSection> = listOf(
        AddFormSection.Wine, AddFormSection.CellarWines, AddFormSection.Bottles,
        AddFormSection.Location, AddFormSection.Documents, AddFormSection.Label,
    )
}

/**
 * The places of a wine's bottles on one line, as the Apple row shows them: the first place,
 * and how many other distinct places there are (each counted once). Null when there is none.
 */
data class FirstPlace(val first: String, val others: Int) {
    companion object {
        fun of(labels: List<String>): FirstPlace? {
            val distinct = labels.distinct()
            return distinct.firstOrNull()?.let { FirstPlace(it, distinct.size - 1) }
        }
    }
}
