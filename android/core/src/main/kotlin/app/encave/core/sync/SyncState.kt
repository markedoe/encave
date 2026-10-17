package app.encave.core.sync

import app.encave.core.Cellar
import app.encave.core.Rack
import app.encave.core.UuidSerializer
import java.util.Locale
import java.util.UUID
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
    val deleted: Boolean = false,
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
    val version: Int = FORMAT_VERSION,
    /** This device. */
    @Serializable(with = UuidSerializer::class) val device: UUID = UUID.randomUUID(),
    /** The highest counter seen; the next local change uses `clock + 1`. */
    val clock: Long = 0,
    /** Last change of each object, by key ("wine:…", "settings", "rack-order"). Deleted objects keep a stamp. */
    val stamps: Map<String, Stamp> = emptyMap(),
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
        compare(SyncKey.Kind.Rack, old.racks.associateBy { it.id }, new.racks.associateBy { it.id })
        if (old.settings != new.settings) stamp(SyncKey.SETTINGS, false)
        if (old.racks.map { it.id } != new.racks.map { it.id }) stamp(SyncKey.RACK_ORDER, false)
        return copy(clock = clock, stamps = stamps)
    }

    /** The stamp of an object, or [Stamp.ZERO] if it exists but was never stamped. */
    internal fun stampFor(key: String, exists: Boolean): Stamp? = stamps[key] ?: if (exists) Stamp.ZERO else null

    companion object {
        const val FORMAT_VERSION = 1

        /** Sync turned on for a cellar that already has data: every object gets a first stamp. */
        fun starting(cellar: Cellar, device: UUID = UUID.randomUUID()): SyncState =
            SyncState(device = device).recording(Cellar(), cellar)
    }
}

/** Identifies an object in the stamps. UUIDs are lowercase. */
object SyncKey {
    enum class Kind(val raw: String) { Wine("wine"), Bottle("bottle"), Rack("rack"), Discovery("discovery") }

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

    /** Merges a remote copy into a local one. The local device stays the device. */
    fun merge(local: Cellar, localState: SyncState, remote: Cellar, remoteState: SyncState): Result {
        val stamps = mutableMapOf<String, Stamp>()

        fun <T : Any> pick(kind: SyncKey.Kind, a: Map<UUID, T>, b: Map<UUID, T>, encode: (T) -> ByteArray): Map<UUID, T> {
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
                val (winner, stamp) = choose(a[id], sa, b[id], sb, encode)
                if (stamp != null) stamps[key] = stamp
                if (winner != null) result[id] = winner
            }
            return result
        }

        val wines = pick(SyncKey.Kind.Wine, local.wines, remote.wines, SwiftJson::wine)
        val bottles = pick(SyncKey.Kind.Bottle, local.bottles, remote.bottles, SwiftJson::bottle).toMutableMap()
        val discoveries = pick(SyncKey.Kind.Discovery, local.discoveries, remote.discoveries, SwiftJson::discovery)
        val racks = pick(SyncKey.Kind.Rack, local.racks.associateBy { it.id }, remote.racks.associateBy { it.id }, SwiftJson::rack)

        // Settings: one object.
        val (chosenSettings, settingsStamp) = choose(
            local.settings, localState.stamps[SyncKey.SETTINGS] ?: Stamp.ZERO,
            remote.settings, remoteState.stamps[SyncKey.SETTINGS] ?: Stamp.ZERO, SwiftJson::settings,
        )
        val settings = chosenSettings ?: local.settings
        if (settingsStamp != null) stamps[SyncKey.SETTINGS] = settingsStamp

        // Rack order: the order of the copy that reordered last, then any other racks.
        val orderA = localState.stamps[SyncKey.RACK_ORDER] ?: Stamp.ZERO
        val orderB = remoteState.stamps[SyncKey.RACK_ORDER] ?: Stamp.ZERO
        val order = if (orderB > orderA) remote.racks.map { it.id } + local.racks.map { it.id }
        else local.racks.map { it.id } + remote.racks.map { it.id }
        if (orderA != Stamp.ZERO || orderB != Stamp.ZERO) stamps[SyncKey.RACK_ORDER] = maxOf(orderA, orderB)
        val seen = HashSet<UUID>()
        val ordered = mutableListOf<Rack>()
        for (id in order) if (seen.add(id)) racks[id]?.let { ordered += it }
        // Racks in neither order (should not happen): by ID.
        for (rack in racks.values.sortedBy { it.id.upper }) if (rack.id !in seen) ordered += rack

        val merged = repair(wines, bottles, ordered, settings, discoveries, stamps)
        return Result(merged, stamps, maxOf(localState.clock, remoteState.clock))
    }

    /**
     * The version that changed last, and its stamp. Equal stamps with different
     * content (two devices that never synced, same object) fall back to the content.
     */
    private fun <T : Any> choose(a: T?, sa: Stamp?, b: T?, sb: Stamp?, encode: (T) -> ByteArray): Pair<T?, Stamp?> = when {
        sa == null && sb == null -> null to null
        sb == null -> (if (sa!!.deleted) null else a ?: b) to sa
        sa == null -> (if (sb.deleted) null else b ?: a) to sb
        sa == sb -> (if (sa.deleted) null else preferred(a, b, encode)) to sa
        else -> {
            val (winner, other, stamp) = if (sa > sb) Triple(a, b, sa) else Triple(b, a, sb)
            (if (stamp.deleted) null else winner ?: other) to stamp
        }
    }

    /**
     * A choice between two versions that does not depend on which device merges: the
     * one whose sorted-keys JSON is lexicographically greater, as the Swift code does.
     */
    private fun <T : Any> preferred(a: T?, b: T?, encode: (T) -> ByteArray): T? {
        if (a == null || b == null) return a ?: b
        if (a == b) return a
        return if (java.util.Arrays.compareUnsigned(encode(a), encode(b)) < 0) b else a
    }

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
        val byId = racks.associateBy { it.id }
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
                if (sx != sy) sy.compareTo(sx) else x.id.upper.compareTo(y.id.upper)
            }
            for (loser in ranked.drop(1)) bottles[loser.id] = loser.copy(slot = null)
        }
        val used = bottles.values.map { it.wineId }.toSet() + keptDiscoveries.values.map { it.wineId }
        return Cellar(wines.values.filter { it.id in used }, bottles.values, racks, settings, keptDiscoveries.values)
    }
}
