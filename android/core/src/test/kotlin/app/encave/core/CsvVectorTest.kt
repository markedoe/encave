package app.encave.core

import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
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

    private fun table(key: String): Map<String, JsonElement> = root.getValue(key).jsonObject
    private fun JsonElement.textOrNull(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun day(instant: Instant?): String = instant?.let { CellarExport.day(it, zone) } ?: ""
    private fun price(value: BigDecimal?): String = value?.let(DecimalSerializer::plain) ?: ""

    private val wineKeys = listOf(
        "name", "producer", "vintage", "color", "region", "country", "appellation", "grapes",
        "drinkFrom", "drinkUntil", "tags", "currentPrice", "currentPriceCurrency", "currentPriceDate",
    )
    private val bottleKeys = listOf(
        "wine", "status", "count", "sizeML", "currency", "price", "storage", "purchaseDate",
        "purchasePlace", "receivedFrom", "reservedFor", "rating", "tastedOn", "note", "expectedOn",
    )
    private val discoveryKeys = listOf("wine", "date", "place", "rating", "note", "price", "currency")

    private fun line(keys: List<String>, fields: Map<String, String>): String =
        keys.joinToString(" | ") { "$it=${fields[it] ?: ""}" }

    /** Strings, numbers and lists of strings ("; " between items), as CaveKit writes them. */
    private fun expected(keys: List<String>, items: JsonArray?): List<String> = items.orEmpty().map { item ->
        line(
            keys,
            item.jsonObject.mapValues { (_, value) ->
                val text = if (value is JsonArray) value.joinToString("; ") { it.jsonPrimitive.content } else value.jsonPrimitive.content
                if (text == "now") day(now) else text
            },
        )
    }.sorted()

    private fun wineLines(cellar: Cellar): List<String> = cellar.wines.values.map { wine ->
        line(
            wineKeys,
            mapOf(
                "name" to wine.name, "producer" to (wine.producer ?: ""), "vintage" to (wine.vintage?.toString() ?: ""),
                "color" to (wine.color?.raw ?: ""), "region" to (wine.region ?: ""), "country" to (wine.country ?: ""),
                "appellation" to (wine.appellation ?: ""), "grapes" to wine.grapes.joinToString("; "),
                "drinkFrom" to (wine.drinkFrom?.toString() ?: ""), "drinkUntil" to (wine.drinkUntil?.toString() ?: ""),
                "tags" to wine.tags.orEmpty().joinToString("; "), "currentPrice" to price(wine.currentPrice),
                "currentPriceCurrency" to (wine.currentPriceCurrency ?: ""), "currentPriceDate" to day(wine.currentPriceDate),
            ),
        )
    }.sorted()

    /** Bottles alike are counted on one line. */
    private fun bottleLines(cellar: Cellar): List<String> = cellar.bottles.values.map { bottle ->
        mapOf(
            "wine" to (cellar.wines[bottle.wineId]?.name ?: "?"), "status" to bottle.status.raw,
            "sizeML" to bottle.sizeML.toString(), "currency" to (bottle.currency ?: ""), "price" to price(bottle.purchasePrice),
            "storage" to (bottle.storageNote ?: ""), "purchaseDate" to day(bottle.purchaseDate),
            "purchasePlace" to (bottle.purchasePlace ?: ""), "receivedFrom" to (bottle.receivedFrom ?: ""),
            "reservedFor" to (bottle.reservedFor ?: ""), "rating" to (bottle.tasting?.rating?.toString() ?: ""),
            "tastedOn" to day(bottle.tasting?.date), "note" to (bottle.tasting?.note ?: ""), "expectedOn" to day(bottle.expectedAt),
        )
    }.groupingBy { it }.eachCount().map { (fields, count) -> line(bottleKeys, fields + ("count" to count.toString())) }.sorted()

    private fun discoveryLines(cellar: Cellar): List<String> = cellar.discoveries.values.map { item ->
        line(
            discoveryKeys,
            mapOf(
                "wine" to (cellar.wines[item.wineId]?.name ?: "?"), "date" to day(item.date), "place" to (item.place ?: ""),
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
    fun parsing() {
        for (item in root.getValue("parsed").jsonArray.map { it.jsonObject }) {
            val text = item.getValue("text").jsonPrimitive.content
            val rows = item.getValue("rows").jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.content } }
            assertEquals(rows, CsvParser.parse(text), text)
        }
    }

    @Test
    fun imports() {
        val imports = root.getValue("imports").jsonArray.map { it.jsonObject }
        assertTrue(imports.size >= 4)
        for (item in imports) {
            val about = item.getValue("about").jsonPrimitive.content
            val end = item["lineEnding"]?.jsonPrimitive?.content ?: "\n"
            val bom = if (item["byteOrderMark"]?.jsonPrimitive?.booleanOrNull == true) "﻿" else ""
            val text = bom + item.getValue("csv").jsonArray.joinToString(end) { it.jsonPrimitive.content } + end
            val cellar = Cellar()
            val summary = cellar.importCsv(text, now, zone)
            val expectedSummary = item.getValue("summary").jsonObject
            assertEquals(
                listOf("winesAdded", "bottlesAdded", "rowsSkipped").map { expectedSummary.getValue(it).jsonPrimitive.int },
                listOf(summary.winesAdded, summary.bottlesAdded, summary.rowsSkipped), about,
            )
            val discoveries = item["discoveries"]?.jsonArray
            assertEquals(discoveries?.size ?: 0, summary.discoveriesAdded, about)
            assertEquals(expected(wineKeys, item["wines"]?.jsonArray), wineLines(cellar), about)
            assertEquals(expected(bottleKeys, item["bottles"]?.jsonArray), bottleLines(cellar), about)
            assertEquals(expected(discoveryKeys, discoveries), discoveryLines(cellar), about)

            val export = CellarExport.csv(cellar, zone = zone)
            assertTrue(export.startsWith(CellarExport.csvColumns.joinToString(",") + "\n"))
            item["exportLines"]?.jsonArray?.let { lines ->
                assertEquals(lines.map { it.jsonPrimitive.content }.sorted(), export.lines().drop(1).filter { it.isNotEmpty() }.sorted(), about)
            }
            if (!item.getValue("roundTrip").jsonPrimitive.boolean) continue
            // What Encave writes, it reads back the same.
            val copy = Cellar()
            val again = copy.importCsv(export, now, zone)
            assertEquals(summary.winesAdded to summary.bottlesAdded, again.winesAdded to again.bottlesAdded, about)
            assertEquals(wineLines(cellar), wineLines(copy), about)
            assertEquals(bottleLines(cellar), bottleLines(copy), about)
            assertEquals(export.lines().sorted(), CellarExport.csv(copy, zone = zone).lines().sorted(), about)
        }
    }
}
