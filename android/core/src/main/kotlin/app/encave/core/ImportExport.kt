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
        "rating", "tasted_on", "note", "expected_on",
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
                bottle.tasting?.note, bottle.expectedAt?.let(::day),
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
 * Imports a CSV in the export format (column order may vary; only "name" is required).
 * A "quantity" column, if present, creates that many bottles per row.
 */
fun Cellar.importCsv(text: String, now: Instant = ReferenceDates.now()): ImportSummary {
    val rows = CsvParser.parse(text)
    val header = rows.firstOrNull() ?: throw ImportException(ImportError.EmptyFile)
    val columns = header.map { it.trim().lowercase(Locale.ROOT).replace(" ", "_") }
    val nameIndex = columns.indexOf("name")
    if (nameIndex < 0) throw ImportException(ImportError.MissingNameColumn)
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
        var wine = Wine(
            name = name, producer = value("producer"), vintage = value("vintage")?.toIntOrNull(),
            color = value("color")?.let { WineColor.fromRaw(it.lowercase(Locale.ROOT)) }, region = value("region"),
            country = value("country"), appellation = value("appellation"),
            grapes = (value("grapes") ?: "").split(";").map { it.trim() }.filter { it.isNotEmpty() },
            drinkFrom = value("drink_from")?.toIntOrNull(), drinkUntil = value("drink_until")?.toIntOrNull(),
        )
        val existing = winesByKey[wine.identityKey]
        if (existing != null) {
            wine = existing
        } else {
            putWine(wine)
            winesByKey[wine.identityKey] = wine
            winesAdded++
        }

        val quantity = (value("quantity")?.toIntOrNull() ?: 1).coerceAtLeast(1)
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
                        rating = value("rating")?.toIntOrNull(), note = value("note"),
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
