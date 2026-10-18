package app.encave.core

import app.encave.core.search.PriceExtractor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ImportError { NotAnEncaveBackup, EmptyFile, MissingNameColumn }

class ImportException(val error: ImportError) : Exception(error.name)

/** Result of an import, for the confirmation message. */
/** [discoveriesAdded]: rows of a tasting history (Vivino) added as discoveries. */
data class ImportSummary(val winesAdded: Int, val bottlesAdded: Int, val rowsSkipped: Int, val discoveriesAdded: Int = 0)

object CellarExport {
    val csvColumns = listOf(
        "name", "producer", "vintage", "color", "region", "country", "appellation", "grapes",
        "drink_from", "drink_until", "status", "rack", "slot", "storage",
        "purchase_date", "price", "currency", "purchase_place", "received_from", "size_ml",
        "rating", "tasted_on", "note", "expected_on", "tags", "reserved_for",
        "current_price", "current_price_currency", "current_price_date",
    )

    /** Pretty-printed JSON backup, readable by the Apple app. */
    fun json(cellar: Cellar, exportedAt: Instant = Instant.now()): String = CellarJson.backup(cellar, exportedAt)

    fun backupFromJson(text: String): CellarBackup = CellarJson.backupFrom(text)

    /**
     * One row per bottle. Includes consumed and gone bottles when [includeHistory] is true;
     * ordered ones always. Dates are days of [zone], the calendar day the user saw when entering them.
     */
    fun csv(cellar: Cellar, includeHistory: Boolean = true, zone: ZoneId = ZoneId.systemDefault()): String {
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
                rack?.name, bottle.slot?.let { rack?.label(it) }, bottle.storageNote,
                bottle.purchaseDate?.let(day), bottle.purchasePrice?.let(DecimalSerializer::plain), bottle.currency,
                bottle.purchasePlace, bottle.receivedFrom, bottle.sizeML.toString(),
                bottle.tasting?.rating?.toString(), bottle.tasting?.let { day(it.date) },
                bottle.tasting?.note, bottle.expectedAt?.let(day), wine.tags.orEmpty().joinToString("; "),
                bottle.reservedFor,
                wine.currentPrice?.let(DecimalSerializer::plain),
                if (wine.currentPrice == null) null else wine.currentPriceCurrency,
                if (wine.currentPrice == null) null else wine.currentPriceDate?.let(day),
            )
            lines += fields.joinToString(",") { escape(it ?: "") }
        }
        return lines.joinToString("\n") + "\n"
    }

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

/** Adds everything from a backup that is not already in this cellar (by id). */
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
            putBottle(if (slot != null && bottleAt(slot) != null) bottle.copy(slot = null) else bottle)
            bottleCount++
        }
    }
    for ((id, discovery) in other.discoveries) {
        if (discoveries[id] == null && wines[discovery.wineId] != null) putDiscovery(discovery)
    }
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
    // Dates ("2024-03-10") are days of [zone], as the export writes them.
    val day = { text: String -> CellarExport.parseDay(text, zone) }
    if ("scan_date" in columns && "quantity" !in columns) return importDiscoveries(rows, columns, nameIndex, now, day)
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
        var wine = CsvColumns.wine(name, ::value, day)
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
                purchasePrice = value("price")?.let(PriceExtractor::decimal),
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
                status == Bottle.Status.Consumed -> bottle.copy(
                    status = Bottle.Status.Consumed,
                    tasting = Tasting(
                        date = value("tasted_on")?.let(day) ?: now,
                        rating = value("rating")?.let(CsvColumns::rating), note = value("note"),
                    ).normalized(),
                )
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
    rows: List<List<String>>, columns: List<String>, nameIndex: Int, now: Instant, day: (String) -> Instant?,
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
        val discovery = Discovery(
            wineId = wine.id, date = value("scan_date")?.let(CsvColumns::date) ?: now, place = value("place"),
            rating = value("rating")?.let(CsvColumns::rating), note = value("note"),
            price = value("price")?.let(PriceExtractor::decimal),
            currency = value("currency") ?: settings.currency,
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
        "drinkingwindow" to "drinking_window",
        "quantity" to "quantity", "qty" to "quantity", "quantite" to "quantity", "totalquantity" to "quantity", "bottles" to "quantity",
        "price" to "price", "prix" to "price", "wineprice" to "price", "purchaseprice" to "price",
        "currency" to "currency", "devise" to "currency",
        "rack" to "rack", "location" to "rack", "casier" to "rack", "slot" to "slot", "bin" to "slot", "case" to "slot",
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

    fun wine(name: String, value: (String) -> String?, day: (String) -> Instant?): Wine {
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
        ).let { wine ->
            val price = value("current_price")?.let(PriceExtractor::decimal)?.takeIf { it.signum() > 0 } ?: return@let wine
            wine.copy(
                currentPrice = price,
                currentPriceCurrency = value("current_price_currency") ?: value("currency"),
                currentPriceDate = value("current_price_date")?.let(day),
            )
        }
    }

    /** "2015", "2015.0"; not "NV" or "1001" (CellarTracker writes 1001 for non-vintage). */
    fun year(text: String): Int? = number(text)?.let(::integer)?.takeIf { it in 1800..2200 }

    fun number(text: String): Double? = text.replace(",", ".").filter { it in '0'..'9' || it == '.' || it == '-' }.toDoubleOrNull()

    /** 1 to 5 (decimals rounded); 100-point scores are converted. */
    fun rating(text: String): Int? {
        val value = number(text)?.takeIf { it > 0 } ?: return null
        val five = minOf(5.0, if (value > 5) value / 20 else value)
        return Math.round(five).toInt().coerceIn(1, 5)
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

    fun date(text: String): Instant? {
        val zone = java.time.ZoneId.systemDefault()
        for (pattern in listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd", "dd/MM/yyyy", "MM/dd/yyyy")) {
            val formatter = DateTimeFormatter.ofPattern(pattern, Locale.ROOT)
            runCatching { return java.time.LocalDateTime.parse(text, formatter).atZone(zone).toInstant() }
            runCatching { return LocalDate.parse(text, formatter).atStartOfDay(zone).toInstant() }
        }
        return null
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
