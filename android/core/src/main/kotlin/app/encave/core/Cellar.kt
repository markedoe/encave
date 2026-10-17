package app.encave.core

import java.math.BigDecimal
import java.text.Collator
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The whole cellar: wines, bottles, racks and settings.
 *
 * Mirrors the Swift value type: commands mutate this instance, so callers that
 * publish a cellar (the UI repository) must work on [copy] and publish the copy.
 */
class Cellar(
    wines: Collection<Wine> = emptyList(),
    bottles: Collection<Bottle> = emptyList(),
    racks: Collection<Rack> = emptyList(),
    var settings: CellarSettings = CellarSettings(),
    discoveries: Collection<Discovery> = emptyList(),
) {
    var schemaVersion: Int = CURRENT_SCHEMA_VERSION
        internal set

    private val wineMap = LinkedHashMap<UUID, Wine>().apply { wines.forEach { put(it.id, it) } }
    private val bottleMap = LinkedHashMap<UUID, Bottle>().apply { bottles.forEach { put(it.id, it) } }
    private val rackList = racks.map { it.normalized() }.toMutableList()
    private val discoveryMap = LinkedHashMap<UUID, Discovery>().apply { discoveries.forEach { put(it.id, it.normalized()) } }

    val wines: Map<UUID, Wine> get() = wineMap
    val bottles: Map<UUID, Bottle> get() = bottleMap
    val racks: List<Rack> get() = rackList

    /** Wines tasted or seen elsewhere, to remember or buy. */
    val discoveries: Map<UUID, Discovery> get() = discoveryMap

    val isEmpty: Boolean get() = wineMap.isEmpty() && bottleMap.isEmpty() && rackList.isEmpty() && discoveryMap.isEmpty()

    /** An independent copy: changes to it never affect this cellar. */
    fun copy(): Cellar = Cellar(wineMap.values, bottleMap.values, rackList, settings, discoveryMap.values).also {
        it.schemaVersion = schemaVersion
    }

    override fun equals(other: Any?): Boolean =
        other is Cellar && schemaVersion == other.schemaVersion && wineMap == other.wineMap &&
            bottleMap == other.bottleMap && rackList == other.rackList && settings == other.settings &&
            discoveryMap == other.discoveryMap

    override fun hashCode(): Int = listOf(schemaVersion, wineMap, bottleMap, rackList, settings, discoveryMap).hashCode()

    override fun toString(): String = "Cellar(wines=${wineMap.size}, bottles=${bottleMap.size}, racks=${rackList.size})"

    // region Queries

    val bottlesInCellar: List<Bottle> get() = bottleMap.values.filter { it.status == Bottle.Status.InCellar }

    val consumedBottles: List<Bottle>
        get() = bottleMap.values
            .filter { it.status == Bottle.Status.Consumed }
            .sortedByDescending { it.tasting?.date ?: Instant.MIN }

    /** Wines that still have bottles in the cellar, sorted by name then vintage. */
    fun groups(query: String = ""): List<WineGroup> =
        groups(CellarFilter(query = query), CellarSort.Name, LocalDate.now().year)

    fun groups(filter: CellarFilter, sort: CellarSort, year: Int): List<WineGroup> {
        val needle = filter.query.trim()
        val result = bottlesInCellar.groupBy { it.wineId }.mapNotNull { (wineId, bottles) ->
            val wine = wineMap[wineId] ?: return@mapNotNull null
            if (needle.isNotEmpty() && !wine.matches(needle)) return@mapNotNull null
            if (filter.colors.isNotEmpty() && (wine.color == null || wine.color !in filter.colors)) return@mapNotNull null
            if (filter.maturities.isNotEmpty() && Maturity.of(wine, year) !in filter.maturities) return@mapNotNull null
            var kept = bottles
            if (filter.rackId != null) {
                kept = bottles.filter { it.slot?.rackId == filter.rackId }
                if (kept.isEmpty()) return@mapNotNull null
            }
            WineGroup(wine, kept.sortedBy { it.addedAt })
        }
        val collator = collator()
        val byName = Comparator<WineGroup> { a, b -> nameOrder(a.wine, b.wine, collator) }
        val comparator: Comparator<WineGroup> = when (sort) {
            CellarSort.Name -> byName
            CellarSort.Vintage -> compareBy<WineGroup> { it.wine.vintage ?: Int.MAX_VALUE }.then(byName)
            CellarSort.RecentlyAdded -> compareByDescending<WineGroup> { g -> g.bottles.maxOfOrNull { it.addedAt } ?: Instant.MIN }
                .then(byName)
            CellarSort.Readiness -> compareBy<WineGroup> { Maturity.of(it.wine, year) }
                .thenBy { it.wine.drinkUntil ?: Int.MAX_VALUE }
                .then(byName)
            CellarSort.Region -> Comparator<WineGroup> { a, b ->
                collator.compare(regionKey(a.wine), regionKey(b.wine))
            }.then(byName)
        }
        return result.sortedWith(comparator)
    }

    /** Groups bucketed by maturity for "Tonight", most urgent first. */
    fun groupsByMaturity(year: Int): List<MaturitySection> =
        groups().groupBy { Maturity.of(it.wine, year) }
            .toSortedMap()
            .map { (maturity, groups) -> MaturitySection(maturity, groups) }

    /** Bottles of a wine still in the cellar. */
    fun bottlesOf(wineId: UUID): List<Bottle> = bottlesInCellar.filter { it.wineId == wineId }.sortedBy { it.addedAt }

    /** Past tastings of a wine, most recent first. */
    fun tastingsOf(wineId: UUID): List<Bottle> = consumedBottles.filter { it.wineId == wineId }

    fun bottleAt(slot: Slot): Bottle? = bottlesInCellar.firstOrNull { it.slot == slot }

    fun freeSlots(rack: Rack): List<Slot> {
        val taken = bottlesInCellar.mapNotNull { it.slot }.toSet()
        val result = mutableListOf<Slot>()
        for (row in 0 until rack.rows) {
            for (column in 0 until rack.columns) {
                val slot = Slot(rack.id, row, column)
                if (slot !in taken) result += slot
            }
        }
        return result
    }

    fun occupancy(rack: Rack): Int = bottlesInCellar.count { it.slot?.rackId == rack.id }

    fun rack(id: UUID): Rack? = rackList.firstOrNull { it.id == id }

    /** "Cellar A · B3", the storage note, or null when the bottle has no location. */
    fun locationLabel(bottle: Bottle): String? {
        val slot = bottle.slot
        val rack = slot?.let { rack(it.rackId) }
        if (slot != null && rack != null) return "${rack.name} · ${rack.label(slot)}"
        val note = bottle.storageNote
        if (!note.isNullOrEmpty()) return note
        return null
    }

    /** Existing wines matching a query, for "add another bottle of...". */
    fun wineSuggestions(query: String, limit: Int = 8): List<Wine> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val collator = collator()
        return wineMap.values.filter { it.matches(needle) }
            .sortedWith { a, b -> nameOrder(a, b, collator) }
            .take(limit)
    }

    /** Distinct values already used, for autocompletion. */
    fun knownValues(field: (Wine) -> String?): List<String> {
        val collator = collator()
        return wineMap.values.mapNotNull(field).filter { it.isNotEmpty() }.distinct().sortedWith(collator)
    }

    // endregion

    // region Commands

    /**
     * Adds [quantity] bottles of a wine, placing them in the given slots (in order) when provided.
     * If the wine already exists (same id), its record is updated.
     */
    fun addBottles(
        wine: Wine,
        quantity: Int = 1,
        slots: List<Slot> = emptyList(),
        purchase: PurchaseInfo = PurchaseInfo(),
        addedAt: Instant = Instant.now(),
    ): List<Bottle> {
        if (quantity <= 0) return emptyList()
        slots.forEach { validateFree(it) }
        if (slots.toSet().size != slots.size) throw CellarException(CellarError.SlotTaken)
        wineMap[wine.id] = wine
        return (0 until quantity).map { index ->
            Bottle(
                wineId = wine.id, slot = slots.getOrNull(index),
                storageNote = purchase.storageNote, addedAt = addedAt, purchaseDate = purchase.date,
                purchasePrice = purchase.price, currency = purchase.currency ?: settings.currency,
                purchasePlace = purchase.place, receivedFrom = purchase.receivedFrom, sizeML = purchase.sizeML,
            ).also { bottleMap[it.id] = it }
        }
    }

    fun drink(bottleId: UUID, tasting: Tasting) {
        val bottle = bottleMap[bottleId] ?: throw CellarException(CellarError.UnknownBottle)
        if (bottle.status != Bottle.Status.InCellar) throw CellarException(CellarError.AlreadyConsumed)
        bottleMap[bottleId] = bottle.copy(status = Bottle.Status.Consumed, slot = null, tasting = tasting.normalized())
    }

    /** Updates the tasting of a bottle already drunk (adding a full note later). */
    fun updateTasting(bottleId: UUID, tasting: Tasting) {
        val bottle = bottleMap[bottleId]
        if (bottle == null || bottle.status != Bottle.Status.Consumed) throw CellarException(CellarError.UnknownBottle)
        bottleMap[bottleId] = bottle.copy(tasting = tasting.normalized())
    }

    /** Reverts a drink, putting the bottle back in its previous slot if still free. */
    fun undoDrink(bottleId: UUID, previousSlot: Slot?) {
        val bottle = bottleMap[bottleId] ?: return
        if (bottle.status == Bottle.Status.InCellar) return
        val slot = if (previousSlot != null && bottleAt(previousSlot) == null) previousSlot else bottle.slot
        bottleMap[bottleId] = bottle.copy(status = Bottle.Status.InCellar, tasting = null, slot = slot)
    }

    /** Marks a bottle as given away, sold or broken. */
    fun markGone(bottleId: UUID) {
        val bottle = bottleMap[bottleId] ?: throw CellarException(CellarError.UnknownBottle)
        bottleMap[bottleId] = bottle.copy(status = Bottle.Status.Gone, slot = null)
    }

    /** Deletes a bottle record entirely (entered by mistake). */
    fun deleteBottle(bottleId: UUID) {
        bottleMap.remove(bottleId)
        removeOrphanWines()
    }

    fun updateBottle(bottle: Bottle) {
        if (bottleMap[bottle.id] == null) throw CellarException(CellarError.UnknownBottle)
        bottle.slot?.let { slot ->
            validateInRange(slot)
            val occupant = bottleAt(slot)
            if (occupant != null && occupant.id != bottle.id) throw CellarException(CellarError.SlotTaken)
        }
        bottleMap[bottle.id] = bottle.copy(tasting = bottle.tasting?.normalized())
    }

    fun move(bottleId: UUID, slot: Slot?) {
        val bottle = bottleMap[bottleId] ?: throw CellarException(CellarError.UnknownBottle)
        if (slot != null) {
            val occupant = bottleAt(slot)
            if (occupant != null && occupant.id != bottleId) throw CellarException(CellarError.SlotTaken)
            validateInRange(slot)
        }
        bottleMap[bottleId] = bottle.copy(slot = slot)
    }

    /** Swaps the contents of two slots (either may be empty). */
    fun swap(a: Slot, b: Slot) {
        validateInRange(a)
        validateInRange(b)
        val first = bottleAt(a)
        val second = bottleAt(b)
        if (first != null) bottleMap[first.id] = first.copy(slot = b)
        if (second != null) bottleMap[second.id] = second.copy(slot = a)
    }

    fun updateWine(wine: Wine) {
        if (wineMap[wine.id] == null) throw CellarException(CellarError.UnknownWine)
        wineMap[wine.id] = wine
    }

    /** Deletes a wine with every bottle and discovery of it. */
    fun deleteWine(wineId: UUID) {
        wineMap.remove(wineId)
        bottleMap.values.removeAll { it.wineId == wineId }
        discoveryMap.values.removeAll { it.wineId == wineId }
    }

    fun addRack(rack: Rack) {
        rackList += rack.normalized()
    }

    /** Updates name, layout or size. Shrinking is refused if it would drop bottles. */
    fun updateRack(rack: Rack) {
        val updated = rack.normalized()
        val index = rackList.indexOfFirst { it.id == updated.id }
        if (index < 0) throw CellarException(CellarError.UnknownRack)
        val outside = bottlesInCellar.any {
            val slot = it.slot
            slot != null && slot.rackId == updated.id && (slot.row >= updated.rows || slot.column >= updated.columns)
        }
        if (outside) throw CellarException(CellarError.RackNotEmpty)
        rackList[index] = updated
    }

    /** Same semantics as SwiftUI's `onMove(fromOffsets:toOffset:)`. */
    fun moveRacks(fromOffsets: Set<Int>, toOffset: Int) {
        val sorted = fromOffsets.sorted()
        val moving = sorted.map { rackList[it] }
        sorted.reversed().forEach { rackList.removeAt(it) }
        val adjusted = toOffset - fromOffsets.count { it < toOffset }
        rackList.addAll(adjusted.coerceIn(0, rackList.size), moving)
    }

    /** Removes a rack; its bottles stay in the cellar without a slot. */
    fun removeRack(rackId: UUID) {
        rackList.removeAll { it.id == rackId }
        bottleMap.entries.forEach { entry ->
            if (entry.value.slot?.rackId == rackId) entry.setValue(entry.value.copy(slot = null))
        }
    }

    internal fun removeOrphanWines() {
        val used = bottleMap.values.map { it.wineId }.toSet() + discoveryMap.values.map { it.wineId }
        wineMap.keys.retainAll(used)
    }

    private fun validateInRange(slot: Slot) {
        val rack = rack(slot.rackId) ?: throw CellarException(CellarError.UnknownRack)
        if (slot.row !in 0 until rack.rows || slot.column !in 0 until rack.columns) {
            throw CellarException(CellarError.SlotOutOfRange)
        }
    }

    private fun validateFree(slot: Slot) {
        validateInRange(slot)
        if (bottleAt(slot) != null) throw CellarException(CellarError.SlotTaken)
    }

    // endregion

    // region Import support (used by ImportExport.kt)

    internal fun putWine(wine: Wine) {
        wineMap[wine.id] = wine
    }

    internal fun putBottle(bottle: Bottle) {
        bottleMap[bottle.id] = bottle
    }

    internal fun putDiscovery(discovery: Discovery) {
        discoveryMap[discovery.id] = discovery.normalized()
    }

    internal fun removeDiscovery(id: UUID) {
        discoveryMap.remove(id)
    }

    internal fun appendRack(rack: Rack) {
        rackList += rack.normalized()
    }

    // endregion

    companion object {
        const val CURRENT_SCHEMA_VERSION = 2

        /** Case-insensitive, accent-sensitive, like `localizedCaseInsensitiveCompare`. */
        fun collator(): Collator = Collator.getInstance().apply { strength = Collator.SECONDARY }

        fun nameOrder(a: Wine, b: Wine, collator: Collator = collator()): Int {
            val order = collator.compare(a.name, b.name)
            if (order != 0) return order
            return (a.vintage ?: 0).compareTo(b.vintage ?: 0)
        }

        private fun regionKey(wine: Wine) = listOf(wine.country ?: "", wine.region ?: "").joinToString("/")
    }
}

/** Bottles of one wine still in the cellar, for list rows like "Château X 2015 ×3". */
data class WineGroup(val wine: Wine, val bottles: List<Bottle>) {
    val id: UUID get() = wine.id
    val count: Int get() = bottles.size
}

data class MaturitySection(val maturity: Maturity, val groups: List<WineGroup>)

/** How the cellar list is sorted. */
enum class CellarSort { Name, Vintage, RecentlyAdded, Readiness, Region }

/** Filters for the cellar list. Empty sets mean "all". */
data class CellarFilter(
    val query: String = "",
    val colors: Set<WineColor> = emptySet(),
    val maturities: Set<Maturity> = emptySet(),
    val rackId: UUID? = null,
) {
    val isActive: Boolean get() = colors.isNotEmpty() || maturities.isNotEmpty() || rackId != null
}

enum class CellarError { UnknownWine, UnknownBottle, UnknownRack, UnknownDiscovery, SlotTaken, SlotOutOfRange, AlreadyConsumed, RackNotEmpty }

class CellarException(val error: CellarError) : Exception(error.name)

/** Purchase details applied to every bottle added in one go. */
data class PurchaseInfo(
    val date: Instant? = null,
    val price: BigDecimal? = null,
    val currency: String? = null,
    val place: String? = null,
    val receivedFrom: String? = null,
    val storageNote: String? = null,
    val sizeML: Int = 750,
)
