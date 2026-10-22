package app.encave.core

import java.math.BigDecimal
import java.text.Collator
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
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

    // The first object of each ID, as Swift's initializer keeps: data from another device
    // or a backup must not break the assumptions of the merge and the UI.
    private val wineMap = LinkedHashMap<UUID, Wine>().apply { wines.forEach { putIfAbsent(it.id, it.normalized()) } }
    private val bottleMap = LinkedHashMap<UUID, Bottle>().apply { bottles.forEach { putIfAbsent(it.id, it.normalized()) } }
    private val rackList = racks.distinctBy { it.id }.map { it.normalized() }.toMutableList()
    private val discoveryMap = LinkedHashMap<UUID, Discovery>().apply { discoveries.forEach { putIfAbsent(it.id, it.normalized()) } }

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

    /** Bottles bought but not delivered yet. */
    /** Every photo the cellar refers to: each wine's label photo and its other photos. */
    val photoIds: Set<String> get() = wineMap.values.flatMap { it.allPhotoIds }.toSet()

    /** Every document of the wines and bottles (to sync, and to keep when cleaning up). */
    val attachmentIds: Set<String>
        get() = (wineMap.values.flatMap { it.attachments.orEmpty() } + bottleMap.values.flatMap { it.attachments.orEmpty() })
            .map { it.id }.toSet()

    /** Every file the cellar refers to, photos and documents: what a sync exchanges. */
    val fileRefs: Set<FileRef>
        get() = photoIds.map { FileRef(it, FileKind.Photo) }.toSet() + attachmentIds.map { FileRef(it, FileKind.Document) }

    /** The documents of a wine or a bottle, in the order they were added. */
    fun attachments(target: AttachmentTarget): List<Attachment> = when (target) {
        is AttachmentTarget.OfWine -> wineMap[target.id]?.attachments.orEmpty()
        is AttachmentTarget.OfBottle -> bottleMap[target.id]?.attachments.orEmpty()
    }

    val bottlesOnOrder: List<Bottle> get() = bottleMap.values.filter { it.status == Bottle.Status.Ordered }

    /** Wines with bottles on order, the soonest expected first (unknown dates last). */
    fun orderGroups(): List<WineGroup> {
        val collator = collator()
        val far = Instant.MAX
        return bottlesOnOrder.groupBy { it.wineId }.mapNotNull { (wineId, bottles) ->
            val wine = wineMap[wineId] ?: return@mapNotNull null
            WineGroup(wine, bottles.sortedWith(compareBy<Bottle>({ it.expectedAt ?: far }, { it.addedAt })))
        }.sortedWith { a, b ->
            val da = a.bottles.first().expectedAt ?: far
            val db = b.bottles.first().expectedAt ?: far
            if (da != db) da.compareTo(db) else nameOrder(a.wine, b.wine, collator)
        }
    }

    /** Occasions bottles in the cellar are reserved for, by name (case-insensitive). */
    val reservations: List<String>
        get() {
            val names = LinkedHashMap<String, String>()
            for (bottle in bottlesInCellar) {
                val name = bottle.reservedFor ?: continue
                names.putIfAbsent(name.lowercase(java.util.Locale.ROOT), name)
            }
            val collator = collator()
            return names.values.sortedWith(collator::compare)
        }

    /** Tags of wines with bottles in the cellar, most used first. */
    val wineTags: List<String>
        get() {
            val inCellar = bottlesInCellar.map { it.wineId }.toSet()
            return byUse(wineMap.values.filter { it.id in inCellar }.flatMap { it.tags.orEmpty() })
        }

    /** Grapes of the wines in the cellar, the most used first (for the filter chips). */
    val wineGrapes: List<String>
        get() {
            val inCellar = bottlesInCellar.map { it.wineId }.toSet()
            return byUse(wineMap.values.filter { it.id in inCellar }.flatMap { it.grapes }.map { it.trim() }.filter { it.isNotEmpty() })
        }

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
            if (filter.tag != null && !wine.hasTag(filter.tag)) return@mapNotNull null
            if (filter.grape != null && !wine.hasGrape(filter.grape)) return@mapNotNull null
            var kept = bottles
            if (filter.rackId != null) {
                kept = kept.filter { it.slot?.rackId == filter.rackId }
                if (kept.isEmpty()) return@mapNotNull null
            }
            if (filter.reservation != null) {
                kept = kept.filter { it.reservedFor?.equals(filter.reservation, ignoreCase = true) == true }
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
        // Bottles reserved for an occasion are left out: they are not for tonight.
        groups().mapNotNull { group ->
            group.bottles.filter { it.reservedFor == null }.takeIf { it.isNotEmpty() }?.let { WineGroup(group.wine, it) }
        }.groupBy { Maturity.of(it.wine, year) }
            .toSortedMap()
            .map { (maturity, groups) -> MaturitySection(maturity, groups) }

    /** Bottles of a wine still in the cellar. */
    fun bottlesOf(wineId: UUID): List<Bottle> = bottlesInCellar.filter { it.wineId == wineId }.sortedBy { it.addedAt }

    /** Past tastings of a wine, most recent first. */
    fun tastingsOf(wineId: UUID): List<Bottle> = consumedBottles.filter { it.wineId == wineId }

    fun bottleAt(slot: Slot): Bottle? = bottlesInCellar.firstOrNull { it.slot == slot }

    fun freeSlots(rack: Rack): List<Slot> {
        val taken = bottlesInCellar.mapNotNull { it.slot }.toSet()
        return rack.allSlots.filter { it !in taken }
    }

    /** Bottles of a rack by cell, front first. */
    fun bottlesByCell(rack: Rack): Map<RackCell, List<Bottle>> =
        bottlesInCellar.filter { it.slot?.rackId == rack.id }.groupBy { it.slot!!.cell }
            .mapValues { (_, bottles) -> bottles.sortedBy { it.slot?.depth ?: 0 } }

    /** Bottles in one cell, front first. */
    fun bottlesIn(cell: RackCell): List<Bottle> =
        bottlesInCellar.filter { it.slot?.cell == cell }.sortedBy { it.slot?.depth ?: 0 }

    /** The first free place in a cell (the front, or the next place in a bin). */
    fun freeSlot(cell: RackCell): Slot? {
        val rack = rack(cell.rackId) ?: return null
        if (!rack.contains(cell)) return null
        val taken = bottlesIn(cell).mapNotNull { it.slot?.depth }.toSet()
        return (0 until rack.positionsPerCell).firstOrNull { it !in taken }?.let { Slot(cell.rackId, cell.row, cell.column, it) }
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

    /** The wine a barcode was seen on, preferring one with bottles in the cellar (see [wines]). */
    fun wine(barcode: String): Wine? = wines(barcode).firstOrNull()

    /**
     * The wines a barcode was seen on: producers often keep one barcode for every vintage.
     * Those with bottles in the cellar first, then the newest vintage (non-vintage last).
     */
    fun wines(barcode: String): List<Wine> {
        val code = Wine.normalizedBarcode(barcode)
        if (code.isEmpty()) return emptyList()
        val inCellar = bottlesInCellar.map { it.wineId }.toSet()
        return wineMap.values.filter { code in it.barcodes.orEmpty() }
            .sortedWith(
                compareBy<Wine>({ if (it.id in inCellar) 0 else 1 }, { -(it.vintage?.toLong() ?: Long.MIN_VALUE / 2) })
                    .thenBy { it.displayName }.thenBy { it.id.toString().uppercase(Locale.ROOT) },
            )
    }

    /**
     * A wine of the cellar that [matching] would duplicate: the same name and vintage, ignoring
     * case, accents and extra spaces, and the same producer or no producer on one side.
     * The draft's own record (same id) is left out. The same producer is preferred, then
     * a wine with bottles in the cellar.
     */
    fun wine(matching: Wine): Wine? {
        val draft = matching
        val name = Wine.matchKey(draft.name)
        if (name.isEmpty()) return null
        val producer = Wine.matchKey(draft.producer.orEmpty())
        val inCellar = bottlesInCellar.map { it.wineId }.toSet()
        return wineMap.values.filter { wine ->
            if (wine.id == draft.id || wine.vintage != draft.vintage || Wine.matchKey(wine.name) != name) return@filter false
            val other = Wine.matchKey(wine.producer.orEmpty())
            other == producer || other.isEmpty() || producer.isEmpty()
        }.minWithOrNull(
            compareBy<Wine>({ if (Wine.matchKey(it.producer.orEmpty()) == producer) 0 else 1 }, { if (it.id in inCellar) 0 else 1 })
                .thenBy { it.id.toString().uppercase(Locale.ROOT) },
        )
    }

    /** Existing wines matching a query, for "add another bottle of..."; [inCellarOnly]: with bottles in the cellar. */
    fun wineSuggestions(query: String, limit: Int = 8, inCellarOnly: Boolean = false): List<Wine> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val collator = collator()
        val inCellar = if (inCellarOnly) bottlesInCellar.mapTo(HashSet()) { it.wineId } else null
        return wineMap.values.filter { (inCellar == null || it.id in inCellar) && it.matches(needle) }
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
        addedAt: Instant = ReferenceDates.now(),
    ): List<Bottle> {
        if (quantity <= 0) return emptyList()
        if (purchase.isOrdered && slots.isNotEmpty()) throw CellarException(CellarError.NotReceived)
        slots.forEach { validateFree(it) }
        if (slots.toSet().size != slots.size) throw CellarException(CellarError.SlotTaken)
        wineMap[wine.id] = wine.normalized()
        return (0 until quantity).map { index ->
            Bottle(
                wineId = wine.id, slot = slots.getOrNull(index),
                storageNote = purchase.storageNote, addedAt = addedAt, purchaseDate = purchase.date,
                purchasePrice = purchase.price, currency = purchase.currency ?: settings.currency,
                purchasePlace = purchase.place, receivedFrom = purchase.receivedFrom, sizeML = purchase.sizeML,
                status = if (purchase.isOrdered) Bottle.Status.Ordered else Bottle.Status.InCellar,
                expectedAt = if (purchase.isOrdered) purchase.expectedAt else null,
            ).also { bottleMap[it.id] = it }
        }
    }

    /**
     * Marks ordered bottles as delivered, placing them in the given slots (in order) when
     * provided. They count as added on [at].
     */
    fun receive(bottleIds: List<UUID>, slots: List<Slot> = emptyList(), at: Instant = ReferenceDates.now()) {
        if (bottleIds.any { bottleMap[it]?.status != Bottle.Status.Ordered }) throw CellarException(CellarError.UnknownBottle)
        slots.forEach { validateFree(it) }
        if (slots.toSet().size != slots.size) throw CellarException(CellarError.SlotTaken)
        bottleIds.forEachIndexed { index, id ->
            val bottle = bottleMap[id] ?: return@forEachIndexed
            bottleMap[id] = bottle.copy(status = Bottle.Status.InCellar, expectedAt = null, addedAt = at, slot = slots.getOrNull(index))
        }
    }

    /**
     * Puts the bottles of these wines that have no place into the free places of a rack, in
     * order. Returns how many were placed.
     */
    fun place(wineIds: Set<UUID>, rackId: UUID): Int {
        val rack = rack(rackId) ?: throw CellarException(CellarError.UnknownRack)
        val collator = collator()
        val waiting = bottlesInCellar.filter { it.wineId in wineIds && it.slot == null }.sortedWith { a, b ->
            val wa = wineMap[a.wineId]
            val wb = wineMap[b.wineId]
            if (a.wineId != b.wineId && wa != null && wb != null) nameOrder(wa, wb, collator) else a.addedAt.compareTo(b.addedAt)
        }
        val free = freeSlots(rack)
        waiting.zip(free).forEach { (bottle, slot) -> bottleMap[bottle.id] = bottle.copy(slot = slot, storageNote = null) }
        return minOf(waiting.size, free.size)
    }

    /** Takes the bottles of these wines out of their places (they stay in the cellar). Returns how many moved. */
    fun unplace(wineIds: Set<UUID>): Int {
        var count = 0
        bottleMap.entries.forEach { entry ->
            val bottle = entry.value
            if (bottle.wineId in wineIds && bottle.status == Bottle.Status.InCellar && bottle.slot != null) {
                entry.setValue(bottle.copy(slot = null))
                count++
            }
        }
        return count
    }

    /** Saves a search under a name, replacing one with the same name (ignoring case). */
    fun saveSearch(name: String, filter: CellarFilter) {
        val search = SavedSearch.of(name, filter)
        if (search.name.isEmpty()) return
        val list = settings.savedSearches.orEmpty().filterNot { it.name.equals(search.name, ignoreCase = true) } + search
        settings = settings.copy(savedSearches = SavedSearch.ordered(list))
    }

    fun deleteSearch(id: UUID) {
        settings = settings.copy(savedSearches = settings.savedSearches.orEmpty().filter { it.id != id }.ifEmpty { null })
    }

    /** Reserves bottles in the cellar for an occasion, or frees them (null or blank). */
    fun reserve(bottleIds: List<UUID>, occasion: String?) {
        val name = occasion?.trim()?.ifEmpty { null }
        if (bottleIds.any { bottleMap[it]?.status != Bottle.Status.InCellar }) throw CellarException(CellarError.UnknownBottle)
        bottleIds.forEach { id -> bottleMap[id] = bottleMap.getValue(id).copy(reservedFor = name) }
    }

    fun drink(bottleId: UUID, tasting: Tasting) {
        val bottle = bottleMap[bottleId] ?: throw CellarException(CellarError.UnknownBottle)
        if (bottle.status == Bottle.Status.Ordered) throw CellarException(CellarError.NotReceived)
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
        // Back in its place if it is still free and still exists (the rack may be gone or smaller).
        val free = previousSlot != null && bottleAt(previousSlot) == null && rack(previousSlot.rackId)?.contains(previousSlot) == true
        val slot = if (free) previousSlot else bottle.slot
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
            if (bottle.status == Bottle.Status.Ordered) throw CellarException(CellarError.NotReceived)
            validateInRange(slot)
            val occupant = bottleAt(slot)
            if (occupant != null && occupant.id != bottle.id) throw CellarException(CellarError.SlotTaken)
        }
        bottleMap[bottle.id] = bottle.normalized().copy(tasting = bottle.tasting?.normalized())
    }

    fun move(bottleId: UUID, slot: Slot?) {
        val bottle = bottleMap[bottleId] ?: throw CellarException(CellarError.UnknownBottle)
        if (slot != null) {
            // A bottle on order has no place until it is received, as in Swift.
            if (bottle.status == Bottle.Status.Ordered) throw CellarException(CellarError.NotReceived)
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
        wineMap[wine.id] = wine.normalized()
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

    /**
     * Updates name, layout or size. [rowMap] moves the bottles of a row to another row (null:
     * the row is gone), keeping their column and depth; rows it leaves out stay as they are.
     * A bottle whose new place is not in the rack is displaced: with [takingOut] it stays in
     * the cellar without a place, otherwise the update is refused.
     */
    fun updateRack(rack: Rack, rowMap: Map<Int, Int?> = emptyMap(), takingOut: Boolean = false) {
        val updated = rack.normalized()
        val index = rackList.indexOfFirst { it.id == updated.id }
        if (index < 0) throw CellarException(CellarError.UnknownRack)
        val displaced = displacedBottles(updated, rowMap).map { it.id }.toSet()
        if (displaced.isNotEmpty() && !takingOut) throw CellarException(CellarError.RackNotEmpty)
        bottlesInCellar.forEach { bottle ->
            val slot = bottle.slot ?: return@forEach
            if (slot.rackId != updated.id) return@forEach
            bottleMap[bottle.id] = bottle.copy(slot = if (bottle.id in displaced) null else Rack.moved(slot, rowMap))
        }
        rackList[index] = updated
    }

    /** Bottles of this rack that an update with [rowMap] would leave without a place, in the order of their places. */
    fun displacedBottles(rack: Rack, rowMap: Map<Int, Int?> = emptyMap()): List<Bottle> {
        val updated = rack.normalized()
        return bottlesInCellar.filter { bottle ->
            val slot = bottle.slot
            if (slot == null || slot.rackId != updated.id) return@filter false
            val moved = Rack.moved(slot, rowMap)
            moved == null || !updated.contains(moved)
        }.sortedWith(compareBy({ it.slot!!.row }, { it.slot!!.column }, { it.slot!!.depth }))
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

    /** Adds documents at the end, or at [index] (one already there, by ID, is kept as it is). */
    fun attach(attachments: List<Attachment>, target: AttachmentTarget, index: Int? = null) = changeAttachments(target) { list ->
        val new = attachments.filter { item -> list.none { it.id == item.id } }
        list.addAll(minOf(index ?: list.size, list.size), new)
    }

    /** Renames a document. A blank name keeps the old one. */
    fun renameAttachment(id: String, target: AttachmentTarget, name: String) {
        if (Attachment.normalizedName(name).isEmpty()) return
        changeAttachments(target) { list ->
            val index = list.indexOfFirst { it.id == id }
            if (index >= 0) list[index] = list[index].copy(name = name).normalized()
        }
    }

    /** Removes a document; returns it and where it was, for Undo (the file stays for a while). */
    fun removeAttachment(id: String, target: AttachmentTarget): Pair<Attachment, Int>? {
        var removed: Pair<Attachment, Int>? = null
        changeAttachments(target) { list ->
            val index = list.indexOfFirst { it.id == id }
            if (index >= 0) removed = list.removeAt(index) to index
        }
        return removed
    }

    private fun changeAttachments(target: AttachmentTarget, change: (MutableList<Attachment>) -> Unit) {
        when (target) {
            is AttachmentTarget.OfWine -> {
                val wine = wineMap[target.id] ?: throw CellarException(CellarError.UnknownWine)
                val list = wine.attachments.orEmpty().toMutableList()
                change(list)
                wineMap[wine.id] = wine.copy(attachments = list).normalized()
            }
            is AttachmentTarget.OfBottle -> {
                val bottle = bottleMap[target.id] ?: throw CellarException(CellarError.UnknownBottle)
                val list = bottle.attachments.orEmpty().toMutableList()
                change(list)
                bottleMap[bottle.id] = bottle.copy(attachments = list).normalized()
            }
        }
    }

    internal fun removeOrphanWines() {
        val used = bottleMap.values.map { it.wineId }.toSet() + discoveryMap.values.map { it.wineId }
        wineMap.keys.retainAll(used)
    }

    private fun validateInRange(slot: Slot) {
        val rack = rack(slot.rackId) ?: throw CellarException(CellarError.UnknownRack)
        if (!rack.contains(slot)) throw CellarException(CellarError.SlotOutOfRange)
    }

    private fun validateFree(slot: Slot) {
        validateInRange(slot)
        if (bottleAt(slot) != null) throw CellarException(CellarError.SlotTaken)
    }

    // endregion

    // region Import support (used by ImportExport.kt)

    internal fun putWine(wine: Wine) {
        wineMap[wine.id] = wine.normalized()
    }

    internal fun putBottle(bottle: Bottle) {
        bottleMap[bottle.id] = bottle.normalized()
    }

    /** Gives a bottle a new ID (the sample's fixed IDs). */
    internal fun rekeyBottle(oldId: UUID, bottle: Bottle) {
        bottleMap.remove(oldId)
        bottleMap[bottle.id] = bottle.normalized()
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

        /**
         * Names counted case-insensitively, the most used first. Each is shown with its most
         * used spelling (ties: the first in UTF-16 order), so the label does not depend on
         * the order records are stored in, and is the same on every platform.
         */
        internal fun byUse(names: List<String>): List<String> {
            val spellings = HashMap<String, HashMap<String, Int>>()
            for (name in names) spellings.getOrPut(name.lowercase(java.util.Locale.ROOT)) { HashMap() }.merge(name, 1, Int::plus)
            val entries = spellings.values.map { counts ->
                val shown = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).first().key
                shown to counts.values.sum()
            }
            val collator = collator()
            return entries.sortedWith { a, b -> if (a.second != b.second) b.second - a.second else collator.compare(a.first, b.first) }
                .map { it.first }
        }

        /** Case-insensitive, accent-sensitive, like `localizedCaseInsensitiveCompare`. */
        fun collator(): Collator = Collator.getInstance().apply { strength = Collator.SECONDARY }

        /**
         * Name, then vintage, then producer, then id: a total order, so wines alike keep
         * their place whatever order the records are stored in (CaveKit does the same).
         */
        fun nameOrder(a: Wine, b: Wine, collator: Collator = collator()): Int {
            val order = collator.compare(a.name, b.name)
            if (order != 0) return order
            val vintages = (a.vintage ?: 0).compareTo(b.vintage ?: 0)
            if (vintages != 0) return vintages
            val producers = collator.compare(a.producer.orEmpty(), b.producer.orEmpty())
            if (producers != 0) return producers
            return a.id.toString().uppercase(Locale.ROOT).compareTo(b.id.toString().uppercase(Locale.ROOT))
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
    /** Only wines with this tag (ignoring case). */
    val tag: String? = null,
    /** Only bottles reserved for this occasion (ignoring case). */
    val reservation: String? = null,
    /** Only wines made with this grape (ignoring case). */
    val grape: String? = null,
) {
    val isActive: Boolean
        get() = colors.isNotEmpty() || maturities.isNotEmpty() || rackId != null || tag != null || reservation != null || grape != null
}

enum class CellarError {
    UnknownWine, UnknownBottle, UnknownRack, UnknownDiscovery, SlotTaken, SlotOutOfRange, AlreadyConsumed, RackNotEmpty,

    /** The bottle is on order: receive it before placing or drinking it. */
    NotReceived,
}

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
    /** The bottles are bought but not delivered yet. */
    val isOrdered: Boolean = false,
    /** When ordered bottles should arrive, if known. */
    val expectedAt: Instant? = null,
)
