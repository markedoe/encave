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
            WineGroup(wine, bottles.sortedWith(bottleExpectedOrder))
        }.sortedWith { a, b ->
            val da = a.bottles.first().expectedAt ?: far
            val db = b.bottles.first().expectedAt ?: far
            if (da != db) da.compareTo(db) else nameOrder(a.wine, b.wine, collator)
        }
    }

    /**
     * Occasions bottles in the cellar are reserved for, counted case-insensitively, by name
     * ([chipOrder]), each with its most used spelling, as in CaveKit.
     */
    val reservations: List<String>
        get() = spelled(bottlesInCellar.mapNotNull { it.reservedFor }).map { it.first }.sortedWith(chipOrder)

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
            WineGroup(wine, kept.sortedWith(bottleAddedOrder))
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
    fun bottlesOf(wineId: UUID): List<Bottle> = bottlesInCellar.filter { it.wineId == wineId }.sortedWith(bottleAddedOrder)

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
        return (0 until rack.positions(cell)).firstOrNull { it !in taken }?.let { Slot(cell.rackId, cell.row, cell.column, it) }
    }

    fun occupancy(rack: Rack): Int = bottlesInCellar.count { it.slot?.rackId == rack.id }

    /**
     * The positions a count of this rack shows: its capacity, plus each place behind a row's
     * depth holding a bottle an older version put there, so a count never reads more bottles
     * than positions. Mirrors CaveKit.
     */
    fun capacity(rack: Rack): Int =
        rack.capacity + bottlesInCellar.count { b -> b.slot?.let { it.rackId == rack.id && rack.contains(it) && !rack.hasPosition(it) } ?: false }

    fun rack(id: UUID): Rack? = rackList.firstOrNull { it.id == id }

    /** "Main rack · B3", "Cabinet · Shelf 2, position 3", the storage note, or null when the bottle has no location. */
    fun locationLabel(bottle: Bottle): String? {
        val slot = bottle.slot
        val rack = slot?.let { rack(it.rackId) }
        if (slot != null && rack != null) return "${rack.name} · ${rack.placeName(slot)}"
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

    /**
     * Puts back a saved search deleted by mistake, in its place in the list (the Undo of
     * [deleteSearch]). Nothing changes when it, or another with its name, is there again.
     */
    fun restoreSearch(search: SavedSearch) {
        val list = settings.savedSearches.orEmpty()
        if (list.any { it.id == search.id || it.name.equals(search.name, ignoreCase = true) }) return
        settings = settings.copy(savedSearches = SavedSearch.ordered(list + search))
    }

    /** Reserves bottles in the cellar for an occasion, or frees them (null or blank). */
    fun reserve(bottleIds: List<UUID>, occasion: String?) {
        val name = occasion?.trim()?.ifEmpty { null }
        if (bottleIds.any { bottleMap[it]?.status != Bottle.Status.InCellar }) throw CellarException(CellarError.UnknownBottle)
        bottleIds.forEach { id -> bottleMap[id] = bottleMap.getValue(id).copy(reservedFor = name) }
    }

    /**
     * Gives the bottles of a wine still in the cellar that are of size [old] the size [new] (a
     * price found for 70 cl when they were saved as 75 cl). Returns the bottles changed. As in CaveKit.
     */
    fun changeSize(wineId: UUID, old: Int, new: Int): List<UUID> {
        if (old == new || new <= 0) return emptyList()
        val changed = bottlesOf(wineId).filter { it.sizeML == old }
        changed.forEach { bottleMap[it.id] = it.copy(sizeML = new) }
        return changed.map { it.id }
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
        bottleMap[bottleId] = bottle.copy(tasting = tasting.normalized().let { it.copy(unknownKeys = it.unknownKeys.orKept(bottle.tasting?.unknownKeys)) })
    }

    /**
     * Reverts a drink, putting the bottle back in its previous slot if still free. Only a
     * bottle opened can be: one on order or given away stays as it is.
     */
    fun undoDrink(bottleId: UUID, previousSlot: Slot?) {
        val bottle = bottleMap[bottleId] ?: return
        if (bottle.status != Bottle.Status.Consumed) return
        // Back in its place if it is still free and still exists (the rack may be gone or smaller).
        val free = previousSlot != null && bottleAt(previousSlot) == null && rack(previousSlot.rackId)?.contains(previousSlot) == true
        bottleMap[bottleId] = bottle.copy(status = Bottle.Status.InCellar, tasting = null, slot = if (free) previousSlot else null)
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
        val stored = bottleMap[bottle.id] ?: throw CellarException(CellarError.UnknownBottle)
        bottle.slot?.let { slot ->
            if (bottle.status == Bottle.Status.Ordered) throw CellarException(CellarError.NotReceived)
            validateInRange(slot)
            val occupant = bottleAt(slot)
            if (occupant != null && occupant.id != bottle.id) throw CellarException(CellarError.SlotTaken)
        }
        bottleMap[bottle.id] = bottle.normalized().copy(
            tasting = bottle.tasting?.normalized()?.let { it.copy(unknownKeys = it.unknownKeys.orKept(stored.tasting?.unknownKeys)) },
            unknownKeys = bottle.unknownKeys.orKept(stored.unknownKeys),
        )
    }

    fun move(bottleId: UUID, slot: Slot?) {
        val bottle = bottleMap[bottleId] ?: throw CellarException(CellarError.UnknownBottle)
        if (slot != null) {
            // A bottle on order has no place until it is received, as in Swift; one opened or
            // gone meanwhile (by sync) never takes one.
            if (bottle.status == Bottle.Status.Ordered) throw CellarException(CellarError.NotReceived)
            if (bottle.status != Bottle.Status.InCellar) throw CellarException(CellarError.NotInCellar)
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

    /**
     * The Undo of [swap] (a, b) that found [first] at a and [second] at b: swaps them back only
     * if both are still where the swap put them, so a bottle put in one of the places since
     * never moves. Returns whether it swapped them back.
     */
    fun undoSwap(a: Slot, b: Slot, first: UUID?, second: UUID?): Boolean {
        if (first == null && second == null) return false
        if (bottleAt(b)?.id != first || bottleAt(a)?.id != second) return false
        return runCatching { swap(a, b) }.isSuccess
    }

    /**
     * The Undo of [updateRack] with takingOut on the rack [old]: the rack takes its old shape
     * back. The bottles in it now go back with their rows (the inverse of [rowMap]), so one
     * placed since stays on its shelf; a bottle in a row the old shape did not have loses its
     * place. The bottles taken out ([takenOut], as they were before) go back to their place only
     * if they still have none and it is free. Bottles moved elsewhere or opened since keep what
     * they have now.
     */
    fun undoRackUpdate(old: Rack, rowMap: Map<Int, Int?>, takenOut: List<Bottle>, cellMap: Map<RackCell, RackCell?> = emptyMap()) {
        val index = rackList.indexOfFirst { it.id == old.id }
        if (index < 0) return
        val restored = old.normalized().let { it.copy(unknownKeys = it.unknownKeys.orKept(rackList[index].unknownKeys)) }
        // A row the edit moved away from and nothing moved into is a new row: no old place.
        val inverse = HashMap<Int, Int?>()
        rowMap.keys.forEach { inverse[it] = null }
        rowMap.forEach { (from, to) -> if (to != null) inverse[to] = from }
        // The same for cells (a diamond rack whose triangles changed).
        val inverseCells = HashMap<RackCell, RackCell?>()
        cellMap.keys.forEach { inverseCells[it] = null }
        cellMap.forEach { (from, to) -> if (to != null) inverseCells[to] = from }
        bottlesInCellar.forEach { bottle ->
            val slot = bottle.slot ?: return@forEach
            if (slot.rackId != restored.id) return@forEach
            bottleMap[bottle.id] = bottle.copy(slot = Rack.moved(slot, inverse, inverseCells)?.takeIf { restored.contains(it) })
        }
        rackList[index] = restored
        takenOut.forEach { bottle ->
            val slot = bottle.slot ?: return@forEach
            val now = bottleMap[bottle.id] ?: return@forEach
            if (now.status == Bottle.Status.InCellar && now.slot == null && bottleAt(slot) == null && restored.contains(slot)) {
                bottleMap[bottle.id] = now.copy(slot = slot)
            }
        }
    }

    /**
     * Bottles of a rack not found while checking it: those still in it whose ID is not in
     * [found], so a bottle seen and then moved within the rack stays found.
     */
    fun missingBottles(rackId: UUID, found: Set<UUID>): List<Bottle> =
        bottlesInCellar.filter { it.slot?.rackId == rackId && it.id !in found }

    /** Whether [reserve] with these bottles and [occasion] would change anything. */
    fun changesReservation(bottleIds: List<UUID>, occasion: String?): Boolean {
        val name = occasion?.trim()?.ifEmpty { null }
        return bottleIds.any { id -> bottleMap[id]?.let { it.reservedFor != name } ?: false }
    }

    fun updateWine(wine: Wine) {
        val stored = wineMap[wine.id] ?: throw CellarException(CellarError.UnknownWine)
        wineMap[wine.id] = wine.normalized().let { it.copy(unknownKeys = it.unknownKeys.orKept(stored.unknownKeys)) }
    }

    /**
     * The same wine entered twice: the bottles and shopping-list entries of [source] go to
     * [target], with its barcodes and documents, and its label photo when [target] has none;
     * then [source] is removed. As in CaveKit.
     */
    fun mergeWine(source: UUID, target: UUID) {
        val from = wineMap[source]
        val into = wineMap[target]
        if (source == target || from == null || into == null) throw CellarException(CellarError.UnknownWine)
        bottleMap.replaceAll { _, bottle -> if (bottle.wineId == source) bottle.copy(wineId = target) else bottle }
        discoveryMap.replaceAll { _, discovery -> if (discovery.wineId == source) discovery.copy(wineId = target) else discovery }
        val merged = from.barcodes.orEmpty().fold(into) { wine, code -> wine.addingBarcode(code) }
        // Critics' scores each once, and the details the target lacks, as in CaveKit.
        val critics = merged.criticScores.orEmpty()
        wineMap[target] = merged.copy(
            attachments = if (from.attachments == null) merged.attachments else merged.attachments.orEmpty() + from.attachments,
            photoId = merged.photoId ?: from.photoId,
            criticScores = (critics + from.criticScores.orEmpty().filter { it !in critics }).ifEmpty { null },
            abv = merged.abv ?: from.abv, classification = merged.classification ?: from.classification,
            lieuDit = merged.lieuDit ?: from.lieuDit, dosage = merged.dosage ?: from.dosage,
            sweetness = merged.sweetness ?: from.sweetness, grapeShares = merged.grapeShares ?: from.grapeShares,
            ageing = merged.ageing ?: from.ageing, farming = merged.farming ?: from.farming,
            farmingCertified = if (merged.farming == null) from.farmingCertified else merged.farmingCertified,
            closure = merged.closure ?: from.closure,
        ).normalized()
        wineMap.remove(source)
    }

    /**
     * Undoes [mergeWine]: [source] and [target] as they were before, and the bottles and
     * discoveries that moved ([bottleIds], [discoveryIds]) back to [source].
     */
    fun unmergeWine(source: Wine, target: Wine, bottleIds: Set<UUID>, discoveryIds: Set<UUID>) {
        wineMap[source.id] = source.normalized()
        wineMap[target.id] = target.normalized()
        bottleIds.forEach { id -> bottleMap[id]?.let { bottleMap[id] = it.copy(wineId = source.id) } }
        discoveryIds.forEach { id -> discoveryMap[id]?.let { discoveryMap[id] = it.copy(wineId = source.id) } }
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
     * [cellMap] moves the bottles of a cell to another cell (a diamond rack whose triangles
     * changed, [Rack.diamondCellMap]), before [rowMap]. A bottle whose new place is not in the
     * rack is displaced: with [takingOut] it stays in the cellar without a place, otherwise the
     * update is refused.
     */
    fun updateRack(
        rack: Rack,
        rowMap: Map<Int, Int?> = emptyMap(),
        takingOut: Boolean = false,
        cellMap: Map<RackCell, RackCell?> = emptyMap(),
    ) {
        val index = rackList.indexOfFirst { it.id == rack.id }
        if (index < 0) throw CellarException(CellarError.UnknownRack)
        val updated = rack.normalized().let { it.copy(unknownKeys = it.unknownKeys.orKept(rackList[index].unknownKeys)) }
        val displaced = displacedBottles(updated, rowMap, cellMap).map { it.id }.toSet()
        if (displaced.isNotEmpty() && !takingOut) throw CellarException(CellarError.RackNotEmpty)
        bottlesInCellar.forEach { bottle ->
            val slot = bottle.slot ?: return@forEach
            if (slot.rackId != updated.id) return@forEach
            bottleMap[bottle.id] = bottle.copy(slot = if (bottle.id in displaced) null else Rack.moved(slot, rowMap, cellMap))
        }
        rackList[index] = updated
    }

    /** Bottles of this rack that an update with [rowMap] would leave without a place, in the order of their places. */
    fun displacedBottles(rack: Rack, rowMap: Map<Int, Int?> = emptyMap(), cellMap: Map<RackCell, RackCell?> = emptyMap()): List<Bottle> {
        val updated = rack.normalized()
        val old = this.rack(updated.id)
        return bottlesInCellar.filter { bottle ->
            val slot = bottle.slot
            if (slot == null || slot.rackId != updated.id) return@filter false
            val moved = Rack.moved(slot, rowMap, cellMap) ?: return@filter true
            if (!updated.contains(moved)) return@filter true
            // A position the edit removes; a bottle already behind its row's depth (put there by
            // an older version) stays where it is while the edit keeps that place.
            !updated.hasPosition(moved) && old?.hasPosition(slot) != false
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
         * Names counted case-insensitively, the most used first, equal counts in [chipOrder].
         * Each is shown with its most used spelling (ties: the first in UTF-16 order), so the
         * label does not depend on the order records are stored in, and is the same on every platform.
         */
        internal fun byUse(names: List<String>): List<String> =
            spelled(names).sortedWith { a, b -> if (a.second != b.second) b.second - a.second else chipOrder.compare(a.first, b.first) }
                .map { it.first }

        /** Names counted case-insensitively, each with its most used spelling (ties: the first in UTF-16 order). */
        private fun spelled(names: List<String>): List<Pair<String, Int>> {
            val spellings = HashMap<String, HashMap<String, Int>>()
            for (name in names) spellings.getOrPut(name.lowercase(java.util.Locale.ROOT)) { HashMap() }.merge(name, 1, Int::plus)
            return spellings.values.map { counts ->
                val shown = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).first().key
                shown to counts.values.sum()
            }
        }

        /**
         * The order of tags, grapes and occasions, the same in every locale and on Apple
         * (`Cellar.chipOrder`): ignoring case and accents, with runs of digits compared by
         * value ("Lot 2" before "Lot 10"), then by UTF-16 code units.
         */
        internal val chipOrder: Comparator<String> = Comparator { a, b ->
            val order = naturalOrder(Wine.fold(a), Wine.fold(b))
            if (order != 0) order else a.compareTo(b)
        }

        private fun naturalOrder(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                if (a[i] in '0'..'9' && b[j] in '0'..'9') {
                    var endA = i
                    while (endA < a.length && a[endA] in '0'..'9') endA++
                    var endB = j
                    while (endB < b.length && b[endB] in '0'..'9') endB++
                    val runA = a.substring(i, endA).trimStart('0')
                    val runB = b.substring(j, endB).trimStart('0')
                    if (runA.length != runB.length) return runA.length.compareTo(runB.length)
                    val digits = runA.compareTo(runB)
                    if (digits != 0) return digits
                    i = endA
                    j = endB
                } else {
                    if (a[i] != b[j]) return a[i].compareTo(b[j])
                    i++
                    j++
                }
            }
            return (a.length - i).compareTo(b.length - j)
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

    /** The bottle was opened or is gone: it has no place any more. */
    NotInCellar,
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
