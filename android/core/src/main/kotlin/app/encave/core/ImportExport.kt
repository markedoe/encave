package app.encave.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ImportError { NotAnEncaveBackup, EmptyFile, MissingNameColumn }

class ImportException(val error: ImportError) : Exception(error.name)

/** Result of an import, for the confirmation message. */
data class ImportSummary(val winesAdded: Int, val bottlesAdded: Int, val rowsSkipped: Int)

object CellarExport {
    val csvColumns = listOf(
        "name", "producer", "vintage", "color", "region", "country", "appellation", "grapes",
        "drink_from", "drink_until", "status", "rack", "slot", "storage",
        "purchase_date", "price", "currency", "purchase_place", "received_from", "size_ml",
        "rating", "tasted_on", "note", "expected_on", "tags",
    )

    /** Pretty-printed JSON backup, readable by the Apple app. */
    fun json(cellar: Cellar, exportedAt: Instant = Instant.now()): String = CellarJson.backup(cellar, exportedAt)

    fun backupFromJson(text: String): CellarBackup = CellarJson.backupFrom(text)

    /** One row per bottle. Includes consumed and gone bottles when [includeHistory] is true; ordered ones always. */
    fun csv(cellar: Cellar, includeHistory: Boolean = true): String {
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
                bottle.purchaseDate?.let(::day), bottle.purchasePrice?.let(DecimalSerializer::plain), bottle.currency,
                bottle.purchasePlace, bottle.receivedFrom, bottle.sizeML.toString(),
                bottle.tasting?.rating?.toString(), bottle.tasting?.let { day(it.date) },
                bottle.tasting?.note, bottle.expectedAt?.let(::day), wine.tags.orEmpty().joinToString("; "),
            )
            lines += fields.joinToString(",") { escape(it ?: "") }
        }
        return lines.joinToString("\n") + "\n"
    }

    internal fun escape(field: String): String {
        if (field.none { it == ',' || it == '"' || it == '\n' || it == '\r' || it == ';' }) return field
        return "\"" + field.replace("\"", "\"\"") + "\""
    }

    private val dayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT).withZone(ZoneOffset.UTC)

    internal fun day(instant: Instant): String = dayFormatter.format(instant)

    internal fun parseDay(text: String): Instant? = try {
        LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(ZoneOffset.UTC).toInstant()
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
fun Cellar.importCsv(text: String, now: Instant = ReferenceDates.now()): ImportSummary {
    val rows = CsvParser.parse(text)
    val header = rows.firstOrNull() ?: throw ImportException(ImportError.EmptyFile)
    val columns = header.map(CsvColumns::canonical)
    val nameIndex = columns.indexOf("name")
    if (nameIndex < 0) throw ImportException(ImportError.MissingNameColumn)
    if ("scan_date" in columns && "quantity" !in columns) return importDiscoveries(rows, columns, nameIndex, now)
    val winesByKey = HashMap<String, Wine>()
    wines.values.forEach { winesByKey.putIfAbsent(it.identityKey, it) }
    var winesAdded = 0
    var bottlesAdded = 0
    var rowsSkipped = 0

    for (row in rows.drop(1)) {
        fun value(column: String): String? {
            val index = columns.indexOf(column)
            if (index < 0 || index >= row.size) return null
            return row[index].trim().ifEmpty { null }
        }
        val name = if (nameIndex < row.size) value("name") else null
        if (name == null) {
            if (row.any { it.isNotBlank() }) rowsSkipped++
            continue
        }
        var wine = CsvColumns.wine(name, ::value)
        val existing = winesByKey[wine.identityKey]
        if (existing != null) {
            wine = existing
        } else {
            putWine(wine)
            winesByKey[wine.identityKey] = wine
            winesAdded++
        }

        val quantity = (value("quantity")?.let(CsvColumns::number)?.toInt() ?: 1).coerceAtLeast(1)
        repeat(quantity) {
            var bottle = Bottle(
                wineId = wine.id, storageNote = value("storage"), addedAt = now,
                purchaseDate = value("purchase_date")?.let(CellarExport::parseDay),
                purchasePrice = value("price")?.replace(",", ".")?.toBigDecimalOrNull(),
                currency = value("currency") ?: settings.currency, purchasePlace = value("purchase_place"),
                receivedFrom = value("received_from"), sizeML = value("size_ml")?.toIntOrNull() ?: 750,
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
                        date = value("tasted_on")?.let(CellarExport::parseDay) ?: now,
                        rating = value("rating")?.let(CsvColumns::rating), note = value("note"),
                    ).normalized(),
                )
                status == Bottle.Status.Gone -> bottle.copy(status = Bottle.Status.Gone)
                // An ordered bottle has no place yet.
                status == Bottle.Status.Ordered -> bottle.copy(
                    status = Bottle.Status.Ordered, expectedAt = value("expected_on")?.let(CellarExport::parseDay),
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
internal fun Cellar.importDiscoveries(rows: List<List<String>>, columns: List<String>, nameIndex: Int, now: Instant): ImportSummary {
    var winesAdded = 0
    var rowsSkipped = 0
    for (row in rows.drop(1)) {
        fun value(column: String): String? {
            val index = columns.indexOf(column)
            if (index < 0 || index >= row.size) return null
            return row[index].trim().ifEmpty { null }
        }
        val name = if (nameIndex < row.size) value("name") else null
        if (name == null) {
            if (row.any { it.isNotBlank() }) rowsSkipped++
            continue
        }
        val wine = CsvColumns.wine(name, ::value)
        val known = wines.values.any { it.identityKey == wine.identityKey }
        val discovery = Discovery(
            wineId = wine.id, date = value("scan_date")?.let(CsvColumns::date) ?: now, place = value("place"),
            rating = value("rating")?.let(CsvColumns::rating), note = value("note"),
            price = value("price")?.let(CsvColumns::number)?.let { java.math.BigDecimal(it.toString()) },
            currency = value("currency") ?: settings.currency,
        )
        addDiscovery(discovery, wine)
        if (!known) winesAdded++
    }
    return ImportSummary(winesAdded, 0, rowsSkipped)
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
        "tags" to "tags", "tag" to "tags",
        "expectedon" to "expected_on", "deliverydate" to "expected_on", "livraison" to "expected_on",
        "scandate" to "scan_date", "scanreviewlocation" to "place", "place" to "place", "lieu" to "place",
    )

    private fun fold(text: String): String = Wine.fold(text)

    /** "Wine Name", "wine_name" and "Millésime" become "name" and "vintage". */
    fun canonical(header: String): String {
        val folded = fold(header).filter { it.isLetterOrDigit() }
        return synonyms[folded] ?: header.trim().lowercase(Locale.ROOT).replace(" ", "_")
    }

    fun wine(name: String, value: (String) -> String?): Wine {
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
        )
    }

    /** "2015", "2015.0"; not "NV" or "1001" (CellarTracker writes 1001 for non-vintage). */
    fun year(text: String): Int? = number(text)?.toInt()?.takeIf { it in 1800..2200 }

    fun number(text: String): Double? = text.replace(",", ".").filter { it.isDigit() || it == '.' || it == '-' }.toDoubleOrNull()

    /** 1 to 5 (decimals rounded); 100-point scores are converted. */
    fun rating(text: String): Int? {
        val value = number(text)?.takeIf { it > 0 } ?: return null
        val five = if (value > 5) value / 20 else value
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
