package app.encave.core

import java.math.BigDecimal
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Required
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonObject

/**
 * A wine tasted or seen somewhere else (at friends', in a restaurant, on a trip),
 * kept to remember it and maybe buy it later. Not a bottle in the cellar.
 */
@KeepGeneratedSerializer
@Serializable(with = DiscoverySerializer::class)
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
    /** The precise score out of 100, when entered out of 20 or 100 (see [Tasting.score100]). */
    val score100: Double? = null,
    /** Keys written by a newer version, kept and written back unchanged (docs/SYNC.md §1). Part of equality, so a merge that only brings them in is still saved. */
    @Transient val unknownKeys: JsonObject = NoUnknownKeys,
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
        // Stale when it no longer matches the rating (docs/SYNC.md §1).
        val score = Score.kept(score100, clamped)
        return if (clamped == rating && clean == tags && score == score100) this else copy(rating = clamped, tags = clean, score100 = score)
    }

    /** The rating from a value entered on a scale (see [Tasting.withRating]). */
    fun withRating(value: Double?, scale: RatingScale): Discovery {
        val entered = value?.let { Score.entered(it, scale) } ?: return copy(rating = null, score100 = null)
        return copy(rating = entered.rating, score100 = entered.score100)
    }

    companion object {
        /** Trimmed, without empty or duplicate tags (ignoring case), in the order given. */
        fun normalized(tags: List<String>): List<String> {
            val seen = HashSet<String>()
            return tags.map(WhiteSpace::trim).filter { it.isNotEmpty() && seen.add(it.lowercase(Locale.ROOT)) }
        }
    }
}

/** What the discoveries list shows. */
data class DiscoveryFilter(val query: String = "", val tag: String? = null, val toBuyOnly: Boolean = false)

/** Most recent first. */
fun Cellar.discoveries(filter: DiscoveryFilter = DiscoveryFilter()): List<Discovery> {
    val needle = filter.query.trim()
    return discoveries.values.filter { discovery ->
        if (filter.toBuyOnly && !discovery.wantsToBuy) return@filter false
        val tag = filter.tag
        if (tag != null && discovery.tags.none { it.equals(tag, ignoreCase = true) }) return@filter false
        if (needle.isEmpty()) return@filter true
        SearchText.matches(needle, wines[discovery.wineId]?.searchFields.orEmpty() + listOfNotNull(discovery.place, discovery.note) + discovery.tags)
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
    val stored = discoveries[discovery.id] ?: throw CellarException(CellarError.UnknownDiscovery)
    if (wines[discovery.wineId] == null) throw CellarException(CellarError.UnknownWine)
    putDiscovery(discovery.copy(unknownKeys = discovery.unknownKeys.orKept(stored.unknownKeys)))
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

/** Keeps the keys of a newer version (see [KeepingUnknownKeys]). */
object DiscoverySerializer : KeepingUnknownKeys<Discovery>(Discovery.generatedSerializer()) {
    override fun unknownKeys(value: Discovery) = value.unknownKeys
    override fun withUnknownKeys(value: Discovery, keys: JsonObject) = value.copy(unknownKeys = keys)

    /** A stale score is read as left out, as CaveKit decodes it. */
    override fun deserialize(decoder: Decoder): Discovery =
        super.deserialize(decoder).let { it.copy(score100 = Score.kept(it.score100, it.rating?.coerceIn(1, 5))) }

    /** A score is written only while it matches the rating ([Score.kept]). */
    override fun serialize(encoder: Encoder, value: Discovery) =
        super.serialize(encoder, value.copy(score100 = Score.kept(value.score100, value.rating)))
}
