package app.encave.core.sync

import app.encave.core.Bottle
import app.encave.core.Cellar
import java.util.UUID

/**
 * What a sync brought from other devices, for the optional notification ("From Marc's
 * iPhone: 2 bottles opened, 3 bottles added."). Worked out on the device by comparing the
 * cellar before and after taking in a sync; nothing leaves it.
 *
 * Rules (the same as CaveKit's `CellarChangeSummary`):
 * - a bottle that appears is **added** when it is in the cellar or on order, **opened** when
 *   it is already drunk, and counted in **other** otherwise (given away);
 * - a bottle that becomes drunk is **opened**; one that disappears or becomes given away,
 *   sold or broken is **removed**; any other change to a bottle is **other**;
 * - a wine that appears without bottles, with a discovery to buy, is a **wine added** (to the
 *   shopping list, its discovery not counted again); any other wine that appears is told by
 *   its bottles or its discovery; a wine deleted is not counted (its bottles are); a wine
 *   edited, a rack, a discovery, a saved search, the settings, or the rack order when racks
 *   were only reordered, that changed is **other**;
 * - each change is credited to the device in the stamp of the changed object after the
 *   sync. Changes stamped by this device (repairs of the merge) are not counted; a change
 *   without a stamp counts, from an unknown device.
 */
data class CellarChangeSummary(
    val opened: Int = 0,
    val added: Int = 0,
    val removed: Int = 0,
    val winesAdded: Int = 0,
    val other: Int = 0,
    /** The devices that made the changes; empty when none is known. */
    val devices: Set<UUID> = emptySet(),
    /** Some change came without a stamp: its device is not known. */
    val unknownDevice: Boolean = false,
) {
    val isEmpty: Boolean get() = opened == 0 && added == 0 && removed == 0 && winesAdded == 0 && other == 0

    /** Only bottles were opened: the notification opens the Journal rather than the Cellar. */
    val onlyOpened: Boolean get() = opened > 0 && added == 0 && removed == 0 && winesAdded == 0 && other == 0

    /** The one device that made every change, or null (several, or one not known). */
    val singleDevice: UUID? get() = devices.singleOrNull()?.takeIf { !unknownDevice }

    /** Both summaries together, for a notification that replaces an earlier one not seen yet. */
    operator fun plus(other: CellarChangeSummary) = CellarChangeSummary(
        opened + other.opened,
        added + other.added,
        removed + other.removed,
        winesAdded + other.winesAdded,
        this.other + other.other,
        devices + other.devices,
        unknownDevice || other.unknownDevice,
    )

    companion object {
        fun between(before: Cellar, after: Cellar, stamps: Map<String, Stamp>, localDevice: UUID): CellarChangeSummary {
            var opened = 0
            var added = 0
            var removed = 0
            var winesAdded = 0
            var other = 0
            val devices = HashSet<UUID>()
            var unknown = false

            /** Counts a change of [key] unless this device made it: [count] says if it is one, and the device is credited. */
            fun change(key: String, count: () -> Boolean) {
                val device = stamps[key]?.device
                if (device == localDevice || !count()) return
                if (device == null || device == Stamp.ZERO.device) unknown = true else devices += device
            }

            fun <T> compare(kind: SyncKey.Kind, old: Map<UUID, T>, new: Map<UUID, T>, classify: (T?, T?) -> Boolean) {
                for (id in old.keys + new.keys) {
                    val a = old[id]
                    val b = new[id]
                    if (a != b) change(SyncKey.of(kind, id)) { classify(a, b) }
                }
            }

            compare(SyncKey.Kind.Bottle, before.bottles, after.bottles) { a, b ->
                when {
                    b == null -> removed++
                    a == null -> when (b.status) {
                        Bottle.Status.InCellar, Bottle.Status.Ordered -> added++
                        Bottle.Status.Consumed -> opened++
                        Bottle.Status.Gone -> other++
                    }
                    a.status != Bottle.Status.Consumed && b.status == Bottle.Status.Consumed -> opened++
                    a.status != Bottle.Status.Gone && b.status == Bottle.Status.Gone -> removed++
                    else -> other++
                }
                true
            }
            val withBottles = after.bottles.values.mapTo(HashSet()) { it.wineId }
            val toBuy = after.discoveries.values.filter { it.wantsToBuy }.mapTo(HashSet()) { it.wineId }
            // Wines counted as added to the shopping list: their discovery is not counted again.
            val listed = HashSet<UUID>()
            for (id in before.wines.keys + after.wines.keys) {
                val a = before.wines[id]
                val b = after.wines[id] ?: continue
                if (a == b) continue
                if (a == null) {
                    if (id in withBottles || id !in toBuy) continue
                    change(SyncKey.of(SyncKey.Kind.Wine, id)) { winesAdded++; listed += id; true }
                } else {
                    change(SyncKey.of(SyncKey.Kind.Wine, id)) { other++; true }
                }
            }
            val countOther: (Any?, Any?) -> Boolean = { _, _ -> other++; true }
            compare(SyncKey.Kind.Discovery, before.discoveries, after.discoveries) { a, b ->
                if (a == null && b != null && b.wineId in listed) return@compare false
                other++
                true
            }
            compare(SyncKey.Kind.Rack, CellarMerge.byId(before.racks), CellarMerge.byId(after.racks), countOther)
            compare(
                SyncKey.Kind.Search,
                CellarMerge.searchesById(before.settings.savedSearches),
                CellarMerge.searchesById(after.settings.savedSearches),
                countOther,
            )
            if (before.settings.copy(savedSearches = null) != after.settings.copy(savedSearches = null)) change(SyncKey.SETTINGS) { other++; true }
            // A rack added or removed already counts; only a new order of the same racks is one more change.
            val order = before.racks.map { it.id }
            val newOrder = after.racks.map { it.id }
            if (order != newOrder && order.toSet() == newOrder.toSet()) change(SyncKey.RACK_ORDER) { other++; true }
            return CellarChangeSummary(opened, added, removed, winesAdded, other, devices, unknown)
        }
    }
}
