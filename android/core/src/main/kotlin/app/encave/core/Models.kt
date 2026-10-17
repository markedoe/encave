package app.encave.core

import java.math.BigDecimal
import java.text.Normalizer
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.SerialName
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
    @Serializable(with = UuidSerializer::class) val id: UUID = UUID.randomUUID(),
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
) {
    /** "Château X 2015" or "Château X" for non-vintage wines. */
    val displayName: String get() = if (vintage != null) "$name $vintage" else name

    /** Same wine label, used to merge duplicates on import ("same name, producer, vintage"). */
    val identityKey: String
        get() = listOf(name, producer ?: "", vintage?.toString() ?: "")
            .joinToString("|") { it.trim().lowercase(Locale.ROOT) }

    /** Case- and accent-insensitive search over the label fields and grapes. */
    fun matches(needle: String): Boolean {
        val folded = fold(needle)
        val fields = listOfNotNull(name, producer, region, country, appellation, vintage?.toString()) + grapes
        return fields.any { fold(it).contains(folded) }
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

    internal companion object {
        private val marks = Regex("\\p{Mn}+")
        fun fold(text: String): String =
            marks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)
    }
}

/** Where a bottle sits: rack, row and column (zero-based). */
@Serializable
data class Slot(
    @SerialName("rackID") @Serializable(with = UuidSerializer::class) val rackId: UUID,
    val row: Int,
    val column: Int,
)

/** How the positions of a rack are drawn. The slot grid is the same; only the shape differs. */
@Serializable
enum class RackLayout(val raw: String) {
    /** Square cells in rows and columns. */
    @SerialName("grid") Grid("grid"),

    /** Diamond (X-shaped) bins, rows offset like a brick wall. */
    @SerialName("diamond") Diamond("diamond"),
}

/** A physical rack, modeled as a grid. Rows are lettered, columns numbered. */
@Serializable
data class Rack(
    @Serializable(with = UuidSerializer::class) val id: UUID = UUID.randomUUID(),
    val name: String,
    val rows: Int,
    val columns: Int,
    val layout: RackLayout = RackLayout.Grid,
) {
    val capacity: Int get() = rows * columns

    /** "B3" for row 1, column 2. */
    fun label(row: Int, column: Int): String = "${rowLetters(row)}${column + 1}"

    fun label(slot: Slot): String = label(slot.row, slot.column)

    /** Parses "B3" back into a slot of this rack. */
    fun slotForLabel(label: String): Slot? {
        val upper = label.trim().uppercase(Locale.ROOT)
        val letters = upper.takeWhile { it.isLetter() }
        if (letters.isEmpty()) return null
        val column = upper.drop(letters.length).toIntOrNull() ?: return null
        var row = 0
        for (char in letters) {
            if (char !in 'A'..'Z') return null
            row = row * 26 + (char - 'A' + 1)
        }
        val slot = Slot(id, row - 1, column - 1)
        if (slot.row !in 0 until rows || slot.column !in 0 until columns) return null
        return slot
    }

    /** Rows and columns are at least 1, as in the Swift initializer. */
    fun normalized(): Rack =
        if (rows >= 1 && columns >= 1) this else copy(rows = rows.coerceAtLeast(1), columns = columns.coerceAtLeast(1))

    companion object {
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
    @Serializable(with = InstantSerializer::class) val date: Instant = Instant.now(),
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
    @Serializable(with = UuidSerializer::class) val id: UUID = UUID.randomUUID(),
    @SerialName("wineID") @Serializable(with = UuidSerializer::class) val wineId: UUID,
    val status: Status = Status.InCellar,
    val slot: Slot? = null,
    /** Where the bottle is when it is not in a rack ("Kitchen", "Box in garage"). */
    val storageNote: String? = null,
    @Serializable(with = InstantSerializer::class) val addedAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class) val purchaseDate: Instant? = null,
    @Serializable(with = DecimalSerializer::class) val purchasePrice: BigDecimal? = null,
    val currency: String? = null,
    val purchasePlace: String? = null,
    val receivedFrom: String? = null,
    /** Bottle size in millilitres (750 standard, 1500 magnum...). */
    val sizeML: Int = 750,
    val tasting: Tasting? = null,
) {
    @Serializable
    enum class Status(val raw: String) {
        @SerialName("inCellar") InCellar("inCellar"),
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
    val currency: String = "EUR",
)
