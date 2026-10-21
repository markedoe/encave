package app.encave.core

import java.time.Instant
import java.util.UUID

/**
 * A detail that several wines can share, to list the wines of the cellar that have it
 * (tapping the producer, a grape or the vintage on a wine's page). Mirrors CaveKit's `WineAttribute`.
 */
sealed interface WineAttribute {
    data class Producer(val value: String) : WineAttribute
    data class Region(val value: String) : WineAttribute
    data class Appellation(val value: String) : WineAttribute
    data class Country(val value: String) : WineAttribute
    /** One grape of the wine's grapes. */
    data class Grape(val value: String) : WineAttribute
    data class Vintage(val year: Int) : WineAttribute
    data class Color(val color: WineColor) : WineAttribute
    data class Tag(val value: String) : WineAttribute

    companion object {
        /** Same text, ignoring case and surrounding spaces; a blank value matches nothing. */
        internal fun same(a: String?, b: String): Boolean {
            val right = b.trim()
            return a != null && right.isNotEmpty() && a.trim().equals(right, ignoreCase = true)
        }
    }
}

/** Whether the wine has this detail. Text ignores case and surrounding spaces. */
fun Wine.has(attribute: WineAttribute): Boolean = when (attribute) {
    is WineAttribute.Producer -> WineAttribute.same(producer, attribute.value)
    is WineAttribute.Region -> WineAttribute.same(region, attribute.value)
    is WineAttribute.Appellation -> WineAttribute.same(appellation, attribute.value)
    is WineAttribute.Country -> WineAttribute.same(country, attribute.value)
    is WineAttribute.Grape -> grapes.any { WineAttribute.same(it, attribute.value) }
    is WineAttribute.Tag -> tags.orEmpty().any { WineAttribute.same(it, attribute.value) }
    is WineAttribute.Vintage -> vintage == attribute.year
    is WineAttribute.Color -> color == attribute.color
}

/**
 * The wines that share a detail, by name: those with bottles in the cellar, and those with
 * bottles on order only. Each group holds the wine's bottles in the cellar, or its bottles on
 * order when it has none in the cellar yet, so a list row shows either where they are or when
 * they arrive.
 */
fun Cellar.winesSharing(attribute: WineAttribute): List<WineGroup> {
    val inCellar = HashMap<UUID, MutableList<Bottle>>()
    val onOrder = HashMap<UUID, MutableList<Bottle>>()
    for (bottle in bottles.values) {
        when (bottle.status) {
            Bottle.Status.InCellar -> inCellar.getOrPut(bottle.wineId) { mutableListOf() } += bottle
            Bottle.Status.Ordered -> onOrder.getOrPut(bottle.wineId) { mutableListOf() } += bottle
            else -> Unit
        }
    }
    val collator = Cellar.collator()
    return wines.values.filter { it.has(attribute) }.mapNotNull { wine ->
        inCellar[wine.id]?.let { return@mapNotNull WineGroup(wine, it.sortedBy { bottle -> bottle.addedAt }) }
        onOrder[wine.id]?.let { ordered ->
            WineGroup(wine, ordered.sortedWith(compareBy<Bottle>({ it.expectedAt ?: Instant.MAX }, { it.addedAt })))
        }
    }.sortedWith { a, b ->
        val order = Cellar.nameOrder(a.wine, b.wine, collator)
        if (order != 0) order else a.wine.id.toString().compareTo(b.wine.id.toString())
    }
}
