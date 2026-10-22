package app.encave.core.sync

import app.encave.core.Cellar
import app.encave.core.Rack
import app.encave.core.SavedSearch
import app.encave.core.UuidSerializer
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

// Sync metadata and the merge, ported from CaveKit's SyncState.swift (docs/SYNC.md §1).

/** Foundation's `uuidString`: uppercase. Stamps are ordered by it. */
internal val UUID.upper: String get() = toString().uppercase(Locale.ROOT)

/** Lowercase, as in object keys, paths and transcripts. */
internal val UUID.lower: String get() = toString().lowercase(Locale.ROOT)

/**
 * When an object last changed, as a Lamport time: a counter that every device
 * advances past anything it has seen, plus the device that made the change.
 * Comparing stamps orders changes the same way on every device.
 */
@Serializable
data class Stamp(
    /** UInt64 on the wire; never near 2^63 in practice. */
    val counter: Long,
    @Serializable(with = UuidSerializer::class) val device: UUID,
    /** The object was deleted at this time. */
    @Required val deleted: Boolean = false,
) : Comparable<Stamp> {
    override fun compareTo(other: Stamp): Int {
        if (counter != other.counter) return java.lang.Long.compareUnsigned(counter, other.counter)
        if (device != other.device) return device.upper.compareTo(other.device.upper)
        // Same change seen twice: a deletion wins over the version it deleted.
        return deleted.compareTo(other.deleted)
    }

    companion object {
        /** For objects that existed before sync was set up. */
        val ZERO = Stamp(0, UUID(0, 0))
    }
}

/**
 * What this device knows about changes, kept next to the cellar (not inside it,
 * so the cellar file stays the same for backups).
 */
@Serializable
data class SyncState(
    @Required val version: Int = FORMAT_VERSION,
    /** This device. */
    @Required @Serializable(with = UuidSerializer::class) val device: UUID = UUID.randomUUID(),
    /** The highest counter seen; the next local change uses `clock + 1`. */
    @Required val clock: Long = 0,
    /** Last change of each object, by key ("wine:…", "settings", "rack-order"). Deleted objects keep a stamp. */
    @Required val stamps: Map<String, Stamp> = emptyMap(),
) {
    /** Stamps every object that differs between two versions of the cellar. Called after each local change. */
    fun recording(old: Cellar, new: Cellar): SyncState {
        var clock = clock
        val stamps = stamps.toMutableMap()
        fun stamp(key: String, deleted: Boolean) {
            clock += 1
            stamps[key] = Stamp(clock, device, deleted)
        }
        fun <T> compare(kind: SyncKey.Kind, before: Map<UUID, T>, after: Map<UUID, T>) {
            for ((id, value) in after) if (before[id] != value) stamp(SyncKey.of(kind, id), false)
            for (id in before.keys) if (id !in after) stamp(SyncKey.of(kind, id), true)
        }
        compare(SyncKey.Kind.Wine, old.wines, new.wines)
        compare(SyncKey.Kind.Bottle, old.bottles, new.bottles)
        compare(SyncKey.Kind.Discovery, old.discoveries, new.discoveries)
        compare(SyncKey.Kind.Rack, CellarMerge.byId(old.racks), CellarMerge.byId(new.racks))
        compare(SyncKey.Kind.Search, CellarMerge.searchesById(old.settings.savedSearches), CellarMerge.searchesById(new.settings.savedSearches))
        if (old.settings.copy(savedSearches = null) != new.settings.copy(savedSearches = null)) stamp(SyncKey.SETTINGS, false)
        if (old.racks.map { it.id } != new.racks.map { it.id }) stamp(SyncKey.RACK_ORDER, false)
        return copy(clock = clock, stamps = stamps)
    }

    /** The stamp of an object, or [Stamp.ZERO] if it exists but was never stamped. */
    internal fun stampFor(key: String, exists: Boolean): Stamp? = stamps[key] ?: if (exists) Stamp.ZERO else null

    companion object {
        const val FORMAT_VERSION = 1

        /**
         * The largest counter a sender may use (2^52): far below the overflow of a UInt64,
         * and half of what a double holds exactly (2^53), so a device that accepted it still
         * has room for as many changes and its counters stay exact in JSON readers that use doubles.
         */
        const val MAX_COUNTER: Long = 1L shl 52

        /**
         * Refuses a state from another device whose counters are out of bounds (SYNC.md §1,
         * step 0): a clock over [MAX_COUNTER], a stamp over the sender's own clock, or a
         * membership counter over [MAX_COUNTER].
         */
        fun check(state: SyncState, membership: Membership? = null) {
            val ok = state.clock in 0..MAX_COUNTER &&
                state.stamps.values.all { it.counter in 0..state.clock } &&
                membership?.stamps?.values.orEmpty().all { it.counter in 0..MAX_COUNTER }
            if (!ok) throw SyncException(SyncError.ProtocolViolation("clock out of bounds"))
        }

        /** Sync turned on for a cellar that already has data: every object gets a first stamp. */
        fun starting(cellar: Cellar, device: UUID = UUID.randomUUID()): SyncState =
            SyncState(device = device).recording(Cellar(), cellar)
    }
}

/** Identifies an object in the stamps. UUIDs are lowercase. */
object SyncKey {
    enum class Kind(val raw: String) { Wine("wine"), Bottle("bottle"), Rack("rack"), Discovery("discovery"), Search("search") }

    const val SETTINGS = "settings"
    const val RACK_ORDER = "rack-order"

    fun of(kind: Kind, id: UUID): String = "${kind.raw}:${id.lower}"
}

/**
 * Merging two copies of a cellar: each object is taken from the copy where it
 * changed last. The result is the same whichever device runs the merge.
 */
object CellarMerge {
    data class Result(val cellar: Cellar, val stamps: Map<String, Stamp>, val clock: Long)

    /** Racks by ID, the first of each ID. */
    internal fun byId(racks: List<Rack>): Map<UUID, Rack> = LinkedHashMap<UUID, Rack>().apply { racks.forEach { putIfAbsent(it.id, it) } }
    internal fun searchesById(searches: List<SavedSearch>?): Map<UUID, SavedSearch> =
        LinkedHashMap<UUID, SavedSearch>().apply { searches.orEmpty().forEach { putIfAbsent(it.id, it) } }

    /** Merges a remote copy into a local one. The local device stays the device. */
    fun merge(local: Cellar, localState: SyncState, remote: Cellar, remoteState: SyncState): Result {
        val stamps = mutableMapOf<String, Stamp>()

        fun <T : Any> pick(kind: SyncKey.Kind, a: Map<UUID, T>, b: Map<UUID, T>, prefer: (T, T) -> T): MutableMap<UUID, T> {
            val result = LinkedHashMap<UUID, T>()
            val ids = LinkedHashSet<UUID>(a.keys + b.keys)
            val prefix = kind.raw + ":"
            for (key in localState.stamps.keys + remoteState.stamps.keys) {
                if (key.startsWith(prefix)) runCatching { UUID.fromString(key.removePrefix(prefix)) }.getOrNull()?.let { ids += it }
            }
            for (id in ids) {
                val key = SyncKey.of(kind, id)
                val sa = localState.stampFor(key, a[id] != null)
                val sb = remoteState.stampFor(key, b[id] != null)
                val (winner, stamp) = choose(a[id], sa, b[id], sb, prefer)
                if (stamp != null) stamps[key] = stamp
                if (winner != null) result[id] = winner
            }
            return result
        }

        val keepLocal: (Any, Any) -> Any = { first, _ -> first }
        @Suppress("UNCHECKED_CAST")
        fun <T> local() = keepLocal as (T, T) -> T

        val wines = pick(SyncKey.Kind.Wine, local.wines, remote.wines, local())
        val bottles = pick(SyncKey.Kind.Bottle, local.bottles, remote.bottles, ::preferBottle)
        val discoveries = pick(SyncKey.Kind.Discovery, local.discoveries, remote.discoveries, local())
        val racks = pick(SyncKey.Kind.Rack, byId(local.racks), byId(remote.racks), local())
        revive(wines, bottles, discoveries, stamps, local, remote)

        // Settings: one object.
        val (chosenSettings, settingsStamp) = choose(
            local.settings, localState.stamps[SyncKey.SETTINGS] ?: Stamp.ZERO,
            remote.settings, remoteState.stamps[SyncKey.SETTINGS] ?: Stamp.ZERO, local(),
        )
        if (settingsStamp != null) stamps[SyncKey.SETTINGS] = settingsStamp
        // Saved searches are merged one by one, so searches saved on two devices are all kept.
        val searches = pick(
            SyncKey.Kind.Search, searchesById(local.settings.savedSearches), searchesById(remote.settings.savedSearches), local(),
        )
        val settings = (chosenSettings ?: local.settings)
            .copy(savedSearches = SavedSearch.ordered(searches.values.toList()).ifEmpty { null })

        // Rack order: the order of the copy that reordered last (on equal stamps, the list
        // that is smaller compared as lowercase IDs), then the other racks by lowercase ID.
        val orderA = localState.stamps[SyncKey.RACK_ORDER] ?: Stamp.ZERO
        val orderB = remoteState.stamps[SyncKey.RACK_ORDER] ?: Stamp.ZERO
        val idsA = local.racks.map { it.id.lower }
        val idsB = remote.racks.map { it.id.lower }
        val remoteFirst = if (orderA == orderB) lexicographicallyPrecedes(idsB, idsA) else orderB > orderA
        val base = if (remoteFirst) remote.racks else local.racks
        if (orderA != Stamp.ZERO || orderB != Stamp.ZERO) stamps[SyncKey.RACK_ORDER] = maxOf(orderA, orderB)
        val seen = HashSet<UUID>()
        val ordered = mutableListOf<Rack>()
        for (rack in base) if (seen.add(rack.id)) racks[rack.id]?.let { ordered += it }
        for (rack in racks.values.sortedBy { it.id.lower }) if (rack.id !in seen) ordered += rack

        val merged = repair(wines, bottles, ordered, settings, discoveries, stamps)
        // At least every counter seen, so the next local change is newer than all of them.
        val clock = (listOf(localState.clock, remoteState.clock) + stamps.values.map { it.counter }).max()
        return Result(merged, stamps, clock)
    }

    /** Swift's `lexicographicallyPrecedes` on arrays of strings. */
    private fun lexicographicallyPrecedes(a: List<String>, b: List<String>): Boolean {
        for (index in 0 until minOf(a.size, b.size)) {
            val order = a[index].compareTo(b[index])
            if (order != 0) return order < 0
        }
        return a.size < b.size
    }

    /**
     * A bottle or discovery changed after its wine was deleted on another device (someone
     * added a bottle while someone else deleted the wine): the wine comes back, with the
     * newest stamp of what refers to it, so nothing added is lost.
     */
    private fun revive(
        wines: MutableMap<UUID, app.encave.core.Wine>,
        bottles: Map<UUID, app.encave.core.Bottle>,
        discoveries: Map<UUID, app.encave.core.Discovery>,
        stamps: MutableMap<String, Stamp>,
        local: Cellar,
        remote: Cellar,
    ) {
        val newest = HashMap<UUID, Stamp>()
        val references = bottles.values.map { SyncKey.of(SyncKey.Kind.Bottle, it.id) to it.wineId } +
            discoveries.values.map { SyncKey.of(SyncKey.Kind.Discovery, it.id) to it.wineId }
        for ((key, wineId) in references) {
            if (wines[wineId] != null) continue
            val stamp = stamps[key] ?: continue
            val deletion = stamps[SyncKey.of(SyncKey.Kind.Wine, wineId)] ?: continue
            if (!deletion.deleted || stamp <= deletion) continue
            newest[wineId] = newest[wineId]?.let { maxOf(it, stamp) } ?: stamp
        }
        for ((wineId, stamp) in newest) {
            val wine = local.wines[wineId] ?: remote.wines[wineId] ?: continue
            wines[wineId] = wine
            stamps[SyncKey.of(SyncKey.Kind.Wine, wineId)] = Stamp(stamp.counter, stamp.device)
        }
    }

    /**
     * The version that changed last, and its stamp. If the winning side lacks the object
     * (repair removed it without a stamp), the other side's is kept under the winning stamp.
     */
    private fun <T : Any> choose(a: T?, sa: Stamp?, b: T?, sb: Stamp?, prefer: (T, T) -> T): Pair<T?, Stamp?> = when {
        sa == null && sb == null -> null to null
        sb == null -> (if (sa!!.deleted) null else a ?: b) to sa
        sa == null -> (if (sb.deleted) null else b ?: a) to sb
        sa == sb -> (if (sa.deleted) null else if (a != null && b != null) prefer(a, b) else a ?: b) to sa
        else -> {
            val (winner, other, stamp) = if (sa > sb) Triple(a, b, sa) else Triple(b, a, sb)
            (if (stamp.deleted) null else winner ?: other) to stamp
        }
    }

    /**
     * Two versions of a bottle with the same stamp come from the same change and differ
     * only where repair changed one without a stamp: the version without a place wins.
     * Otherwise the local version is kept.
     */
    private fun preferBottle(a: app.encave.core.Bottle, b: app.encave.core.Bottle): app.encave.core.Bottle =
        if ((a.slot == null) != (b.slot == null)) (if (a.slot == null) a else b) else a

    /**
     * Fixes what a merge can leave inconsistent, the same way on every device:
     * bottles of a deleted wine, places in a removed or smaller rack, and two
     * bottles in one place (the one moved there last keeps it).
     */
    private fun repair(
        wines: Map<UUID, app.encave.core.Wine>,
        bottles: MutableMap<UUID, app.encave.core.Bottle>,
        racks: List<Rack>,
        settings: app.encave.core.CellarSettings,
        discoveries: Map<UUID, app.encave.core.Discovery>,
        stamps: Map<String, Stamp>,
    ): Cellar {
        bottles.entries.removeAll { wines[it.value.wineId] == null }
        val keptDiscoveries = discoveries.filterValues { wines[it.wineId] != null }
        val byId = byId(racks)
        for ((id, bottle) in bottles.toList()) {
            val slot = bottle.slot ?: continue
            if (bottle.status != app.encave.core.Bottle.Status.InCellar || byId[slot.rackId]?.contains(slot) != true) {
                bottles[id] = bottle.copy(slot = null)
            }
        }
        val placed = bottles.values.filter { it.slot != null }.groupBy { it.slot!! }
        for (together in placed.values) {
            if (together.size < 2) continue
            val ranked = together.sortedWith { x, y ->
                val sx = stamps[SyncKey.of(SyncKey.Kind.Bottle, x.id)] ?: Stamp.ZERO
                val sy = stamps[SyncKey.of(SyncKey.Kind.Bottle, y.id)] ?: Stamp.ZERO
                if (sx != sy) sy.compareTo(sx) else x.id.lower.compareTo(y.id.lower)
            }
            for (loser in ranked.drop(1)) bottles[loser.id] = loser.copy(slot = null)
        }
        val used = bottles.values.map { it.wineId }.toSet() + keptDiscoveries.values.map { it.wineId }
        return Cellar(wines.values.filter { it.id in used }, bottles.values, racks, settings, keptDiscoveries.values)
    }
}

/**
 * What a device that joined a cellar with Replace sends until the other device's cellar has
 * arrived: only the objects changed on it since it joined (those with a stamp, the stamps
 * having been reset when it joined), with the wines and racks they use, and the settings. Its
 * own bottles are never sent, and are replaced only once the other cellar is taken in
 * (docs/SYNC.md §5). Mirrors CaveKit's `CellarMerge.onlyStamped`.
 */
fun CellarMerge.onlyStamped(cellar: Cellar, state: SyncState): Cellar {
    fun stamped(kind: SyncKey.Kind, id: UUID) = state.stamps.containsKey(SyncKey.of(kind, id))
    val bottles = cellar.bottles.values.filter { stamped(SyncKey.Kind.Bottle, it.id) }
    val discoveries = cellar.discoveries.values.filter { stamped(SyncKey.Kind.Discovery, it.id) }
    val usedWines = bottles.mapTo(HashSet()) { it.wineId } + discoveries.map { it.wineId }
    val wines = cellar.wines.values.filter { stamped(SyncKey.Kind.Wine, it.id) || it.id in usedWines }
    val usedRacks = bottles.mapNotNullTo(HashSet()) { it.slot?.rackId }
    val racks = cellar.racks.filter { stamped(SyncKey.Kind.Rack, it.id) || it.id in usedRacks }
    return Cellar(wines, bottles, racks, cellar.settings, discoveries).also { it.schemaVersion = cellar.schemaVersion }
}
