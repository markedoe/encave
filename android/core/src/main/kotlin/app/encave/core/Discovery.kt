package app.encave.core

import java.math.BigDecimal
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

/**
 * A wine tasted or seen somewhere else (at friends', in a restaurant, on a trip),
 * kept to remember it and maybe buy it later. Not a bottle in the cellar.
 */
@Serializable
data class Discovery(
    @Required @Serializable(with = UuidSerializer::class) val id: UUID = UUID.randomUUID(),
    @SerialName("wineID") @Serializable(with = UuidSerializer::class) val wineId: UUID,
    @Required @Serializable(with = InstantSerializer::class) val date: Instant = ReferenceDates.now(),
    val setting: Setting? = null,
    /** Free text: restaurant, city, friend's name. */
    val place: String? = null,
    /** 1 to 5, optional. */
    val rating: Int? = null,
    val note: String? = null,
    val tags: List<String> = emptyList(),
    /** Price seen (on the wine list, in the shop). */
    @Serializable(with = DecimalSerializer::class) val price: BigDecimal? = null,
    val currency: String? = null,
    /** On the shopping list. */
    val wantsToBuy: Boolean = false,
    /** When a bottle of it was added to the cellar from the shopping list. */
    @Serializable(with = InstantSerializer::class) val boughtAt: Instant? = null,
) {
    /** Where the wine was found. */
    @Serializable
    enum class Setting(val raw: String) {
        @SerialName("friends") Friends("friends"),
        @SerialName("restaurant") Restaurant("restaurant"),
        @SerialName("travel") Travel("travel"),
        @SerialName("shop") Shop("shop"),
        @SerialName("tasting") Tasting("tasting"),
        @SerialName("other") Other("other"),
    }

    /** Rating clamped to 1..5 and tags normalized, as the Swift initializer does. */
    fun normalized(): Discovery {
        val clamped = rating?.coerceIn(1, 5)
        val clean = normalized(tags)
        return if (clamped == rating && clean == tags) this else copy(rating = clamped, tags = clean)
    }

    companion object {
        /** Trimmed, without empty or duplicate tags (ignoring case), in the order given. */
        fun normalized(tags: List<String>): List<String> {
            val seen = HashSet<String>()
            return tags.map { it.trim() }.filter { it.isNotEmpty() && seen.add(it.lowercase(Locale.ROOT)) }
        }
    }
}

/** What the discoveries list shows. */
data class DiscoveryFilter(val query: String = "", val tag: String? = null, val toBuyOnly: Boolean = false)

/** Most recent first. */
fun Cellar.discoveries(filter: DiscoveryFilter = DiscoveryFilter()): List<Discovery> {
    val needle = filter.query.trim()
    val folded = Wine.fold(needle)
    return discoveries.values.filter { discovery ->
        if (filter.toBuyOnly && !discovery.wantsToBuy) return@filter false
        val tag = filter.tag
        if (tag != null && discovery.tags.none { it.equals(tag, ignoreCase = true) }) return@filter false
        if (needle.isEmpty()) return@filter true
        if (wines[discovery.wineId]?.matches(needle) == true) return@filter true
        (listOfNotNull(discovery.place, discovery.note) + discovery.tags).any { Wine.fold(it).contains(folded) }
    }.sortedWith(compareByDescending<Discovery> { it.date }.thenBy { it.id.toString().uppercase(Locale.ROOT) })
}

fun Cellar.discoveriesOf(wineId: UUID): List<Discovery> =
    discoveries.values.filter { it.wineId == wineId }.sortedByDescending { it.date }

/** Tags in use, most used first, for suggestions and filters. */
val Cellar.discoveryTags: List<String>
    get() = Cellar.byUse(discoveries.values.flatMap { it.tags })

val Cellar.shoppingListCount: Int get() = discoveries.values.count { it.wantsToBuy }

/**
 * Adds a discovery. If the same wine (name, producer, vintage) is already known,
 * the discovery is linked to it instead of creating a duplicate. Returns what was stored.
 */
fun Cellar.addDiscovery(discovery: Discovery, wine: Wine): Discovery {
    val existing = wines[wine.id] ?: wines.values.firstOrNull { it.identityKey == wine.identityKey }
    val stored = if (existing != null) {
        if (existing.photoId == null && wine.photoId != null) putWine(existing.copy(photoId = wine.photoId))
        discovery.copy(wineId = existing.id)
    } else {
        putWine(wine)
        discovery.copy(wineId = wine.id)
    }.normalized()
    putDiscovery(stored)
    return stored
}

fun Cellar.updateDiscovery(discovery: Discovery) {
    if (discoveries[discovery.id] == null) throw CellarException(CellarError.UnknownDiscovery)
    if (wines[discovery.wineId] == null) throw CellarException(CellarError.UnknownWine)
    putDiscovery(discovery)
}

/** Removes a discovery, and its wine if nothing else refers to it. */
fun Cellar.deleteDiscovery(id: UUID) {
    removeDiscovery(id)
    removeOrphanWines()
}

/** Takes the wine off the shopping list once a bottle was bought. */
fun Cellar.markBought(id: UUID, date: Instant = ReferenceDates.now()) {
    val discovery = discoveries[id] ?: throw CellarException(CellarError.UnknownDiscovery)
    putDiscovery(discovery.copy(wantsToBuy = false, boughtAt = date))
}
