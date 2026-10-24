package app.encave.core

import java.util.UUID

/**
 * The kind of storage a person chooses when creating one. Each maps onto [Rack], with no
 * field of its own: see [StorageSettings.rack] and [Rack.storageType]. Mirrors CaveKit.
 */
enum class StorageType {
    /** A rack on a wall: a grid, a honeycomb or diamond bins ([RackLayout]). */
    Rack,

    /** Shelves of bottles in one row each, or in layers stacked in quincunx. */
    WineFridge,

    /** Crates or boxes, each a bin holding several bottles. */
    Crates,
}

/** How a rack holds its bottles. */
enum class RackLayout { Grid, Honeycomb, Diamond }

/** What the storage wizard asks for, type by type. Only the settings of [type] are read. */
data class StorageSettings(
    val type: StorageType,
    // Rack.
    val layout: RackLayout = RackLayout.Grid,
    val rows: Int = 6,
    val columns: Int = 8,
    /** Bottles one behind the other (rack or wine fridge). */
    val depth: Int = if (type == StorageType.WineFridge) 2 else 1,
    /** Diamond bins and crates. */
    val bottlesPerBin: Int = if (type == StorageType.Crates) 12 else 10,
    // Wine fridge.
    val shelves: Int = 5,
    val bottlesPerShelf: Int = 6,
    /** 1: a single row on each shelf; 2 or 3: layers stacked in quincunx. */
    val layersPerShelf: Int = 2,
    /** Two deep, in layers: the back bottles lie head-to-tail. */
    val headToTail: Boolean = true,
    // Crates.
    val bins: Int = 6,
) {
    /** Head-to-tail applies to a fridge with layers, two deep. */
    val offersHeadToTail: Boolean get() = type == StorageType.WineFridge && layersPerShelf > 1 && depth == 2

    /** The rack these settings describe. */
    fun rack(id: UUID = UUID.randomUUID(), name: String): Rack {
        fun count(value: Int) = value.coerceIn(1, MAX_COUNT)
        return when (type) {
            StorageType.Rack -> when (layout) {
                RackLayout.Grid -> Rack(id = id, name = name, kind = RackKind.Grid, rows = count(rows), columns = count(columns),
                    depth = depth.coerceIn(1, 2))
                RackLayout.Honeycomb -> Rack(id = id, name = name, kind = RackKind.Staggered, rows = count(rows),
                    columns = count(columns), depth = depth)
                RackLayout.Diamond -> Rack(id = id, name = name, kind = RackKind.Bins, rows = count(rows), columns = count(columns),
                    binCapacity = count(bottlesPerBin))
            }
            StorageType.WineFridge -> {
                val shelves = shelves.coerceIn(1, MAX_SHELVES)
                val depth = depth.coerceIn(1, 2)
                if (layersPerShelf <= 1) {
                    Rack(id = id, name = name, kind = RackKind.Shelves, rows = shelves, columns = count(bottlesPerShelf), depth = depth)
                } else {
                    val layers = layersPerShelf.coerceAtMost(Rack.MAX_LAYERS)
                    // Each layer above the bottom one rests in its gaps: one fewer, then as many.
                    Rack(
                        id = id, name = name, kind = RackKind.Staggered, rows = shelves * layers, columns = count(bottlesPerShelf),
                        depth = depth, shelfLayers = List(shelves) { layers }, backReversed = headToTail && depth == 2,
                    )
                }
            }
            StorageType.Crates -> {
                val bins = count(bins)
                val perRow = minOf(bins, BINS_PER_ROW)
                val rows = (bins + perRow - 1) / perRow
                val last = bins - (rows - 1) * perRow
                Rack(
                    id = id, name = name, kind = RackKind.Bins, rows = rows, columns = perRow, binCapacity = count(bottlesPerBin),
                    rowColumns = if (last == perRow) null else List(rows - 1) { perRow } + last,
                )
            }
        }.normalized()
    }

    companion object {
        /** Crates are drawn this many to a row. */
        const val BINS_PER_ROW = 4

        /** Largest value the wizard offers for a count, well below [Rack.MAX_SIZE]. */
        const val MAX_COUNT = 40

        /** Most shelves a fridge can have, so three layers each stay within [Rack.MAX_SIZE] rows. */
        const val MAX_SHELVES = 30

        /** Settings that make this rack again, for editing and for describing it: only those of its type. */
        fun of(rack: Rack): StorageSettings {
            val base = StorageSettings(rack.storageType)
            return when (rack.storageType) {
                StorageType.Rack -> base.copy(
                    layout = when (rack.kind) {
                        RackKind.Staggered -> RackLayout.Honeycomb
                        RackKind.Bins -> RackLayout.Diamond
                        else -> RackLayout.Grid
                    },
                    rows = rack.rows, columns = rack.columns, depth = rack.depth,
                    bottlesPerBin = if (rack.kind == RackKind.Bins) rack.binCapacity else base.bottlesPerBin,
                )
                StorageType.WineFridge -> {
                    val layers = rack.shelfLayers?.takeIf { rack.hasShelfLayers }
                    base.copy(
                        depth = rack.depth,
                        shelves = layers?.size ?: rack.rows,
                        layersPerShelf = layers?.maxOrNull() ?: 1,
                        bottlesPerShelf = rack.columns,
                        headToTail = rack.isBackReversed,
                    )
                }
                StorageType.Crates -> base.copy(bins = rack.cellCount, bottlesPerBin = rack.binCapacity)
            }
        }

        /** [base], or "base 2", "base 3"… when a storage already has that name. */
        fun uniqueName(base: String, names: List<String>): String {
            val taken = names.map { it.trim().lowercase() }.toSet()
            if (base.lowercase() !in taken) return base
            var number = 2
            while ("$base $number".lowercase() in taken) number++
            return "$base $number"
        }
    }
}

/**
 * The kind of storage, as the wizard names it: a honeycomb on shelves or shelves are a wine
 * fridge; bins laid out as the wizard lays out crates are crates, other bins are diamond
 * bins in a rack (see [isCratesLayout]).
 */
val Rack.storageType: StorageType
    get() = when (kind) {
        RackKind.Grid -> StorageType.Rack
        RackKind.Staggered -> if (hasShelfLayers) StorageType.WineFridge else StorageType.Rack
        RackKind.Shelves -> StorageType.WineFridge
        RackKind.Bins -> if (isCratesLayout) StorageType.Crates else StorageType.Rack
    }

/**
 * Bins laid out as crates are: [StorageSettings.BINS_PER_ROW] to a row, the last row
 * possibly shorter. Nothing saved tells diamond bins from crates, so the shape does; a
 * diamond rack of one row, or of rows of exactly that many bins, reads as crates.
 */
val Rack.isCratesLayout: Boolean
    get() {
        val bins = cellCount
        if (kind != RackKind.Bins || bins < 1) return false
        val perRow = minOf(bins, StorageSettings.BINS_PER_ROW)
        val expectedRows = (bins + perRow - 1) / perRow
        return rows == expectedRows &&
            (0 until rows).all { columnsInRow(it) == if (it < rows - 1) perRow else bins - (rows - 1) * perRow }
    }

/** What the Storage list counts: rows of a rack, shelves of a fridge, bins of crates or diamond bins. */
val Rack.storageCount: Int
    get() = when (storageType) {
        StorageType.Rack -> if (kind == RackKind.Bins) cellCount else rows
        StorageType.WineFridge -> if (hasShelfLayers) shelfLayers?.size ?: rows else rows
        StorageType.Crates -> cellCount
    }
