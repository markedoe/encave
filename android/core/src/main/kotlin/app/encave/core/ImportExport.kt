package app.encave.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

enum class ImportError {
    NotAnEncaveBackup,

    /** Made by a newer version of Encave, in a format this one does not know. */
    NewerVersion,

    /** A ZIP file that is encrypted or split: not an archive Encave makes. */
    UnsupportedArchive,

    /** An archive whose cellar could not be read in full (cut short, or changed). */
    DamagedArchive,
    EmptyFile,
    MissingNameColumn,
}

class ImportException(val error: ImportError) : Exception(error.name)

/** Result of an import, for the confirmation message. */
/** [discoveriesAdded]: rows of a tasting history (Vivino) added as discoveries. */
data class ImportSummary(val winesAdded: Int, val bottlesAdded: Int, val rowsSkipped: Int, val discoveriesAdded: Int = 0)

object CellarExport {
    val csvColumns = listOf(
        "name", "producer", "vintage", "color", "region", "country", "appellation", "grapes",
        "drink_from", "drink_until", "status", "rack", "slot", "location", "storage",
        "purchase_date", "price", "currency", "purchase_place", "received_from", "size_ml",
        "rating", "tasted_on", "note", "expected_on", "tags", "reserved_for",
        "current_price", "current_price_currency", "current_price_date",
        "score", "abv", "classification", "critic_scores",
    )

    /** Pretty-printed JSON backup, readable by the Apple app. */
    fun json(cellar: Cellar, exportedAt: Instant = Instant.now()): String = CellarJson.backup(cellar, exportedAt)

    fun backupFromJson(text: String): CellarBackup = CellarJson.backupFrom(text)

    /**
     * One row per bottle. Includes consumed and gone bottles when [includeHistory] is true;
     * ordered ones always. Dates are days of [zone], the calendar day the user saw when entering them.
     * Next to the technical "slot" ("B3-2"), "location" names the position for people,
     * "Cabinet · Shelf 1, position 6": [placeName] gives it in the person's language (English
     * when null). The import reads the "rack" column before it, so "location" is ignored there.
     */
    fun csv(
        cellar: Cellar,
        includeHistory: Boolean = true,
        zone: ZoneId = ZoneId.systemDefault(),
        placeName: ((Rack, Slot) -> String)? = null,
    ): String {
        val day = { instant: Instant -> day(instant, zone) }
        val collator = Cellar.collator()
        val bottles = cellar.bottles.values
            .filter { includeHistory || it.status == Bottle.Status.InCellar || it.status == Bottle.Status.Ordered }
            .sortedWith { a, b ->
                val wa = cellar.wines[a.wineId]
                val wb = cellar.wines[b.wineId]
                if (wa != null && wb != null && wa.id != wb.id) Cellar.nameOrder(wa, wb, collator) else a.addedAt.compareTo(b.addedAt)
            }
        val lines = mutableListOf(csvColumns.joinToString(","))
        for (bottle in bottles) {
            val wine = cellar.wines[bottle.wineId] ?: continue
            val rack = bottle.slot?.let { cellar.rack(it.rackId) }
            val fields = listOf(
                wine.name, wine.producer, wine.vintage?.toString(), wine.color?.raw,
                wine.region, wine.country, wine.appellation, wine.grapes.joinToString("; "),
                wine.drinkFrom?.toString(), wine.drinkUntil?.toString(), bottle.status.raw,
                rack?.name, bottle.slot?.let { rack?.label(it) },
                bottle.slot?.let { slot -> rack?.let { "${it.name} · ${placeName?.invoke(it, slot) ?: it.placeName(slot)}" } },
                bottle.storageNote,
                bottle.purchaseDate?.let(day), bottle.purchasePrice?.let(::price), bottle.currency,
                bottle.purchasePlace, bottle.receivedFrom, bottle.sizeML.toString(),
                bottle.tasting?.rating?.toString(), bottle.tasting?.let { day(it.date) },
                bottle.tasting?.note, bottle.expectedAt?.let(day), wine.tags.orEmpty().joinToString("; "),
                bottle.reservedFor,
                wine.currentPrice?.let(::price),
                if (wine.currentPrice == null) null else wine.currentPriceCurrency,
                if (wine.currentPrice == null) null else wine.currentPriceDate?.let(day),
                bottle.tasting?.score100?.let(CsvColumns::scoreText), wine.abv?.let(DecimalSerializer::plain), wine.classification,
                wine.criticScores?.let(CsvColumns::criticScoresText),
            )
            lines += fields.joinToString(",") { escape(it ?: "") }
        }
        return lines.joinToString("\n") + "\n"
    }

    /** A price rounded to the cent, as the CSV export writes and reads it ("8.333" is 8.33). */
    fun roundedPrice(value: BigDecimal): BigDecimal = value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros()

    /** "8.33", "120.5", "18". */
    internal fun price(value: BigDecimal): String = DecimalSerializer.plain(roundedPrice(value))

    internal fun escape(text: String): String {
        // A spreadsheet runs a cell that starts with "=", "+", "-" or "@" as a formula: such
        // text (from any device of the cellar) is written after a quote mark, which the
        // import removes. Encave writes no negative numbers.
        val field = if (text.firstOrNull()?.let { it in "=+-@" } == true) "'$text" else text
        if (field.none { it == ',' || it == '"' || it == '\n' || it == '\r' || it == ';' }) return field
        return "\"" + field.replace("\"", "\"\"") + "\""
    }

    private val dayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)

    /** The day of [instant] in [zone], "2024-03-10". */
    internal fun day(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String = dayFormatter.format(instant.atZone(zone))

    /** The start of the day in [zone]. */
    internal fun parseDay(text: String, zone: ZoneId = ZoneId.systemDefault()): Instant? = try {
        LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(zone).toInstant()
    } catch (_: Exception) {
        null
    }
}

/**
 * Adds everything from a backup that is not already in this cellar (by id), and the saved
 * searches it does not have (by id and name). A bottle whose place is taken, or no longer in
 * its rack (made smaller since), is added without a place.
 */
fun Cellar.merge(other: Cellar): ImportSummary {
    var wineCount = 0
    var bottleCount = 0
    for (newRack in other.racks) if (rack(newRack.id) == null) appendRack(newRack)
    for ((id, wine) in other.wines) {
        if (wines[id] == null) {
            putWine(wine)
            wineCount++
        }
    }
    for ((id, bottle) in other.bottles) {
        if (bottles[id] == null) {
            val slot = bottle.slot
            val placeable = slot == null || (bottleAt(slot) == null && rack(slot.rackId)?.contains(slot) == true)
            putBottle(if (placeable) bottle else bottle.copy(slot = null))
            bottleCount++
        }
    }
    for ((id, discovery) in other.discoveries) {
        if (discoveries[id] == null && wines[discovery.wineId] != null) putDiscovery(discovery)
    }
    val searches = settings.savedSearches.orEmpty()
    val ids = searches.map { it.id }.toSet()
    val names = searches.map { it.name.lowercase(java.util.Locale.ROOT) }.toSet()
    val missing = other.settings.savedSearches.orEmpty().filter { it.id !in ids && it.name.lowercase(java.util.Locale.ROOT) !in names }
    if (missing.isNotEmpty()) settings = settings.copy(savedSearches = SavedSearch.ordered(searches + missing))
    return ImportSummary(wineCount, bottleCount, 0)
}

/**
 * Imports a CSV: Encave's own export, or a spreadsheet or another app's export
 * (CellarTracker, Vivino…). Columns are recognized by name in English or French, in any
 * order; only a wine name is required. A quantity column creates that many bottles per
 * row. A Vivino scan history (a "scan date" column and no quantity) becomes discoveries.
 */
fun Cellar.importCsv(text: String, now: Instant = ReferenceDates.now(), zone: ZoneId = ZoneId.systemDefault()): ImportSummary {
    val rows = CsvParser.parse(text)
    val header = rows.firstOrNull() ?: throw ImportException(ImportError.EmptyFile)
    val columns = header.map(CsvColumns::canonical)
    val nameIndex = columns.indexOf("name")
    if (nameIndex < 0) throw ImportException(ImportError.MissingNameColumn)
    // Dates ("2024-03-10") are days of [zone], as the export writes them; other apps' dates
    // (with a time, or "10/03/2024") are read too.
    val day = { text: String -> CellarExport.parseDay(text, zone) ?: CsvColumns.date(text, zone) }
    if ("scan_date" in columns && "quantity" !in columns) return importDiscoveries(rows, columns, nameIndex, now, zone, day)
    // Encave's own columns ("price", not "Prix") hold plain numbers: "8.333" is not 8,333.
    fun own(column: String): Boolean = columns.indexOf(column).let { it >= 0 && header[it].trim() == column }
    val plainPrice = own("price")
    val plainCurrentPrice = own("current_price")
    val winesByKey = HashMap<String, Wine>()
    wines.values.forEach { winesByKey.putIfAbsent(it.identityKey, it) }
    var winesAdded = 0
    var bottlesAdded = 0
    var rowsSkipped = 0

    for (row in rows.drop(1)) {
        fun value(column: String): String? {
            val index = columns.indexOf(column)
            if (index < 0 || index >= row.size) return null
            return CsvColumns.cleaned(row[index])
        }
        val name = if (nameIndex < row.size) value("name") else null
        if (name == null) {
            if (row.any { it.isNotBlank() }) rowsSkipped++
            continue
        }
        var wine = CsvColumns.wine(name, ::value, day, plainCurrentPrice)
        val existing = winesByKey[wine.identityKey]
        if (existing != null) {
            wine = existing
        } else {
            putWine(wine)
            winesByKey[wine.identityKey] = wine
            winesAdded++
        }

        val quantity = CsvColumns.quantity(value("quantity"))
        repeat(quantity) {
            var bottle = Bottle(
                wineId = wine.id, storageNote = value("storage"), addedAt = now,
                purchaseDate = value("purchase_date")?.let(day),
                purchasePrice = value("price")?.let { CsvColumns.price(it, plainPrice) },
                currency = value("currency") ?: settings.currency, purchasePlace = value("purchase_place"),
                receivedFrom = value("received_from"), sizeML = value("size_ml")?.let(CsvColumns::size) ?: 750,
                reservedFor = value("reserved_for"),
            )
            val status = value("status")?.let { Bottle.Status.fromRaw(it) } ?: Bottle.Status.InCellar
            val rackName = value("rack")
            val label = value("slot")
            val rack = rackName?.let { name -> racks.firstOrNull { it.name.equals(name, ignoreCase = true) } }
            val wanted = if (label != null) rack?.slotForLabel(label) else null
            // A label already taken (several bottles listed in one cell) uses the next free place.
            val slot = wanted?.let { if (bottleAt(it) == null) it else freeSlot(it.cell) }
            bottle = when {
                status == Bottle.Status.Consumed -> {
                    val score = value("score")?.let(CsvColumns::score) ?: value("rating")?.let(CsvColumns::score)
                    bottle.copy(
                        status = Bottle.Status.Consumed,
                        tasting = Tasting(
                            date = value("tasted_on")?.let(day) ?: now,
                            rating = score?.rating, note = value("note"), score100 = score?.score100,
                        ).normalized(),
                    )
                }
                status == Bottle.Status.Gone -> bottle.copy(status = Bottle.Status.Gone)
                // An ordered bottle has no place yet.
                status == Bottle.Status.Ordered -> bottle.copy(
                    status = Bottle.Status.Ordered, expectedAt = value("expected_on")?.let(day),
                )
                slot != null -> bottle.copy(slot = slot)
                rackName != null -> bottle.copy(
                    storageNote = bottle.storageNote ?: listOfNotNull(rackName, label).joinToString(" · "),
                )
                else -> bottle
            }
            putBottle(bottle)
            bottlesAdded++
        }
    }
    return ImportSummary(winesAdded, bottlesAdded, rowsSkipped)
}

/** Rows of a tasting history (Vivino): each becomes a discovery of the wine. */
internal fun Cellar.importDiscoveries(
    rows: List<List<String>>, columns: List<String>, nameIndex: Int, now: Instant, zone: ZoneId, day: (String) -> Instant?,
): ImportSummary {
    var winesAdded = 0
    var rowsSkipped = 0
    var added = 0
    for (row in rows.drop(1)) {
        fun value(column: String): String? {
            val index = columns.indexOf(column)
            if (index < 0 || index >= row.size) return null
            return CsvColumns.cleaned(row[index])
        }
        val name = if (nameIndex < row.size) value("name") else null
        if (name == null) {
            if (row.any { it.isNotBlank() }) rowsSkipped++
            continue
        }
        val wine = CsvColumns.wine(name, ::value, day)
        val known = wines.values.any { it.identityKey == wine.identityKey }
        val score = value("score")?.let(CsvColumns::score) ?: value("rating")?.let(CsvColumns::score)
        val discovery = Discovery(
            wineId = wine.id, date = value("scan_date")?.let { CsvColumns.date(it, zone) } ?: now, place = value("place"),
            rating = score?.rating, note = value("note"),
            price = value("price")?.let { CsvColumns.price(it, plain = false) },
            currency = value("currency") ?: settings.currency, score100 = score?.score100,
        )
        addDiscovery(discovery, wine)
        added++
        if (!known) winesAdded++
    }
    return ImportSummary(winesAdded, 0, rowsSkipped, added)
}

/** Column names and values of other apps' and spreadsheets' exports. */
internal object CsvColumns {
    /** Header (folded: lowercase letters and digits, no accents) → the name used by Encave's own export. */
    val synonyms: Map<String, String> = mapOf(
        "name" to "name", "wine" to "name", "winename" to "name", "nom" to "name", "vin" to "name", "cuvee" to "name",
        "producer" to "producer", "winery" to "producer", "domaine" to "producer", "producteur" to "producer", "estate" to "producer",
        "vintage" to "vintage", "millesime" to "vintage", "year" to "vintage", "annee" to "vintage",
        "color" to "color", "colour" to "color", "couleur" to "color", "type" to "color", "winetype" to "color",
        "region" to "region", "country" to "country", "pays" to "country", "appellation" to "appellation",
        "grapes" to "grapes", "varietal" to "grapes", "grape" to "grapes", "cepage" to "grapes", "cepages" to "grapes",
        "drinkfrom" to "drink_from", "beginconsume" to "drink_from", "boirea" to "drink_from",
        "drinkuntil" to "drink_until", "endconsume" to "drink_until", "boirejusqua" to "drink_until",
        "drinkingwindow" to "drinking_window", "fenetrededegustation" to "drinking_window",
        "quantity" to "quantity", "qty" to "quantity", "quantite" to "quantity", "totalquantity" to "quantity", "bottles" to "quantity",
        "price" to "price", "prix" to "price", "wineprice" to "price", "purchaseprice" to "price",
        "currency" to "currency", "devise" to "currency",
        "rack" to "rack", "location" to "rack", "casier" to "rack", "emplacement" to "rack", "slot" to "slot", "bin" to "slot", "case" to "slot",
        "storage" to "storage", "status" to "status", "purchasedate" to "purchase_date", "purchaseplace" to "purchase_place",
        "receivedfrom" to "received_from", "sizeml" to "size_ml", "tastedon" to "tasted_on",
        "rating" to "rating", "yourrating" to "rating", "note" to "note", "notes" to "note", "yourreview" to "note",
        "personalnote" to "note", "commentaire" to "note",
        "currentprice" to "current_price", "currentvalue" to "current_price", "prixactuel" to "current_price",
        "valeuractuelle" to "current_price", "currentpricecurrency" to "current_price_currency",
        "currentpricedate" to "current_price_date",
        "tags" to "tags", "tag" to "tags", "reservedfor" to "reserved_for", "reserved" to "reserved_for",
        "expectedon" to "expected_on", "deliverydate" to "expected_on", "livraison" to "expected_on",
        "scandate" to "scan_date", "scanreviewlocation" to "place", "place" to "place", "lieu" to "place",
        "score" to "score", "abv" to "abv", "alcohol" to "abv", "alcool" to "abv", "degre" to "abv",
        "classification" to "classification", "classement" to "classification",
        "criticscores" to "critic_scores", "notesdescritiques" to "critic_scores",
    )

    private fun fold(text: String): String = Wine.fold(text)

    /** "Wine Name", "wine_name" and "Millésime" become "name" and "vintage". */
    fun canonical(header: String): String {
        val folded = fold(header).filter { it.isLetterOrDigit() }
        return synonyms[folded] ?: header.trim().lowercase(Locale.ROOT).replace(" ", "_")
    }

    /**
     * A cell without surrounding spaces, and without the quote mark the export puts before
     * text a spreadsheet would run as a formula; null if empty.
     */
    fun cleaned(cell: String): String? {
        var text = cell.trim()
        if (text.length > 1 && text[0] == '\'' && text[1] in "=+-@") text = text.substring(1)
        return text.ifEmpty { null }
    }

    /** Bottles a row creates: 1 to [MAX_QUANTITY] (a number larger than that is a mistake, not bottles). */
    const val MAX_QUANTITY = 999

    fun quantity(text: String?): Int {
        val value = text?.let(::number)?.takeUnless { it.isNaN() } ?: return 1
        return integer(value.coerceIn(1.0, MAX_QUANTITY.toDouble())) ?: 1
    }

    /** "750", "750.0"; null unless positive. */
    fun size(text: String): Int? = number(text)?.let(::integer)?.takeIf { it in 1..100_000 }

    /** The nearest integer, or null if there is none (NaN, infinite, too large), as Swift's `Int(exactly:)`. */
    fun integer(value: Double): Int? {
        if (value.isNaN() || value.isInfinite()) return null
        // Swift's `rounded()`: to the nearest, halves away from zero.
        val nearest = if (value < 0) -Math.floor(-value + 0.5) else Math.floor(value + 0.5)
        return if (nearest >= Int.MIN_VALUE && nearest <= Int.MAX_VALUE) nearest.toInt() else null
    }

    fun wine(name: String, value: (String) -> String?, day: (String) -> Instant?, plainPrice: Boolean = false): Wine {
        var from = value("drink_from")?.let(::year)
        var until = value("drink_until")?.let(::year)
        if (from == null && until == null) {
            value("drinking_window")?.let { window ->
                val years = window.split(Regex("[^0-9]+")).mapNotNull { it.toIntOrNull() }.filter { it in 1900..2200 }
                from = years.getOrNull(0)
                until = years.getOrNull(1)
            }
        }
        return Wine(
            name = name, producer = value("producer"), vintage = value("vintage")?.let(::year),
            color = value("color")?.let(::color), region = value("region"),
            country = value("country"), appellation = value("appellation"),
            grapes = (value("grapes") ?: "").split(';', ',').map { it.trim() }.filter { it.isNotEmpty() },
            drinkFrom = from, drinkUntil = until,
            tags = Wine.normalizedTags(value("tags")?.split(';', ',')),
            abv = value("abv")?.let(::abv), classification = value("classification"),
            criticScores = value("critic_scores")?.let(::criticScores),
        ).let { wine ->
            val price = value("current_price")?.let { CsvColumns.price(it, plainPrice) }?.takeIf { it.signum() > 0 } ?: return@let wine
            wine.copy(
                currentPrice = price,
                currentPriceCurrency = value("current_price_currency") ?: value("currency"),
                currentPriceDate = value("current_price_date")?.let(day),
            )
        }
    }

    private val plainNumber = Regex("[0-9]+(\\.[0-9]+)?")

    /** The prices read in an import: more than 0, at most this. */
    private val maxPrice = BigDecimal(100_000)

    /**
     * A price rounded to the cent, null outside 0 (excluded) to [maxPrice]. In Encave's own
     * columns ([plain]), "8.333" is 8.33; otherwise [amount] reads it.
     */
    fun price(text: String, plain: Boolean): BigDecimal? {
        val value = (if (plain && plainNumber.matches(text)) BigDecimal(text) else amount(text)) ?: return null
        if (value.signum() <= 0 || value > maxPrice) return null
        return CellarExport.roundedPrice(value)
    }

    /**
     * The number from the first digit to the last, a currency or words around it ("12,50 €",
     * "CHF 13.–"). Spaces and apostrophes group thousands. One separator before exactly three
     * digits groups thousands ("1.234", "8,333"); before 1, 2 or 4 or more digits it starts the
     * decimals ("45.0000"). With "." and ",", the last one starts the decimals ("1,234.50").
     * Null for a negative number or text between the digits ("12 to 15").
     */
    internal fun amount(text: String): BigDecimal? {
        val first = text.indexOfFirst { it in '0'..'9' }
        val last = text.indexOfLast { it in '0'..'9' }
        if (first < 0) return null
        if (first > 0 && text[first - 1] in "-−") return null
        val digits = StringBuilder()
        // Each separator, with the count of digits before it.
        val marks = mutableListOf<Pair<Int, Char>>()
        for (character in text.substring(first, last + 1)) {
            when {
                character in '0'..'9' -> digits.append(character)
                character == '.' || character == ',' -> {
                    if (marks.lastOrNull()?.first == digits.length) return null
                    marks += digits.length to character
                }
                character !in " \u00A0\u202F'’" -> return null
            }
        }
        var decimals = 0
        val lastMark = marks.lastOrNull()
        if (lastMark != null) {
            val tail = digits.length - lastMark.first
            when {
                marks.any { it.second != lastMark.second } -> decimals = tail
                marks.size == 1 -> decimals = if (tail == 3) 0 else tail
                // "1.234.567": every group has three digits.
                else -> {
                    val ends = marks.drop(1).map { it.first } + digits.length
                    if (marks.zip(ends).any { (mark, end) -> end - mark.first != 3 }) return null
                }
            }
        }
        val integer = digits.substring(0, digits.length - decimals)
        val fraction = digits.substring(digits.length - decimals)
        return BigDecimal(if (fraction.isEmpty()) integer else "${integer.ifEmpty { "0" }}.$fraction")
    }

    /** "2015", "2015.0"; not "NV" or "1001" (CellarTracker writes 1001 for non-vintage). */
    fun year(text: String): Int? = number(text)?.let(::integer)?.takeIf { it in 1800..2200 }

    fun number(text: String): Double? = text.replace(",", ".").filter { it in '0'..'9' || it == '.' || it == '-' }.toDoubleOrNull()

    /** 1 to 5 (decimals rounded), as [score] reads it. */
    fun rating(text: String): Int? = score(text)?.rating

    /**
     * A rating and, for a score out of 100 or 20, the precise score, by the shared conversion
     * ([Score]), as CaveKit reads it. A bare number above 5 is out of 100: from 50 to 100 it is
     * kept as a score, below it is that share of 5 stars. "93/100" (50 to 100) and "17.5/20"
     * are scores; other "x/y" ("4/5", "8/10") are that share of 5 stars, without a score.
     */
    fun score(text: String): EnteredRating? {
        fun share(five: Double) = EnteredRating(Math.round(minOf(5.0, five)).toInt().coerceIn(1, 5), null)
        val parts = text.split('/')
        if (parts.size == 2) {
            val value = number(parts[0])?.takeIf { it > 0 } ?: return null
            val scale = number(parts[1])?.takeIf { it > 0 } ?: return null
            if (scale == 100.0 && value >= Score.LOWEST && value <= Score.HIGHEST) return Score.entered(value, RatingScale.Hundred)
            if (scale == 20.0 && value <= 20) return Score.entered(value, RatingScale.Twenty)
            return share(value / scale * 5)
        }
        val value = number(text)?.takeIf { it > 0 } ?: return null
        if (value <= 5) return share(value)
        if (value >= Score.LOWEST && value <= Score.HIGHEST) return Score.entered(value, RatingScale.Hundred)
        return share(value / 20)
    }

    /** A score out of 100 as the export writes it: "90", "93.75". */
    fun scoreText(score: Double): String = if (score == Math.floor(score)) score.toLong().toString() else score.toString()

    /** "Guide Hachette: 17.5/20 (2015); Concours de Mâcon: Gold medal", as the export writes critics' scores. */
    fun criticScoresText(scores: List<CriticScore>): String = scores.joinToString("; ") { item ->
        val note = item.note?.let { if (it.isEmpty()) "" else " ($it)" } ?: ""
        "${item.source}: ${item.value ?: ""}$note"
    }

    /**
     * The critics' scores of an export: entries separated by ";", each "source: value", the
     * value "score/scale", a bare score or an award, with an optional note in brackets at the end.
     * A ";" in a source, award or note, or a ":" in a source, cannot be told apart from the separators.
     */
    fun criticScores(text: String): List<CriticScore>? = text.split(';').filter { it.isNotEmpty() }.mapNotNull { entry ->
        val colon = entry.indexOf(':')
        val source = (if (colon < 0) entry else entry.substring(0, colon)).trim(' ')
        if (source.isEmpty()) return@mapNotNull null
        var rest = if (colon < 0) "" else entry.substring(colon + 1).trim(' ')
        var note: String? = null
        // The note starts at the first " (" (a note may hold brackets of its own).
        val open = rest.indexOf(" (")
        if (rest.endsWith(")") && open >= 0) {
            note = rest.substring(open + 2, rest.length - 1)
            rest = rest.substring(0, open).trim(' ')
        } else if (rest.startsWith("(") && rest.endsWith(")")) {
            note = rest.substring(1, rest.length - 1)
            rest = ""
        }
        val fraction = rest.split('/')
        val scale = if (fraction.size == 2) fraction[1].trim(' ').let { if (it.all { c -> c in '0'..'9' }) it.toIntOrNull() else null } else null
        val score = if (fraction.size == 2) decimal(fraction[0]) else null
        val bare = decimal(rest)
        when {
            scale != null && score != null -> CriticScore(source, score = score, scale = scale, note = note)
            // A bare number is a score without a scale, as the export writes it.
            bare != null -> CriticScore(source, score = bare, note = note)
            else -> CriticScore(source, award = rest.ifEmpty { null }, note = note)
        }
    }.ifEmpty { null }

    private val decimalNumber = Regex("[0-9]+(\\.[0-9]+)?")

    /** "17.5", "17,5" or "94" as a decimal; null unless only digits and one separator. */
    fun decimal(text: String): BigDecimal? {
        val trimmed = text.trim(' ').replace(",", ".")
        return if (decimalNumber.matches(trimmed)) BigDecimal(trimmed).stripTrailingZeros() else null
    }

    /** Alcohol by volume: "13.5", "13,5 %", "12.5% vol"; null outside 0 (excluded) to 100. */
    fun abv(text: String): BigDecimal? {
        val digits = text.replace(",", ".").filter { it in '0'..'9' || it == '.' }
        return decimal(digits)?.takeIf { it.signum() > 0 && it <= BigDecimal(100) }
    }

    fun color(text: String): WineColor? {
        val folded = fold(text)
        val table = listOf(
            "sparkling" to WineColor.Sparkling, "effervescent" to WineColor.Sparkling, "champagne" to WineColor.Sparkling,
            "petillant" to WineColor.Sparkling, "rose" to WineColor.Rose, "dessert" to WineColor.Dessert,
            "sweet" to WineColor.Dessert, "liquoreux" to WineColor.Dessert, "moelleux" to WineColor.Dessert,
            "fortified" to WineColor.Fortified, "port" to WineColor.Fortified, "porto" to WineColor.Fortified,
            "fortifie" to WineColor.Fortified, "orange" to WineColor.Orange, "red" to WineColor.Red, "rouge" to WineColor.Red,
            "white" to WineColor.White, "blanc" to WineColor.White,
        )
        return table.firstOrNull { folded.contains(it.first) }?.second
    }

    private fun strict(pattern: String) = DateTimeFormatter.ofPattern(pattern, Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)
    private val dateTimeFormat = strict("uuuu-M-d H:mm:ss")
    private val dateFormats = listOf("uuuu-M-d", "d/M/uuuu", "M/d/uuuu").map(::strict)

    /**
     * Other apps' dates in [zone]: "2025-07-14 19:30:00", "2024-3-5", "10/03/2024" (day first,
     * else month first); null for a day that does not exist ("2024-02-30").
     */
    fun date(text: String, zone: ZoneId): Instant? {
        runCatching { return LocalDateTime.parse(text, dateTimeFormat).atZone(zone).toInstant() }
        for (formatter in dateFormats) runCatching { return LocalDate.parse(text, formatter).atStartOfDay(zone).toInstant() }
        return null
    }
}

/**
 * The text of a CSV file: UTF-8 with or without a byte order mark, UTF-16 with one, else
 * Windows-1252 (French Excel's "CSV (point-virgule)", CellarTracker). The five bytes
 * Windows-1252 leaves undefined (0x81, 0x8D, 0x8F, 0x90, 0x9D) become the same code point,
 * as in Latin-1. A byte order mark is removed.
 */
object CsvText {
    fun decode(bytes: ByteArray): String {
        fun startsWith(vararg prefix: Int) = bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it].toByte() }
        if (startsWith(0xFF, 0xFE)) return utf16(bytes, littleEndian = true)
        if (startsWith(0xFE, 0xFF)) return utf16(bytes, littleEndian = false)
        val start = if (startsWith(0xEF, 0xBB, 0xBF)) 3 else 0
        return utf8(bytes, start) ?: windows1252(bytes, start)
    }

    private fun utf8(bytes: ByteArray, start: Int): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, start, bytes.size - start)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** After the byte order mark; an unpaired surrogate or a last odd byte is U+FFFD. */
    private fun utf16(bytes: ByteArray, littleEndian: Boolean): String {
        val count = (bytes.size - 2) / 2
        fun unit(index: Int): Int {
            val first = bytes[2 + 2 * index].toInt() and 0xFF
            val second = bytes[3 + 2 * index].toInt() and 0xFF
            return if (littleEndian) first or (second shl 8) else (first shl 8) or second
        }
        val text = StringBuilder(count)
        var index = 0
        while (index < count) {
            val unit = unit(index)
            if (unit in 0xD800..0xDBFF && index + 1 < count && unit(index + 1) in 0xDC00..0xDFFF) {
                text.append(unit.toChar()).append(unit(index + 1).toChar())
                index += 2
            } else {
                text.append(if (unit in 0xD800..0xDFFF) '\uFFFD' else unit.toChar())
                index++
            }
        }
        if ((bytes.size - 2) % 2 == 1) text.append('\uFFFD')
        return text.toString()
    }

    /** Code points of 0x80 to 0x9F; the other bytes are their own code point. */
    private val high = intArrayOf(
        0x20AC, 0x0081, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021, 0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0x008D, 0x017D, 0x008F,
        0x0090, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014, 0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0x009D, 0x017E, 0x0178,
    )

    private fun windows1252(bytes: ByteArray, start: Int): String {
        val text = StringBuilder(bytes.size - start)
        for (index in start until bytes.size) {
            val byte = bytes[index].toInt() and 0xFF
            text.append((if (byte in 0x80..0x9F) high[byte - 0x80] else byte).toChar())
        }
        return text.toString()
    }
}

/** Minimal RFC 4180 CSV parser. Accepts "," or ";" separators (auto-detected). */
object CsvParser {
    fun parse(text: String): List<List<String>> {
        val body = text.removePrefix("﻿")
        val firstLine = body.takeWhile { it != '\n' && it != '\r' }
        val separator = if (firstLine.count { it == ';' } > firstLine.count { it == ',' }) ';' else ','
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < body.length) {
            val char = body[index]
            if (inQuotes) {
                if (char == '"') {
                    if (index + 1 < body.length && body[index + 1] == '"') {
                        field.append('"')
                        index++
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(char)
                }
            } else if (char == '"') {
                inQuotes = true
            } else if (char == separator) {
                row += field.toString()
                field.clear()
            } else if (char == '\n' || char == '\r') {
                row += field.toString()
                rows += row
                row = mutableListOf()
                field.clear()
                if (char == '\r' && index + 1 < body.length && body[index + 1] == '\n') index++
            } else {
                field.append(char)
            }
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            rows += row
        }
        return rows.filter { !(it.size == 1 && it[0].isEmpty()) }
    }
}
