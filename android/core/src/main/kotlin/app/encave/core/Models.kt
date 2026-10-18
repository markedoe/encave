package app.encave.core

import java.math.BigDecimal
import java.text.Normalizer
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

/** Style of wine. Always shown with a label, never by color alone. */
@Serializable
enum class WineColor(val raw: String) {
    @SerialName("red") Red("red"),
    @SerialName("white") White("white"),
    @SerialName("rose") Rose("rose"),
    @SerialName("sparkling") Sparkling("sparkling"),
    @SerialName("dessert") Dessert("dessert"),
    @SerialName("fortified") Fortified("fortified"),
    @SerialName("orange") Orange("orange");

    companion object {
        fun fromRaw(raw: String): WineColor? = entries.firstOrNull { it.raw == raw }
    }
}

/** A wine: the invisible grouping behind bottles ("Château X 2015"). */
@Serializable
data class Wine(
    @Required @Serializable(with = UuidSerializer::class) val id: UUID = UUID.randomUUID(),
    val name: String,
    val producer: String? = null,
    val vintage: Int? = null,
    val color: WineColor? = null,
    val region: String? = null,
    val country: String? = null,
    val appellation: String? = null,
    val grapes: List<String> = emptyList(),
    /** First and last year of the drinking window, when known. */
    val drinkFrom: Int? = null,
    val drinkUntil: Int? = null,
    /** Free-form notes about the wine itself (not a tasting). */
    val notes: String? = null,
    /** Identifier of a photo (label or bottle) stored by the app next to the cellar. */
    @SerialName("photoID") val photoId: String? = null,
    /**
     * Barcodes (EAN, UPC) seen on its bottles, digits only, to recognize it at the next
     * scan. Null rather than empty, so files of wines without barcodes stay as they were.
     */
    val barcodes: List<String>? = null,
    /** The user's own labels ("gift", "for summer"), normalized; null rather than empty. */
    val tags: List<String>? = null,
    /**
     * What a bottle sells for today, accepted by the user from a web search or typed:
     * the amount, its currency code and the day it was set. Null when never set.
     */
    @Serializable(with = DecimalSerializer::class) val currentPrice: BigDecimal? = null,
    val currentPriceCurrency: String? = null,
    @Serializable(with = InstantSerializer::class) val currentPriceDate: Instant? = null,
    /** More photos (back label, receipt…), synced like the label photo; null rather than empty. */
    @SerialName("extraPhotoIDs") val extraPhotoIds: List<String>? = null,
    /**
     * The picture shown instead of the label photo (found on the internet or an
     * illustration made on an Apple device); null shows the label photo. Synced.
     */
    @SerialName("coverPhotoID") val coverPhotoId: String? = null,
) {
    /** The label photo, then the others, then the cover. */
    val allPhotoIds: List<String> get() = listOfNotNull(photoId) + extraPhotoIds.orEmpty() + listOfNotNull(coverPhotoId)

    /** The picture to show: the cover if any, else the label photo. */
    val shownPhotoId: String? get() = coverPhotoId ?: photoId

    /** "Château X 2015" or "Château X" for non-vintage wines. */
    val displayName: String get() = if (vintage != null) "$name $vintage" else name

    /** Same wine label, used to merge duplicates on import ("same name, producer, vintage"). */
    val identityKey: String
        get() = listOf(name, producer ?: "", vintage?.toString() ?: "")
            .joinToString("|") { it.trim().lowercase(Locale.ROOT) }

    /** Case- and accent-insensitive search over the label fields and grapes. */
    fun matches(needle: String): Boolean {
        val folded = fold(needle)
        val fields = listOfNotNull(name, producer, region, country, appellation, vintage?.toString()) + grapes +
            barcodes.orEmpty() + tags.orEmpty()
        return fields.any { fold(it).contains(folded) }
    }

    /** True if the wine has this tag, ignoring case. */
    fun hasTag(tag: String): Boolean = tags.orEmpty().any { it.equals(tag, ignoreCase = true) }

    /**
     * Tags normalized and empty lists left out, as Swift's initializer keeps them. Extra
     * photos are named after their content, so the same picture added twice is kept once.
     */
    fun normalized(): Wine {
        val cleanTags = normalizedTags(tags)
        val cleanBarcodes = barcodes?.ifEmpty { null }
        val cleanExtras = extraPhotoIds?.distinct()?.ifEmpty { null }
        return if (cleanTags == tags && cleanBarcodes == barcodes && cleanExtras == extraPhotoIds) this
        else copy(tags = cleanTags, barcodes = cleanBarcodes, extraPhotoIds = cleanExtras)
    }

    /** Where the window comes from, to explain it. */
    val windowSource: WindowSource
        get() {
            val from = drinkFrom ?: return WindowSource.Missing
            val until = drinkUntil ?: return WindowSource.Missing
            val estimate = estimatedWindow()
            if (vintage != null && estimate != null && estimate.first == from && estimate.last == until) {
                return WindowSource.Estimated(from - vintage, until - from)
            }
            return WindowSource.Entered
        }

    /** Moves the end of the window by [years], never before its start. */
    fun movingWindowEnd(years: Int): Wine {
        val until = drinkUntil ?: return this
        return copy(drinkUntil = maxOf(drinkFrom ?: Int.MIN_VALUE, until + years))
    }

    /** Adds a barcode if it is new (at least 6 digits). The wine is unchanged otherwise. */
    fun addingBarcode(code: String): Wine {
        val normalized = normalizedBarcode(code)
        if (normalized.length < 6 || barcodes.orEmpty().contains(normalized)) return this
        return copy(barcodes = barcodes.orEmpty() + normalized)
    }

    /** Rough drinking window when none is entered, from color and vintage. */
    fun estimatedWindow(): IntRange? {
        val vintage = vintage ?: return null
        val (start, length) = when (color) {
            WineColor.Red -> 3 to 12
            WineColor.White -> 1 to 6
            WineColor.Rose -> 0 to 3
            WineColor.Sparkling -> 1 to 8
            WineColor.Dessert -> 3 to 25
            WineColor.Fortified -> 5 to 30
            WineColor.Orange -> 1 to 8
            null -> 2 to 8
        }
        return (vintage + start)..(vintage + start + length)
    }

    companion object {
        /** Digits only: "3 760 1234 5678 9" and "3760123456789" are the same barcode. */
        fun normalizedBarcode(code: String): String = code.filter { it in '0'..'9' }.take(20)

        /** Trimmed, without empty ones or case-insensitive duplicates (the first spelling is kept); null if none. */
        fun normalizedTags(tags: List<String>?): List<String>? = Discovery.normalized(tags.orEmpty()).ifEmpty { null }

        private val marks = Regex("\\p{Mn}+")
        internal fun fold(text: String): String =
            marks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)
    }
}

/**
 * Where a bottle sits: rack, row and column (zero-based), and how deep in the cell
 * (front or back of a double-depth rack, or the place in a bin's pile).
 */
/** Why a wine has the drinking window it has. */
sealed class WindowSource {
    /** Worked out from the style and the vintage. */
    data class Estimated(val yearsAfterHarvest: Int, val yearsAtBest: Int) : WindowSource()

    /** Entered by the user or filled from the label. */
    data object Entered : WindowSource()
    data object Missing : WindowSource()
}

@Serializable
data class Slot(
    @SerialName("rackID") @Serializable(with = UuidSerializer::class) val rackId: UUID,
    val row: Int,
    val column: Int,
    /** 0 is the front (or the first bottle of a bin). */
    val depth: Int = 0,
) {
    /** The cell this slot belongs to, whatever its depth. */
    val cell: RackCell get() = RackCell(rackId, row, column)
}

/** One cell of a rack: a position in a grid, a place on a shelf or a whole bin. */
data class RackCell(val rackId: UUID, val row: Int, val column: Int)

/** The kind of storage, as it exists in real cellars. */
@Serializable
enum class RackKind(val raw: String) {
    /** Classic rack: one bottle per cell in rows and columns, possibly two deep. */
    @SerialName("grid") Grid("grid"),

    /** Honeycomb rack: every other row is offset by half a bottle and holds one fewer. */
    @SerialName("staggered") Staggered("staggered"),

    /** Wine fridge or cabinet: shelves holding a row of bottles, one or more deep. */
    @SerialName("shelves") Shelves("shelves"),

    /** Diamond bins or crates: each bin holds several bottles in a pile. */
    @SerialName("bins") Bins("bins"),
}

/**
 * A place where bottles are stored: a rack, a wine fridge, a set of bins.
 * Rows (or shelves) are lettered from the top, cells numbered from the left.
 * Files from versions with a "layout" key ("grid" or the decorative "diamond") load as grids.
 */
@Serializable
data class Rack(
    @Required @Serializable(with = UuidSerializer::class) val id: UUID = UUID.randomUUID(),
    val name: String,
    val kind: RackKind = RackKind.Grid,
    /** Rows, shelves, or rows of bins. */
    val rows: Int,
    /** Cells per row (the widest row for honeycomb racks). */
    val columns: Int,
    /** Bottles one behind the other in a cell (grid and shelves). */
    val depth: Int = 1,
    /** Bottles per bin (bins only). */
    val binCapacity: Int = 12,
    /** Cells in each row when rows differ (a triangle top, an irregular cellar). */
    val rowColumns: List<Int>? = null,
) {
    /** Cells in a row: custom widths first, then one fewer on offset honeycomb rows. */
    fun columnsInRow(row: Int): Int {
        rowColumns?.getOrNull(row)?.let { return it }
        if (kind == RackKind.Staggered && row % 2 == 1) return maxOf(1, columns - 1)
        return columns
    }

    /** The widest row, for drawing. */
    val maxColumns: Int get() = (0 until rows).maxOfOrNull { columnsInRow(it) } ?: columns

    /** Bottles that fit in one cell. */
    val positionsPerCell: Int
        get() = when (kind) {
            RackKind.Bins -> binCapacity
            RackKind.Staggered -> 1
            else -> depth
        }

    val cellCount: Int get() = (0 until rows).sumOf { columnsInRow(it) }

    val capacity: Int get() = cellCount * positionsPerCell

    fun contains(slot: Slot): Boolean = slot.rackId == id && slot.row in 0 until rows &&
        slot.column in 0 until columnsInRow(slot.row) && slot.depth in 0 until positionsPerCell

    fun contains(cell: RackCell): Boolean =
        cell.rackId == id && cell.row in 0 until rows && cell.column in 0 until columnsInRow(cell.row)

    val cells: List<RackCell>
        get() = (0 until rows).flatMap { row -> (0 until columnsInRow(row)).map { RackCell(id, row, it) } }

    /**
     * Every slot, in the order bottles are placed automatically: a bin is filled before
     * the next one; elsewhere the front of a row is filled before the back.
     */
    val allSlots: List<Slot>
        get() = (0 until rows).flatMap { row ->
            val width = columnsInRow(row)
            if (kind == RackKind.Bins) {
                (0 until width).flatMap { column -> (0 until positionsPerCell).map { Slot(id, row, column, it) } }
            } else {
                (0 until positionsPerCell).flatMap { depth -> (0 until width).map { Slot(id, row, it, depth) } }
            }
        }

    /** "B3" for row 1, column 2. */
    fun label(row: Int, column: Int): String = "${rowLetters(row)}${column + 1}"

    /** "B3", or "B3-2" for the second bottle of a cell that holds several. */
    fun label(slot: Slot): String {
        val base = label(slot.row, slot.column)
        return if (positionsPerCell > 1) "$base-${slot.depth + 1}" else base
    }

    fun label(cell: RackCell): String = label(cell.row, cell.column)

    /** Parses "B3" or "B3-2" back into a slot of this rack. */
    fun slotForLabel(label: String): Slot? {
        val parts = label.trim().uppercase(Locale.ROOT).split("-", limit = 2)
        val upper = parts.first()
        val letters = upper.takeWhile { it.isLetter() }
        val digits = upper.drop(letters.length)
        // Short labels only ("AB12-3"): longer ones are no place of a rack, and would overflow.
        if (letters.length !in 1..3 || digits.length !in 1..4 || !digits.all { it in '0'..'9' }) return null
        val column = digits.toInt()
        var depth = 0
        if (parts.size > 1) {
            val text = parts[1]
            if (text.length !in 1..4 || !text.all { it in '0'..'9' }) return null
            depth = text.toInt() - 1
        }
        var row = 0
        for (char in letters) {
            if (char !in 'A'..'Z') return null
            row = row * 26 + (char - 'A' + 1)
        }
        val slot = Slot(id, row - 1, column - 1, depth)
        return if (contains(slot)) slot else null
    }

    /** Limits applied by the Swift initializer and decoder. */
    fun normalized(): Rack {
        val fixed = copy(
            rows = rows.coerceIn(1, MAX_SIZE), columns = columns.coerceIn(1, MAX_SIZE),
            depth = depth.coerceIn(1, MAX_DEPTH), binCapacity = binCapacity.coerceIn(1, MAX_SIZE),
            rowColumns = rowColumns?.take(MAX_SIZE)?.map { it.coerceIn(1, MAX_SIZE) },
        )
        return if (fixed == this) this else fixed
    }

    companion object {
        const val MAX_DEPTH = 3

        /**
         * Largest number of rows, of cells in a row and of bottles in a bin. The editor
         * stays well below; a rack from another device or a file is cut to it, so a damaged
         * or hostile one cannot make every device allocate millions of cells.
         */
        const val MAX_SIZE = 100

        fun rowLetters(index: Int): String {
            var n = index
            val letters = StringBuilder()
            do {
                letters.insert(0, 'A' + n % 26)
                n = n / 26 - 1
            } while (n >= 0)
            return letters.toString()
        }
    }
}

/** A record of drinking a bottle, from a quick rating to a full tasting note. */
@Serializable
data class Tasting(
    @Required @Serializable(with = InstantSerializer::class) val date: Instant = ReferenceDates.now(),
    /** 1 to 5, optional. Clamped when stored in a cellar. */
    val rating: Int? = null,
    val note: String? = null,
    val occasion: String? = null,
    val appearance: String? = null,
    val nose: String? = null,
    val palate: String? = null,
    val finish: String? = null,
    val foodPairing: String? = null,
) {
    /** True when any structured field beyond rating and note is filled. */
    val isDetailed: Boolean
        get() = listOf(appearance, nose, palate, finish, foodPairing).any { !it.isNullOrEmpty() }

    fun normalized(): Tasting {
        val clamped = rating?.coerceIn(1, 5)
        return if (clamped == rating) this else copy(rating = clamped)
    }
}

/**
 * One physical bottle. Each bottle is its own entity with a status, so two
 * people drinking "the last bottle" at once is a visible conflict, not a wrong count.
 */
@Serializable
data class Bottle(
    @Required @Serializable(with = UuidSerializer::class) val id: UUID = UUID.randomUUID(),
    @SerialName("wineID") @Serializable(with = UuidSerializer::class) val wineId: UUID,
    val status: Status = Status.InCellar,
    val slot: Slot? = null,
    /** Where the bottle is when it is not in a rack ("Kitchen", "Box in garage"). */
    val storageNote: String? = null,
    @Required @Serializable(with = InstantSerializer::class) val addedAt: Instant = ReferenceDates.now(),
    @Serializable(with = InstantSerializer::class) val purchaseDate: Instant? = null,
    @Serializable(with = DecimalSerializer::class) val purchasePrice: BigDecimal? = null,
    val currency: String? = null,
    val purchasePlace: String? = null,
    val receivedFrom: String? = null,
    /** Bottle size in millilitres (750 standard, 1500 magnum...). */
    val sizeML: Int = 750,
    val tasting: Tasting? = null,
    /** For an ordered bottle: when it should arrive. */
    @Serializable(with = InstantSerializer::class) val expectedAt: Instant? = null,
    /** Kept for an occasion ("Paul's 40th"): not suggested for tonight. */
    val reservedFor: String? = null,
) {
    @Serializable
    enum class Status(val raw: String) {
        @SerialName("inCellar") InCellar("inCellar"),

        /** Bought, not delivered yet: never has a place, not counted in the cellar. */
        @SerialName("ordered") Ordered("ordered"),
        @SerialName("consumed") Consumed("consumed"),

        /** Given away, sold or broken: no longer in the cellar, not drunk by you. */
        @SerialName("gone") Gone("gone");

        companion object {
            fun fromRaw(raw: String): Status? = entries.firstOrNull { it.raw == raw }
        }
    }
}

/** User preferences stored with the cellar. */
@Serializable
data class CellarSettings(
    /** ISO 4217 code used by default for prices ("EUR", "CHF"...). */
    @Required val currency: String = "EUR",
    /** Searches the user named, shown as chips in the Cellar. Null when there are none. */
    val savedSearches: List<SavedSearch>? = null,
)

/** A search and filters kept under a name ("Burgundy, ready"). */
@Serializable
data class SavedSearch(
    @Required @Serializable(with = UuidSerializer::class) val id: UUID = UUID.randomUUID(),
    val name: String,
    val query: String,
    val colors: List<WineColor>,
    val maturities: List<Maturity>,
    @SerialName("rackID") @Serializable(with = UuidSerializer::class) val rackId: UUID? = null,
    val tag: String? = null,
    val reservation: String? = null,
) {
    val filter: CellarFilter
        get() = CellarFilter(query, colors.toSet(), maturities.toSet(), rackId, tag, reservation)

    companion object {
        /** By name (ignoring case), then by ID: the same order on every device, as in Swift. */
        fun ordered(searches: List<SavedSearch>): List<SavedSearch> =
            searches.sortedWith(compareBy<SavedSearch> { it.name.lowercase(java.util.Locale.ROOT) }.thenBy { it.id.toString().lowercase(java.util.Locale.ROOT) })

        /** Trimmed; colors and maturities sorted by their string value, so the same filters always encode the same way. */
        fun of(name: String, filter: CellarFilter, id: UUID = UUID.randomUUID()) = SavedSearch(
            id = id, name = name.trim(), query = filter.query.trim(),
            colors = filter.colors.sortedBy { it.raw }, maturities = filter.maturities.sortedBy { it.raw },
            rackId = filter.rackId, tag = filter.tag, reservation = filter.reservation,
        )
    }
}
