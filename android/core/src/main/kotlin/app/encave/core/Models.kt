package app.encave.core

import java.math.BigDecimal
import java.text.Normalizer
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
@KeepGeneratedSerializer
@Serializable(with = WineSerializer::class)
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
    /** Documents kept with the wine, so with all its bottles (an invoice, a technical sheet). Null rather than empty. */
    val attachments: List<Attachment>? = null,
    /** When the details were last completed by the AI from the label photo; null when never. Synced. */
    @Serializable(with = InstantSerializer::class) val aiCompletedAt: Instant? = null,
    /**
     * Where the whole front label is in the label photo, from the AI label recognition, to
     * frame the studio picture. Used only while it names the current [photoId] (see
     * [labelFrame]). Null when never found. Synced.
     */
    val labelFrame: WineLabelFrame? = null,
    /** Alcohol by volume, in percent (13.5). */
    @Serializable(with = DecimalSerializer::class) val abv: BigDecimal? = null,
    /** Free text, with suggestions ("Grand Cru", "Cru Bourgeois"). */
    val classification: String? = null,
    /** The lieu-dit or climat (a named vineyard). */
    val lieuDit: String? = null,
    /** Sparkling wines: a [Vocabulary.dosages] id ("brut"). Kept even when unknown. */
    val dosage: String? = null,
    /** Still wines: a [Vocabulary.sweetnesses] id ("dry", "offDry"…). Kept even when unknown. */
    val sweetness: String? = null,
    /**
     * Share of each grape in percent (1 to 100), keyed by the grape as written in [grapes],
     * which stays the list older versions read. A share whose grape is no longer listed is dropped. Null rather than empty.
     */
    val grapeShares: Map<String, Int>? = null,
    /** How it was aged, [Vocabulary.ageings] ids, each once. Null rather than empty. */
    val ageing: List<String>? = null,
    /** [Vocabulary.farmings] id ("organic"), and whether it is certified (true or null, only with a farming). */
    val farming: String? = null,
    val farmingCertified: Boolean? = null,
    /** [Vocabulary.closures] id ("screwCap"). */
    val closure: String? = null,
    /** Critics' and guides' scores and awards, as typed. Null rather than empty. */
    val criticScores: List<CriticScore>? = null,
    /** Keys written by a newer version, kept and written back unchanged (docs/SYNC.md §1). Part of equality, so a merge that only brings them in is still saved. */
    @Transient val unknownKeys: JsonObject = NoUnknownKeys,
) {
    /** The share of a grape in percent, if given. */
    fun share(grape: String): Int? = grapeShares?.entries?.firstOrNull { grapeKey(it.key) == grapeKey(grape) }?.value

    /** True when any of the details under "More details" is filled. */
    val hasMoreDetails: Boolean
        get() = abv != null || !classification.isNullOrEmpty() || !lieuDit.isNullOrEmpty() || dosage != null || sweetness != null ||
            grapeShares != null || ageing != null || farming != null || closure != null

    /**
     * The details normalized the same way on every device (docs/SYNC.md §1), as CaveKit
     * decodes them; ids this version does not know are kept as they are.
     */
    fun readNormalized(): Wine {
        // Without trailing zeros, as DecimalSerializer reads it: BigDecimal equality counts the scale.
        val cleanAbv = abv?.stripTrailingZeros()
        val cleanDosage = dosage?.ifEmpty { null }
        val cleanSweetness = sweetness?.ifEmpty { null }
        val cleanShares = keptShares(grapeShares, grapes)
        val cleanAgeing = Tasting.uniqueIds(ageing)
        val cleanFarming = farming?.ifEmpty { null }
        val cleanCertified = if (cleanFarming != null && farmingCertified == true) true else null
        val cleanClosure = closure?.ifEmpty { null }
        val cleanCritics = criticScores?.ifEmpty { null }
        if (cleanAbv == abv && cleanDosage == dosage && cleanSweetness == sweetness && cleanShares == grapeShares && cleanAgeing == ageing &&
            cleanFarming == farming && cleanCertified == farmingCertified && cleanClosure == closure && cleanCritics == criticScores
        ) return this
        return copy(
            abv = cleanAbv, dosage = cleanDosage, sweetness = cleanSweetness, grapeShares = cleanShares, ageing = cleanAgeing,
            farming = cleanFarming, farmingCertified = cleanCertified, closure = cleanClosure, criticScores = cleanCritics,
        )
    }

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

    /**
     * What a search looks in: the label fields, grapes, barcodes and tags, the details
     * (classification, lieu-dit, and the labels of dosage, sweetness, ageing, farming and
     * closure in the person's language) and the critics' sources and awards.
     */
    val searchFields: List<String>
        get() = listOfNotNull(name, producer, region, country, appellation, vintage?.toString()) + grapes +
            barcodes.orEmpty() + tags.orEmpty() +
            listOfNotNull(
                classification, lieuDit, dosage?.let { Vocabulary.label(Vocabulary.Kind.Dosage, it) },
                sweetness?.let { Vocabulary.label(Vocabulary.Kind.Sweetness, it) },
                farming?.let { Vocabulary.label(Vocabulary.Kind.Farming, it) },
                closure?.let { Vocabulary.label(Vocabulary.Kind.Closure, it) },
            ) + ageing.orEmpty().map { Vocabulary.label(Vocabulary.Kind.Ageing, it) } +
            criticScores.orEmpty().flatMap { listOfNotNull(it.source, it.award) }

    /** Every word of [needle] is in one of the fields ("Margaux 2015", "syrah guigal"). */
    fun matches(needle: String): Boolean = SearchText.matches(needle, searchFields)

    /** True if the wine has this tag, ignoring case. */
    fun hasTag(tag: String): Boolean = tags.orEmpty().any { it.equals(tag, ignoreCase = true) }

    fun hasGrape(grape: String): Boolean = grapes.any { it.trim().equals(grape.trim(), ignoreCase = true) }

    /**
     * Tags normalized and empty lists left out, as Swift's initializer keeps them. Extra
     * photos and documents are named after their content, so the same file added twice is
     * kept once.
     */
    fun normalized(): Wine {
        val cleanTags = normalizedTags(tags)
        val cleanBarcodes = barcodes?.ifEmpty { null }
        val cleanExtras = extraPhotoIds?.distinct()?.ifEmpty { null }
        val cleanAttachments = Attachment.unique(attachments)
        val same = cleanTags == tags && cleanBarcodes == barcodes && cleanExtras == extraPhotoIds && cleanAttachments == attachments
        return (if (same) this else copy(tags = cleanTags, barcodes = cleanBarcodes, extraPhotoIds = cleanExtras, attachments = cleanAttachments))
            .readNormalized()
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
        /** A grape compared for its share: trimmed, in Unicode NFC, ignoring case (as in CaveKit). */
        fun grapeKey(grape: String): String = Normalizer.normalize(grape.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)

        /**
         * Shares in percent kept for the grapes listed (trimmed, ignoring case), each brought
         * into 1…100 (0 and below are dropped); null when none are left. A grape written twice
         * (in another case) keeps the key that sorts first, as in CaveKit.
         */
        fun keptShares(shares: Map<String, Int>?, grapes: List<String>): Map<String, Int>? {
            if (shares == null) return null
            val listed = grapes.map(::grapeKey).toSet()
            val seen = HashSet<String>()
            val kept = LinkedHashMap<String, Int>()
            for (grape in shares.keys.sorted()) {
                val key = grapeKey(grape)
                val share = shares.getValue(grape)
                if (share > 0 && key in listed && seen.add(key)) kept[grape] = minOf(100, share)
            }
            return kept.ifEmpty { null }
        }

        /** Digits only: "3 760 1234 5678 9" and "3760123456789" are the same barcode. */
        fun normalizedBarcode(code: String): String = code.filter { it in '0'..'9' }.take(20)

        /** Trimmed, without empty ones or case-insensitive duplicates (the first spelling is kept); null if none. */
        fun normalizedTags(tags: List<String>?): List<String>? = Discovery.normalized(tags.orEmpty()).ifEmpty { null }

        /** A name or producer compared for [Cellar.wine] (matching): without case, accents or extra spaces. */
        fun matchKey(text: String): String = fold(text.trim().split(Regex("\\s+")).joinToString(" "))

        private val marks = Regex("\\p{Mn}+")
        /** Without accents and case, as Apple's folding: upper then lower case gives the full
         *  case mapping, so "ß" matches "ss" and "ﬁ" matches "fi". */
        internal fun fold(text: String): String =
            marks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").uppercase(Locale.ROOT).lowercase(Locale.ROOT)
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

@KeepGeneratedSerializer
@Serializable(with = SlotSerializer::class)
data class Slot(
    @SerialName("rackID") @Serializable(with = UuidSerializer::class) val rackId: UUID,
    val row: Int,
    val column: Int,
    /** 0 is the front (or the first bottle of a bin). */
    val depth: Int = 0,
    /** Keys written by a newer version, kept and written back unchanged (docs/SYNC.md §1). Not part of equality: a slot is compared as a place (two bottles in one place). */
    @Transient val unknownKeys: JsonObject = NoUnknownKeys,
) {
    /** The cell this slot belongs to, whatever its depth. */
    val cell: RackCell get() = RackCell(rackId, row, column)

    override fun equals(other: Any?): Boolean =
        other is Slot && rackId == other.rackId && row == other.row && column == other.column && depth == other.depth

    override fun hashCode(): Int = listOf(rackId, row, column, depth).hashCode()
}

/** Keeps the keys of a newer version (see [KeepingUnknownKeys]). */
object SlotSerializer : KeepingUnknownKeys<Slot>(Slot.generatedSerializer()) {
    override fun unknownKeys(value: Slot) = value.unknownKeys
    override fun withUnknownKeys(value: Slot, keys: JsonObject) = value.copy(unknownKeys = keys)
}

/** One cell of a rack: a position in a grid, a place on a shelf or a whole bin. */
data class RackCell(val rackId: UUID, val row: Int, val column: Int)

/** What a storage of bins was made as. Written as "binStyle" on the rack (docs/SYNC.md). Mirrors CaveKit. */
enum class BinStyle(val raw: String) {
    /** Diamond bins in a rack, shown under Rack. */
    Diamond("diamond"),

    /** Crates or boxes. */
    Crates("crates"),
}

/** The kind of storage, as it exists in real cellars. */
@Serializable
enum class RackKind(val raw: String) {
    /** Classic rack: one bottle per cell in rows and columns, possibly two deep. */
    @SerialName("grid") Grid("grid"),

    /**
     * Honeycomb: rows offset by half a bottle, nesting into each other. Either continuous
     * (a honeycomb rack: every other row holds one fewer) or on shelves (a wine fridge whose
     * shelves hold bottles laid in layers stacked in quincunx, see [Rack.shelfLayers]).
     * Up to two bottles one behind the other.
     */
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
@KeepGeneratedSerializer
@Serializable(with = RackSerializer::class)
data class Rack(
    @Required @Serializable(with = UuidSerializer::class) val id: UUID = UUID.randomUUID(),
    val name: String,
    val kind: RackKind = RackKind.Grid,
    /** Rows, shelves, or rows of bins. */
    val rows: Int,
    /** Cells per row (the widest row for honeycomb racks). */
    val columns: Int,
    /**
     * Bottles one behind the other in a cell (grid, honeycomb and shelves); when rows differ
     * ([rowDepths]), the deepest row, so older versions still show every place.
     */
    val depth: Int = 1,
    /** Bottles per bin (bins only). */
    val binCapacity: Int = 12,
    /** Cells in each row when rows differ (a triangle top, an irregular cellar). */
    val rowColumns: List<Int>? = null,
    /**
     * Honeycomb on shelves (a wine fridge): how many layers each shelf holds, from the top
     * shelf, each 1 to 3; null for a continuous honeycomb. Rows are the layers, lettered from
     * the top: a shelf's last row is its bottom layer. See [normalized].
     */
    val shelfLayers: List<Int>? = null,
    /**
     * Fridge with shelf layers and temperature zones: the shelves (0 is the top one) that
     * start a new zone, below a divider. Null for a single zone.
     */
    val zoneBreaks: List<Int>? = null,
    /**
     * Fridge with shelf layers, two deep: the back bottles lie the other way round
     * ("head-to-tail"), necks facing you between the front bottles' bases. Null (false)
     * elsewhere; [normalized] keeps it only as true where it applies.
     */
    val backReversed: Boolean? = null,
    /**
     * "binStyle" as read or set: kept even when this version does not know the value, so
     * writing the rack back does not lose it (docs/SYNC.md). See [binStyle].
     */
    @SerialName("binStyle") val binStyleValue: String? = null,
    /**
     * Bottles one behind the other in each row, from the top, when rows differ (one row
     * single, the next two deep); each 1 to [depth], the same for every layer of a shelf.
     * Null when every row is [depth] deep. See [depthOfRow] and [normalized].
     */
    val rowDepths: List<Int>? = null,
    /**
     * Fridge with shelf layers: whether each row lies head-to-tail, from the top, when the
     * shelves two deep differ; the same for every layer of a shelf, false where a row is not
     * two deep. Null when every shelf two deep follows [backReversed]. See [isBackReversedRow].
     */
    val rowBackReversed: List<Boolean>? = null,
    /**
     * Diamond bins: the sides whose edge triangles hold bottles ("edgeTriangles"), as read or
     * set, values of a newer version kept. They apply only while the rows are shaped for them
     * ([diamondLayout]); otherwise every cell is a whole diamond, as older versions show it.
     */
    @SerialName("edgeTriangles") val edgeTrianglesValue: List<String>? = null,
    /** Bottles an edge triangle holds ("triangleCapacity"), as read or set. See [triangleCapacity]. */
    @SerialName("triangleCapacity") val triangleCapacityValue: Int? = null,
    /** Keys written by a newer version, kept and written back unchanged (docs/SYNC.md §1). Part of equality, so a merge that only brings them in is still saved. */
    @Transient val unknownKeys: JsonObject = NoUnknownKeys,
) {
    /**
     * Bins only: whether they were made as diamond bins in a rack or as crates. Null when not
     * saved (racks from older versions) or unknown to this version: the shape decides (see
     * [storageType]).
     */
    val binStyle: BinStyle? get() = BinStyle.entries.firstOrNull { it.raw == binStyleValue }

    /**
     * A fridge with shelf layers two deep (on some shelves at least): drawn as a front band
     * and a back band on each shelf two deep (see [hasDepthBandsOnShelf]).
     */
    val hasDepthBands: Boolean get() = hasShelfLayers && depth == 2

    /**
     * The back bottles of a fridge drawn in bands lie head-to-tail, on some shelves at least
     * (see [backReversed] and [isBackReversedRow]).
     */
    val isBackReversed: Boolean get() = backReversed == true && hasDepthBands

    /** Bottles one behind the other in this row: its own when rows differ, else [depth]. 1 for bins. */
    fun depthOfRow(row: Int): Int {
        if (kind == RackKind.Bins) return 1
        return rowDepths?.getOrNull(row) ?: depth
    }

    /** Bottles one behind the other on a shelf of a fridge with shelf layers (0 is the top one). */
    fun depthOfShelf(shelf: Int): Int = shelfRows.getOrNull(shelf)?.let { depthOfRow(it.last) } ?: depth

    /** Bottles that fit in one cell of this row: its depth, or a bin's capacity. */
    fun positionsInRow(row: Int): Int = if (kind == RackKind.Bins) binCapacity else depthOfRow(row)

    /** Rows that are not all the same beyond their number of places: their depth or how they lie. */
    val rowsDiffer: Boolean get() = rowDepths != null || rowBackReversed != null

    /** This shelf of a fridge with shelf layers is two deep: drawn as a front and a back band. */
    fun hasDepthBandsOnShelf(shelf: Int): Boolean = hasDepthBands && depthOfShelf(shelf) == 2

    /** The back bottles of this row lie head-to-tail: a fridge with shelf layers, the row two deep, its shelf head-to-tail. */
    fun isBackReversedRow(row: Int): Boolean {
        if (!isBackReversed || depthOfRow(row) != 2) return false
        return rowBackReversed?.getOrNull(row) ?: true
    }

    /** The back bottles of this shelf lie head-to-tail (0 is the top shelf). */
    fun isBackReversedShelf(shelf: Int): Boolean = shelfRows.getOrNull(shelf)?.let { isBackReversedRow(it.last) } ?: false

    /**
     * How far the layers of a band are drawn right of the same layers in front, in places:
     * head-to-tail, each neck behind sits between two front bases, half a place along.
     * Drawing only: places, numbering and counts stay the same.
     */
    fun bandShift(row: Int, depth: Int?): Float = if (isBackReversedRow(row) && depth == 1) 0.5f else 0f

    /** The largest shift of a band ([bandShift]), for the width of the drawing. */
    val maxBandShift: Float get() = if (isBackReversed) 0.5f else 0f

    /** The temperature zone of each shelf, 0 for the top one (only with shelf layers). */
    fun zoneOfShelf(shelf: Int): Int = zoneBreaks.orEmpty().count { it <= shelf }

    /** Zones of a fridge with shelf layers: 1 without breaks. */
    val zoneCount: Int get() = (zoneBreaks?.size ?: 0) + 1

    /**
     * Cells in a row: custom widths first, then one fewer on offset honeycomb rows (on
     * shelves: one fewer on the layer above the bottom one, as bottles rest in the gaps).
     */
    fun columnsInRow(row: Int): Int {
        rowColumns?.getOrNull(row)?.let { return it }
        return if (kind == RackKind.Staggered && isOffset(row)) maxOf(1, columns - 1) else columns
    }

    /** A honeycomb on shelves, as a wine fridge with bottles laid in layers. */
    val hasShelfLayers: Boolean get() = kind == RackKind.Staggered && shelfLayers != null

    /** The rows of each shelf, from the top shelf (only with shelf layers). */
    val shelfRows: List<IntRange>
        get() {
            val layers = shelfLayers?.takeIf { kind == RackKind.Staggered } ?: return emptyList()
            var start = 0
            return layers.map { count -> (start until start + count).also { start += count } }
        }

    /** Where a row is on a fridge with shelf layers: shelf 0 is the top one, layer 0 the bottom one. */
    data class ShelfPlace(val shelf: Int, val layer: Int, val layers: Int)

    fun shelfPlace(row: Int): ShelfPlace? {
        val layers = shelfLayers?.takeIf { kind == RackKind.Staggered } ?: return null
        var start = 0
        layers.forEachIndexed { shelf, count ->
            if (row < start + count) return ShelfPlace(shelf, start + count - 1 - row, count)
            start += count
        }
        return null
    }

    /** Is this row drawn offset by half a bottle? Odd honeycomb rows, or upper layers resting in the gaps; bins too. */
    fun isOffset(row: Int): Boolean = when (kind) {
        RackKind.Bins -> Math.floorMod(row - (diamondLayout?.topRows ?: 0), 2) == 1
        RackKind.Staggered -> shelfPlace(row)?.let { it.layer % 2 == 1 } ?: (row % 2 == 1)
        RackKind.Grid, RackKind.Shelves -> false
    }

    /** The widest row, for drawing. */
    val maxColumns: Int get() = (0 until rows).maxOfOrNull { columnsInRow(it) } ?: columns

    /** Bottles that fit in one cell, in the deepest row when rows differ ([positionsInRow] gives each row's). */
    val positionsPerCell: Int
        get() = when (kind) {
            RackKind.Bins -> binCapacity
            else -> depth
        }

    val cellCount: Int get() = (0 until rows).sumOf { columnsInRow(it) }

    val capacity: Int
        get() {
            val layout = diamondLayout
            return (0 until rows).sumOf { row -> (0 until columnsInRow(row)).sumOf { positions(row, it, layout) } }
        }

    /** Bottles that fit in this cell: a bin's capacity, fewer in an edge triangle ([triangleCapacity]), elsewhere its row's depth. */
    fun positions(row: Int, column: Int): Int = positions(row, column, diamondLayout)

    fun positions(cell: RackCell): Int = positions(cell.row, cell.column)

    internal fun positions(row: Int, column: Int, layout: DiamondLayout?): Int =
        if (layout != null && layout.place(row, column) is DiamondPlace.Triangle) triangleCapacity else positionsInRow(row)

    /**
     * A place of this rack as every version reads it: within the rack's [depth] (or a bin's
     * capacity), whatever its row's own depth. Sync repair and imports judge places with it,
     * so a version that does not know [rowDepths] and one that does keep the same bottles in
     * place (docs/SYNC.md). A place behind a row's depth ([hasPosition]) holds a bottle an
     * older version put there: it is kept and shown. Mirrors CaveKit.
     */
    fun contains(slot: Slot): Boolean = slot.rackId == id && slot.row in 0 until rows &&
        slot.column in 0 until columnsInRow(slot.row) && slot.depth in 0 until positionsPerCell

    /** A position the row really has, within its own depth: what the apps offer, and what an edit keeps. */
    fun hasPosition(slot: Slot): Boolean = contains(slot) && slot.depth < positions(slot.row, slot.column)

    /**
     * The places drawn and named for a cell of this row: its own depth, and as deep as the
     * bottles there go (a bottle an older version put behind a single row stays in view).
     */
    fun shownPositions(row: Int, deepest: Int?): Int = maxOf(positionsInRow(row), (deepest ?: -1) + 1)

    /**
     * The places drawn and counted for this cell: its own (fewer in an edge triangle), and as
     * many as its bottles go (an older version may fill a triangle like a whole diamond).
     */
    fun shownPositions(cell: RackCell, deepest: Int?): Int = maxOf(positions(cell), (deepest ?: -1) + 1)

    fun contains(cell: RackCell): Boolean =
        cell.rackId == id && cell.row in 0 until rows && cell.column in 0 until columnsInRow(cell.row)

    val cells: List<RackCell>
        get() = (0 until rows).flatMap { row -> (0 until columnsInRow(row)).map { RackCell(id, row, it) } }

    /**
     * Every slot, in the order bottles are placed automatically: a bin is filled before
     * the next one; elsewhere the front of a row is filled before the back. On a fridge with
     * shelf layers, shelves are filled from the top as rows are, each from its bottom layer
     * up (an upper layer rests on the one below).
     */
    val allSlots: List<Slot>
        get() {
            val layout = diamondLayout
            return (if (hasShelfLayers) shelfRows.flatMap { it.reversed() } else (0 until rows).toList()).flatMap { row ->
            val width = columnsInRow(row)
            val positions = positionsInRow(row)
            if (kind == RackKind.Bins) {
                (0 until width).flatMap { column -> (0 until positions(row, column, layout)).map { Slot(id, row, column, it) } }
            } else {
                (0 until positions).flatMap { depth -> (0 until width).map { Slot(id, row, it, depth) } }
            }
            }
        }

    /** "B3" for row 1, column 2. */
    fun label(row: Int, column: Int): String = "${rowLetters(row)}${column + 1}"

    /** "B3", or "B3-2" for the second bottle of a cell that holds several. */
    fun label(slot: Slot): String {
        val base = label(slot.row, slot.column)
        return if (shownPositions(slot.row, slot.depth) > 1) "$base-${slot.depth + 1}" else base
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

    /**
     * Limits applied by the Swift initializer and decoder. Shelf layers are kept only on a
     * honeycomb, each brought into 1…3, as many shelves as fit in [MAX_SIZE] rows; the rows
     * are then the layers.
     */
    fun normalized(): Rack {
        var layers: List<Int>? = null
        var total = 0
        if (kind == RackKind.Staggered && !shelfLayers.isNullOrEmpty()) {
            val kept = mutableListOf<Int>()
            for (value in shelfLayers) {
                val count = value.coerceIn(1, MAX_LAYERS)
                if (total + count > MAX_SIZE) break
                kept += count
                total += count
            }
            layers = kept
        }
        // Breaks between kept shelves only, each once, in order.
        val breaks = layers?.let { kept -> zoneBreaks.orEmpty().filter { it in 1 until kept.size }.toSortedSet().toList() }
            ?.takeIf { it.isNotEmpty() }
        val fixed = copy(
            rows = if (layers != null) total else rows.coerceIn(1, MAX_SIZE), columns = columns.coerceIn(1, MAX_SIZE),
            depth = depth.coerceIn(1, if (kind == RackKind.Staggered) MAX_STAGGERED_DEPTH else MAX_DEPTH),
            binCapacity = binCapacity.coerceIn(1, MAX_SIZE),
            rowColumns = rowColumns?.take(MAX_SIZE)?.map { it.coerceIn(1, MAX_SIZE) },
            edgeTrianglesValue = edgeTrianglesValue?.take(8),
            triangleCapacityValue = triangleCapacityValue?.coerceIn(1, MAX_SIZE),
            shelfLayers = layers,
            zoneBreaks = breaks,
        )
        val reversed = fixed.copy(backReversed = if (backReversed == true && fixed.hasDepthBands) true else null)
        val rowed = reversed.withRowSettings(rowDepths, rowBackReversed)
        return if (rowed == this) this else rowed
    }

    /**
     * Keeps per-row depths and head-to-tail only where rows really differ, the same way on
     * every device (docs/SYNC.md): one value per row (an array of another length is ignored),
     * each depth brought into 1…[depth], every layer of a shelf taking the value of
     * its bottom layer. Depths are dropped when every row is [depth] deep, or when none is (an
     * older version made the rack deeper: it shows every row that deep). Head-to-tail applies
     * only to rows two deep of a head-to-tail fridge, and is dropped when every such row lies
     * head-to-tail, or none does ([backReversed] then says it for all). Mirrors CaveKit.
     */
    private fun withRowSettings(depths: List<Int>?, headToTail: List<Boolean>?): Rack {
        var rack = copy(rowDepths = null, rowBackReversed = null)
        if (kind == RackKind.Bins) return rack
        // An array of another length is stale (an older version added or removed rows or
        // shelves without knowing it): the rows are read as uniform.
        if (depths != null && depths.size == rows) {
            val read = (0 until rows).map { row -> depths.getOrNull(row)?.coerceIn(1, depth) ?: depth }
            val kept = rack.alongShelves(read)
            if (depth in kept && kept.any { it != depth }) rack = rack.copy(rowDepths = kept)
        }
        if (rack.isBackReversed && headToTail != null && headToTail.size == rows) {
            val read = (0 until rows).map { row -> headToTail.getOrNull(row) ?: true }
            val kept = rack.alongShelves(read).mapIndexed { row, reversed -> reversed && rack.depthOfRow(row) == 2 }
            val deep = (0 until rows).filter { rack.depthOfRow(it) == 2 }
            if (deep.any { kept[it] } && deep.any { !kept[it] }) rack = rack.copy(rowBackReversed = kept)
        }
        return rack
    }

    /** Every layer of a shelf takes the value of its bottom layer (the shelf's last row). */
    private fun <T> alongShelves(values: List<T>): List<T> {
        if (!hasShelfLayers) return values
        val result = values.toMutableList()
        for (rows in shelfRows) for (row in rows) result[row] = values[rows.last]
        return result
    }

    companion object {
        const val MAX_DEPTH = 3

        /** Honeycomb racks hold at most two bottles one behind the other. */
        const val MAX_STAGGERED_DEPTH = 2

        /** Layers of bottles stacked on one shelf. */
        const val MAX_LAYERS = 3

        /**
         * Largest number of rows, of cells in a row and of bottles in a bin. The editor
         * stays well below; a rack from another device or a file is cut to it, so a damaged
         * or hostile one cannot make every device allocate millions of cells.
         */
        const val MAX_SIZE = 100

        /**
         * Where each row of a fridge on shelves goes when its shelves are edited. [origins]
         * gives, for each shelf of [new] from the top, the shelf of [old] it was (null for a
         * new one). A layer keeps its place counted from the bottom of its shelf; the rows of
         * a removed shelf or layer map to null. Empty unless both racks have shelf layers.
         */
        fun shelfRowMap(old: Rack, new: Rack, origins: List<Int?>): Map<Int, Int?> {
            if (!old.hasShelfLayers || !new.hasShelfLayers) return emptyMap()
            val oldShelves = old.shelfRows
            val newShelves = new.shelfRows
            val map = mutableMapOf<Int, Int?>()
            for (row in 0 until old.rows) map[row] = null
            origins.forEachIndexed { shelf, origin ->
                if (shelf !in newShelves.indices || origin == null || origin !in oldShelves.indices) return@forEachIndexed
                val from = oldShelves[origin]
                val to = newShelves[shelf]
                // Layer 0 is the bottom one, the shelf's last row.
                for (layer in 0 until minOf(from.count(), to.count())) map[from.last - layer] = to.last - layer
            }
            return map
        }

        /**
         * A slot moved by a cell map, else by a row map (see [Cellar.updateRack]); null when its
         * cell or row is gone. Depth is kept.
         */
        internal fun moved(slot: Slot, rowMap: Map<Int, Int?>, cellMap: Map<RackCell, RackCell?> = emptyMap()): Slot? {
            if (cellMap.containsKey(slot.cell)) return cellMap[slot.cell]?.let { slot.copy(row = it.row, column = it.column) }
            if (!rowMap.containsKey(slot.row)) return slot
            return rowMap[slot.row]?.let { slot.copy(row = it) }
        }

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
@KeepGeneratedSerializer
@Serializable(with = TastingSerializer::class)
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
    /**
     * The precise score out of 100 (50…100 in quarter points), when the rating was entered
     * out of 20 or 100; kept only while [rating] is its stars ([Score.kept]). Set both with [withRating].
     */
    @Serializable(with = ScoreSerializer::class) val score100: Double? = null,
    /** Aromas of the guided tasting sheet, as vocabulary ids ("fruit.cherry"), each once, in the order chosen; null rather than empty. */
    val aromas: List<String>? = null,
    /** Structure, each 1 to 5 ([Vocabulary.structure]), optional. */
    val sweetness: Int? = null,
    val acidity: Int? = null,
    val tannin: Int? = null,
    val body: Int? = null,
    val alcohol: Int? = null,
    val length: Int? = null,
    /** Appearance: intensity ("pale", "medium", "deep") and hue ("ruby", "gold"…), vocabulary ids. */
    val intensity: String? = null,
    val hue: String? = null,
    /** Keys written by a newer version, kept and written back unchanged (docs/SYNC.md §1). Part of equality, so a merge that only brings them in is still saved. */
    @Transient val unknownKeys: JsonObject = NoUnknownKeys,
) {
    /** True when any part of the guided sheet is filled: aromas, structure or appearance. */
    val hasGuidedNotes: Boolean
        get() = !aromas.isNullOrEmpty() || listOf(sweetness, acidity, tannin, body, alcohol, length).any { it != null } ||
            intensity != null || hue != null

    /** True when any structured field beyond rating and note is filled. */
    val isDetailed: Boolean
        get() = listOf(appearance, nose, palate, finish, foodPairing).any { !it.isNullOrEmpty() } || hasGuidedNotes

    /** The labels of its aromas and hue in the person's language, for search. */
    val searchLabels: List<String>
        get() = aromas.orEmpty().map { Vocabulary.label(Vocabulary.Kind.Aroma, it) } +
            listOfNotNull(hue?.let { Vocabulary.label(Vocabulary.Kind.Hue, it) })

    /** The value of a structure attribute by its vocabulary id. */
    fun level(attribute: String): Int? = when (attribute) {
        "sweetness" -> sweetness
        "acidity" -> acidity
        "tannin" -> tannin
        "body" -> body
        "alcohol" -> alcohol
        "length" -> length
        else -> null
    }

    fun withLevel(attribute: String, value: Int?): Tasting {
        val level = value?.coerceIn(1, 5)
        return when (attribute) {
            "sweetness" -> copy(sweetness = level)
            "acidity" -> copy(acidity = level)
            "tannin" -> copy(tannin = level)
            "body" -> copy(body = level)
            "alcohol" -> copy(alcohol = level)
            "length" -> copy(length = level)
            else -> this
        }
    }

    /** The rating from a value entered on a scale: stars clear the score, out of 20 or 100 keep it with its stars ([Score.entered]). Null clears both. */
    fun withRating(value: Double?, scale: RatingScale): Tasting {
        val entered = value?.let { Score.entered(it, scale) } ?: return copy(rating = null, score100 = null)
        return copy(rating = entered.rating, score100 = entered.score100)
    }

    /** Rating clamped to 1..5, and the newer fields as CaveKit reads them ([readNormalized]). */
    fun normalized(): Tasting {
        val clamped = rating?.coerceIn(1, 5)
        return (if (clamped == rating) this else copy(rating = clamped)).readNormalized()
    }

    /**
     * The newer fields normalized the same way on every device (docs/SYNC.md §1), as CaveKit
     * decodes them: a score that no longer matches the rating is stale and dropped, levels are
     * brought into 1…5, ids are kept once and empty ones are left out.
     */
    fun readNormalized(): Tasting {
        val cleanScore = Score.kept(score100, rating)
        val cleanAromas = uniqueIds(aromas)
        val levels = listOf(sweetness, acidity, tannin, body, alcohol, length).map { it?.coerceIn(1, 5) }
        val cleanIntensity = intensity?.ifEmpty { null }
        val cleanHue = hue?.ifEmpty { null }
        val unchanged = cleanScore == score100 && cleanAromas == aromas && cleanIntensity == intensity && cleanHue == hue &&
            levels == listOf(sweetness, acidity, tannin, body, alcohol, length)
        if (unchanged) return this
        return copy(
            score100 = cleanScore, aromas = cleanAromas, sweetness = levels[0], acidity = levels[1], tannin = levels[2],
            body = levels[3], alcohol = levels[4], length = levels[5], intensity = cleanIntensity, hue = cleanHue,
        )
    }

    companion object {
        /** Each non-empty id once, in the order given; null when none are left. */
        fun uniqueIds(ids: List<String>?): List<String>? = ids?.filter { it.isNotEmpty() }?.distinct()?.ifEmpty { null }
    }
}

/**
 * One physical bottle. Each bottle is its own entity with a status, so two
 * people drinking "the last bottle" at once is a visible conflict, not a wrong count.
 */
@KeepGeneratedSerializer
@Serializable(with = BottleSerializer::class)
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
    /** Documents of this bottle only (its certificate, its receipt). Null rather than empty. */
    val attachments: List<Attachment>? = null,
    /** Keys written by a newer version, kept and written back unchanged (docs/SYNC.md §1). Part of equality, so a merge that only brings them in is still saved. */
    @Transient val unknownKeys: JsonObject = NoUnknownKeys,
) {
    /** Documents once each, names normalized, null rather than empty, as CaveKit reads them. */
    fun normalized(): Bottle {
        val cleanAttachments = Attachment.unique(attachments)
        return if (cleanAttachments == attachments) this else copy(attachments = cleanAttachments)
    }

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
@KeepGeneratedSerializer
@Serializable(with = CellarSettingsSerializer::class)
data class CellarSettings(
    /** ISO 4217 code used by default for prices ("EUR", "CHF"...). */
    @Required val currency: String = "EUR",
    /** Searches the user named, shown as chips in the Cellar. Null when there are none. */
    val savedSearches: List<SavedSearch>? = null,
    /** Keys written by a newer version, kept and written back unchanged (docs/SYNC.md §1). Part of equality, so a merge that only brings them in is still saved. */
    @Transient val unknownKeys: JsonObject = NoUnknownKeys,
)

/** Keeps the keys of a newer version (see [KeepingUnknownKeys]). */
object CellarSettingsSerializer : KeepingUnknownKeys<CellarSettings>(CellarSettings.generatedSerializer()) {
    override fun unknownKeys(value: CellarSettings) = value.unknownKeys
    override fun withUnknownKeys(value: CellarSettings, keys: JsonObject) = value.copy(unknownKeys = keys)
}

/** A search and filters kept under a name ("Burgundy, ready"). */
@KeepGeneratedSerializer
@Serializable(with = SavedSearchSerializer::class)
data class SavedSearch(
    @Required @Serializable(with = UuidSerializer::class) val id: UUID = UUID.randomUUID(),
    val name: String,
    val query: String,
    val colors: List<WineColor>,
    val maturities: List<Maturity>,
    @SerialName("rackID") @Serializable(with = UuidSerializer::class) val rackId: UUID? = null,
    val tag: String? = null,
    val reservation: String? = null,
    val grape: String? = null,
    /** Keys written by a newer version, kept and written back unchanged (docs/SYNC.md §1). Part of equality, so a merge that only brings them in is still saved. */
    @Transient val unknownKeys: JsonObject = NoUnknownKeys,
) {
    val filter: CellarFilter
        get() = CellarFilter(query, colors.toSet(), maturities.toSet(), rackId, tag, reservation, grape)

    companion object {
        /** By name (ignoring case), then by ID: the same order on every device, as in Swift. */
        fun ordered(searches: List<SavedSearch>): List<SavedSearch> =
            searches.sortedWith(compareBy<SavedSearch> { it.name.lowercase(java.util.Locale.ROOT) }.thenBy { it.id.toString().lowercase(java.util.Locale.ROOT) })

        /** Trimmed; colors and maturities sorted by their string value, so the same filters always encode the same way. */
        fun of(name: String, filter: CellarFilter, id: UUID = UUID.randomUUID()) = SavedSearch(
            id = id, name = name.trim(), query = filter.query.trim(),
            colors = filter.colors.sortedBy { it.raw }, maturities = filter.maturities.sortedBy { it.raw },
            rackId = filter.rackId, tag = filter.tag, reservation = filter.reservation, grape = filter.grape,
        )
    }
}

/**
 * The Unicode White_Space characters, spelled out so both platforms use the same set
 * (Kotlin's `trim()` also strips U+001C–U+001F; CaveKit has `CharacterSet.unicodeWhiteSpace`).
 */
object WhiteSpace {
    /** As a regex character class body. */
    internal const val CHARACTERS = "\\u0009-\\u000D\\u0020\\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000"

    fun contains(char: Char): Boolean = when (char) {
        in '\u0009'..'\u000D', ' ', '\u0085', ' ', ' ', in ' '..' ',
        ' ', ' ', ' ', ' ', '　' -> true
        else -> false
    }

    fun trim(text: String): String = text.trim(::contains)
}

/**
 * Searches by words: each word of the query must appear, ignoring case and accents, in
 * one of the fields (not necessarily the same one). CaveKit does the same.
 */
object SearchText {
    private val spaces = Regex("[${WhiteSpace.CHARACTERS}]+")

    /** Split on Unicode White_Space, no-break spaces included, as CaveKit's `isWhitespace`. */
    fun words(query: String): List<String> = query.split(spaces).filter { it.isNotEmpty() }

    /** True for a blank query. */
    fun matches(query: String, fields: List<String>): Boolean {
        val words = words(query).map { Wine.fold(it) }
        if (words.isEmpty()) return true
        val folded = fields.map { Wine.fold(it) }
        return words.all { word -> folded.any { it.contains(word) } }
    }
}

/** Keeps the keys of a newer version (see [KeepingUnknownKeys]). */
object WineSerializer : KeepingUnknownKeys<Wine>(Wine.generatedSerializer()) {
    override fun unknownKeys(value: Wine) = value.unknownKeys
    override fun withUnknownKeys(value: Wine, keys: JsonObject) = value.copy(unknownKeys = keys)

    /** The details as CaveKit decodes them ([Wine.readNormalized]). */
    override fun deserialize(decoder: Decoder): Wine = super.deserialize(decoder).readNormalized()

    /** Only `true` is written for `farmingCertified`, and only with a farming. */
    override fun serialize(encoder: Encoder, value: Wine) =
        super.serialize(encoder, value.copy(farmingCertified = if (value.farming != null && value.farmingCertified == true) true else null))
}

/** Keeps the keys of a newer version (see [KeepingUnknownKeys]). */
object RackSerializer : KeepingUnknownKeys<Rack>(Rack.generatedSerializer(), legacyKeys = setOf("layout")) {
    override fun unknownKeys(value: Rack) = value.unknownKeys
    override fun withUnknownKeys(value: Rack, keys: JsonObject) = value.copy(unknownKeys = keys)
}

/** Keeps the keys of a newer version (see [KeepingUnknownKeys]). */
object TastingSerializer : KeepingUnknownKeys<Tasting>(Tasting.generatedSerializer()) {
    override fun unknownKeys(value: Tasting) = value.unknownKeys
    override fun withUnknownKeys(value: Tasting, keys: JsonObject) = value.copy(unknownKeys = keys)

    /** The newer fields as CaveKit decodes them ([Tasting.readNormalized]); the rating is read as written. */
    override fun deserialize(decoder: Decoder): Tasting = super.deserialize(decoder).readNormalized()

    /** A score is written only while it matches the rating ([Score.kept]). */
    override fun serialize(encoder: Encoder, value: Tasting) =
        super.serialize(encoder, value.copy(score100 = Score.kept(value.score100, value.rating)))
}

/** Keeps the keys of a newer version (see [KeepingUnknownKeys]). */
object BottleSerializer : KeepingUnknownKeys<Bottle>(Bottle.generatedSerializer()) {
    override fun unknownKeys(value: Bottle) = value.unknownKeys
    override fun withUnknownKeys(value: Bottle, keys: JsonObject) = value.copy(unknownKeys = keys)
}

/** Keeps the keys of a newer version (see [KeepingUnknownKeys]). */
object SavedSearchSerializer : KeepingUnknownKeys<SavedSearch>(SavedSearch.generatedSerializer()) {
    override fun unknownKeys(value: SavedSearch) = value.unknownKeys
    override fun withUnknownKeys(value: SavedSearch, keys: JsonObject) = value.copy(unknownKeys = keys)
}
