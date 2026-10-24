package app.encave.core

import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import java.util.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CSV import and export from shared vectors. CaveKit (CSVVectorTests.swift) reads the same
 * file, `fixtures/csv-vectors.json`, so the two platforms read and write the same files.
 */
class CsvVectorTest {
    /** Tests run in android/core. */
    private val root: JsonObject = Json.parseToJsonElement(File("../../fixtures/csv-vectors.json").readText()).jsonObject

    /** The import's own date: mid-October 2026, the same day in every time zone but the farthest. */
    private val now = Instant.parse("2026-10-15T12:00:00Z")
    private val zone = ZoneId.systemDefault()

    /** A table of vectors, never empty: a loop over nothing would test nothing. */
    private fun table(key: String): Map<String, JsonElement> = root.getValue(key).jsonObject.also { assertTrue(it.isNotEmpty(), key) }
    private fun list(key: String): List<JsonObject> = root.getValue(key).jsonArray.map { it.jsonObject }.also { assertTrue(it.isNotEmpty(), key) }
    private fun JsonElement.textOrNull(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun day(instant: Instant?, zone: ZoneId): String = instant?.let { CellarExport.day(it, zone) } ?: ""
    private fun price(value: BigDecimal?): String = value?.let(DecimalSerializer::plain) ?: ""

    /** The device's zone is not the import's: a date read in the device's zone shows. */
    private fun inUtc(block: () -> Unit) {
        val saved = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            block()
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    private val wineKeys = listOf(
        "name", "producer", "vintage", "color", "region", "country", "appellation", "grapes",
        "drinkFrom", "drinkUntil", "tags", "currentPrice", "currentPriceCurrency", "currentPriceDate",
    )
    private val bottleKeys = listOf(
        "wine", "status", "count", "sizeML", "currency", "price", "storage", "purchaseDate",
        "purchasePlace", "receivedFrom", "reservedFor", "rating", "tastedOn", "note", "expectedOn", "place",
    )
    private val discoveryKeys = listOf("wine", "date", "place", "rating", "note", "price", "currency")

    private fun line(keys: List<String>, fields: Map<String, String>): String =
        keys.joinToString(" | ") { "$it=${fields[it] ?: ""}" }

    /** Strings, numbers and lists of strings ("; " between items), as CaveKit writes them. */
    private fun expected(keys: List<String>, items: JsonArray?, zone: ZoneId): List<String> = items.orEmpty().map { item ->
        line(
            keys,
            item.jsonObject.mapValues { (_, value) ->
                val text = if (value is JsonArray) value.joinToString("; ") { it.jsonPrimitive.content } else value.jsonPrimitive.content
                if (text == "now") day(now, zone) else text
            },
        )
    }.sorted()

    private fun wineLines(cellar: Cellar, zone: ZoneId): List<String> = cellar.wines.values.map { wine ->
        line(
            wineKeys,
            mapOf(
                "name" to wine.name, "producer" to (wine.producer ?: ""), "vintage" to (wine.vintage?.toString() ?: ""),
                "color" to (wine.color?.raw ?: ""), "region" to (wine.region ?: ""), "country" to (wine.country ?: ""),
                "appellation" to (wine.appellation ?: ""), "grapes" to wine.grapes.joinToString("; "),
                "drinkFrom" to (wine.drinkFrom?.toString() ?: ""), "drinkUntil" to (wine.drinkUntil?.toString() ?: ""),
                "tags" to wine.tags.orEmpty().joinToString("; "), "currentPrice" to price(wine.currentPrice),
                "currentPriceCurrency" to (wine.currentPriceCurrency ?: ""), "currentPriceDate" to day(wine.currentPriceDate, zone),
            ),
        )
    }.sorted()

    /** Bottles alike are counted on one line; "place" is the rack and the slot ("Cave B3-1"). */
    private fun bottleLines(cellar: Cellar, zone: ZoneId): List<String> = cellar.bottles.values.map { bottle ->
        val rack = bottle.slot?.let { cellar.rack(it.rackId) }
        mapOf(
            "wine" to (cellar.wines[bottle.wineId]?.name ?: "?"), "status" to bottle.status.raw,
            "sizeML" to bottle.sizeML.toString(), "currency" to (bottle.currency ?: ""), "price" to price(bottle.purchasePrice),
            "storage" to (bottle.storageNote ?: ""), "purchaseDate" to day(bottle.purchaseDate, zone),
            "purchasePlace" to (bottle.purchasePlace ?: ""), "receivedFrom" to (bottle.receivedFrom ?: ""),
            "reservedFor" to (bottle.reservedFor ?: ""), "rating" to (bottle.tasting?.rating?.toString() ?: ""),
            "tastedOn" to day(bottle.tasting?.date, zone), "note" to (bottle.tasting?.note ?: ""),
            "expectedOn" to day(bottle.expectedAt, zone),
            "place" to (bottle.slot?.let { slot -> rack?.let { "${it.name} ${it.label(slot)}" } } ?: ""),
        )
    }.groupingBy { it }.eachCount().map { (fields, count) -> line(bottleKeys, fields + ("count" to count.toString())) }.sorted()

    private fun discoveryLines(cellar: Cellar, zone: ZoneId): List<String> = cellar.discoveries.values.map { item ->
        line(
            discoveryKeys,
            mapOf(
                "wine" to (cellar.wines[item.wineId]?.name ?: "?"), "date" to day(item.date, zone), "place" to (item.place ?: ""),
                "rating" to (item.rating?.toString() ?: ""), "note" to (item.note ?: ""), "price" to price(item.price),
                "currency" to (item.currency ?: ""),
            ),
        )
    }.sorted()

    @Test
    fun headersInEnglishAndFrench() {
        val headers = table("headers")
        assertTrue(headers.size > 40)
        for ((header, column) in headers) assertEquals(column.jsonPrimitive.content, CsvColumns.canonical(header), header)
    }

    @Test
    fun cellValues() {
        for ((text, year) in table("years")) assertEquals(year.jsonPrimitive.intOrNull, CsvColumns.year(text), "year $text")
        for ((text, rating) in table("ratings")) assertEquals(rating.jsonPrimitive.intOrNull, CsvColumns.rating(text), "rating $text")
        for ((text, quantity) in table("quantities")) {
            assertEquals(quantity.jsonPrimitive.int, CsvColumns.quantity(text.ifEmpty { null }), "quantity $text")
            assertEquals(quantity.jsonPrimitive.int, CsvColumns.quantity(text), "quantity $text")
        }
        for ((text, size) in table("sizes")) assertEquals(size.jsonPrimitive.intOrNull, CsvColumns.size(text), "size $text")
        for ((text, color) in table("colors")) assertEquals(color.textOrNull(), CsvColumns.color(text)?.raw, "color $text")
        for ((text, cleaned) in table("cleaned")) assertEquals(cleaned.textOrNull(), CsvColumns.cleaned(text), "cleaned $text")
        for ((text, escaped) in table("escaped")) assertEquals(escaped.jsonPrimitive.content, CellarExport.escape(text), "escaped $text")
    }

    @Test
    fun decoding() {
        for (item in list("decoded")) {
            val bytes = Base64.getDecoder().decode(item.getValue("base64").jsonPrimitive.content)
            assertEquals(item.getValue("text").jsonPrimitive.content, CsvText.decode(bytes), item.getValue("about").jsonPrimitive.content)
        }
    }

    @Test
    fun prices() {
        for ((text, value) in table("ownPrices")) {
            assertEquals(value.textOrNull(), CsvColumns.price(text, plain = true)?.let(CellarExport::price), "own $text")
        }
        for ((text, value) in table("otherPrices")) {
            assertEquals(value.textOrNull(), CsvColumns.price(text, plain = false)?.let(CellarExport::price), "other $text")
        }
        for ((text, value) in table("exportedPrices")) {
            assertEquals(value.jsonPrimitive.content, CellarExport.price(BigDecimal(text)), "exported $text")
        }
    }

    @Test
    fun dates() = inUtc {
        val dates = table("dates")
        val zone = ZoneId.of(dates.getValue("zone").jsonPrimitive.content)
        val values = dates.getValue("values").jsonObject
        assertTrue(values.isNotEmpty())
        for ((text, value) in values) {
            assertEquals(value.textOrNull()?.let(Instant::parse), CsvColumns.date(text, zone), "date $text")
        }
    }

    @Test
    fun parsing() {
        for (item in list("parsed")) {
            val text = item.getValue("text").jsonPrimitive.content
            val rows = item.getValue("rows").jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.content } }
            assertEquals(rows, CsvParser.parse(text), text)
        }
    }

    /** Racks of the cellar before the import. */
    private fun racks(item: JsonObject): List<Rack> = item["racks"]?.jsonArray.orEmpty().map { it.jsonObject }.map { rack ->
        Rack(
            name = rack.getValue("name").jsonPrimitive.content, rows = rack.getValue("rows").jsonPrimitive.int,
            columns = rack.getValue("columns").jsonPrimitive.int, depth = rack["depth"]?.jsonPrimitive?.int ?: 1,
        )
    }

    @Test
    fun imports() = inUtc {
        val imports = list("imports")
        assertTrue(imports.size >= 4)
        for (item in imports) {
            val zone = item["zone"]?.jsonPrimitive?.content?.let(ZoneId::of) ?: zone
            val about = item.getValue("about").jsonPrimitive.content
            val end = item["lineEnding"]?.jsonPrimitive?.content ?: "\n"
            val bom = if (item["byteOrderMark"]?.jsonPrimitive?.booleanOrNull == true) "﻿" else ""
            var text = bom + item.getValue("csv").jsonArray.joinToString(end) { it.jsonPrimitive.content } + end
            item["encoded"]?.jsonPrimitive?.content?.let { encoded ->
                // The file's bytes, as the app reads them.
                val decoded = CsvText.decode(Base64.getDecoder().decode(encoded))
                assertEquals(text, decoded, about)
                text = decoded
            }
            val racks = racks(item)
            val cellar = Cellar()
            racks.forEach(cellar::addRack)
            val summary = cellar.importCsv(text, now, zone)
            val expectedSummary = item.getValue("summary").jsonObject
            assertEquals(
                listOf("winesAdded", "bottlesAdded", "rowsSkipped").map { expectedSummary.getValue(it).jsonPrimitive.int },
                listOf(summary.winesAdded, summary.bottlesAdded, summary.rowsSkipped), about,
            )
            val discoveries = item["discoveries"]?.jsonArray
            assertEquals(discoveries?.size ?: 0, summary.discoveriesAdded, about)
            assertEquals(expected(wineKeys, item["wines"]?.jsonArray, zone), wineLines(cellar, zone), about)
            assertEquals(expected(bottleKeys, item["bottles"]?.jsonArray, zone), bottleLines(cellar, zone), about)
            assertEquals(expected(discoveryKeys, discoveries, zone), discoveryLines(cellar, zone), about)

            val export = CellarExport.csv(cellar, zone = zone)
            assertTrue(export.startsWith(CellarExport.csvColumns.joinToString(",") + "\n"))
            item["exportLines"]?.jsonArray?.let { lines ->
                assertEquals(lines.map { it.jsonPrimitive.content }.sorted(), export.lines().drop(1).filter { it.isNotEmpty() }.sorted(), about)
            }
            if (!item.getValue("roundTrip").jsonPrimitive.boolean) continue
            // What Encave writes, it reads back the same (into a cellar with the same racks).
            val copy = Cellar()
            racks.forEach(copy::addRack)
            val again = copy.importCsv(export, now, zone)
            assertEquals(summary.winesAdded to summary.bottlesAdded, again.winesAdded to again.bottlesAdded, about)
            assertEquals(wineLines(cellar, zone), wineLines(copy, zone), about)
            assertEquals(bottleLines(cellar, zone), bottleLines(copy, zone), about)
            assertEquals(export.lines().sorted(), CellarExport.csv(copy, zone = zone).lines().sorted(), about)
        }
    }
}
