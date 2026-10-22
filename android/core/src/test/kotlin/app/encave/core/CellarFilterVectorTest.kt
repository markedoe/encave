package app.encave.core

import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The cellar list from shared vectors: filters, sorts, chips and saved searches. CaveKit
 * (CellarFilterVectorTests.swift) reads the same file, `fixtures/cellar-filter-vectors.json`.
 */
class CellarFilterVectorTest {
    /** Tests run in android/core. */
    private val root: JsonObject =
        Json.parseToJsonElement(File("../../fixtures/cellar-filter-vectors.json").readText()).jsonObject
    private val year = root.getValue("year").jsonPrimitive.intOrNull!!

    private fun JsonObject.text(key: String): String? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
    private fun JsonObject.strings(key: String): List<String>? =
        this[key]?.takeUnless { it is JsonNull }?.jsonArray?.map { it.jsonPrimitive.content }

    private fun day(text: String) = LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant()

    private fun cellar(): Cellar {
        val cellar = Cellar()
        for (element in root.getValue("wines").jsonArray) {
            val item = element.jsonObject
            val wine = Wine(
                name = item.text("name")!!, producer = item.text("producer"), vintage = item["vintage"]?.jsonPrimitive?.intOrNull,
                color = item.text("color")?.let(WineColor::fromRaw), region = item.text("region"), country = item.text("country"),
                grapes = item.strings("grapes").orEmpty(), drinkFrom = item["from"]?.jsonPrimitive?.intOrNull,
                drinkUntil = item["until"]?.jsonPrimitive?.intOrNull, barcodes = item.strings("barcodes"), tags = item.strings("tags"),
            )
            for (bottleElement in item.getValue("bottles").jsonArray) {
                val bottleItem = bottleElement.jsonObject
                val added = day(bottleItem.text("added")!!)
                val bottle = cellar.addBottles(wine, addedAt = added).first()
                bottleItem.text("reservedFor")?.let { cellar.reserve(listOf(bottle.id), it) }
                if (bottleItem["drunk"]?.jsonPrimitive?.booleanOrNull == true) cellar.drink(bottle.id, Tasting(date = added))
            }
        }
        return cellar
    }

    private fun filter(item: JsonObject) = CellarFilter(
        query = item.text("query") ?: "",
        colors = item.strings("colors").orEmpty().mapNotNull(WineColor::fromRaw).toSet(),
        maturities = item.strings("maturities").orEmpty().map { raw -> Maturity.entries.first { it.raw == raw } }.toSet(),
        tag = item.text("tag"), reservation = item.text("reservation"), grape = item.text("grape"),
    )

    private fun names(groups: List<WineGroup>) = groups.map { "${it.wine.name} ×${it.bottles.size}" }

    private fun sort(raw: String?): CellarSort = when (raw ?: "name") {
        "name" -> CellarSort.Name
        "vintage" -> CellarSort.Vintage
        "recentlyAdded" -> CellarSort.RecentlyAdded
        "readiness" -> CellarSort.Readiness
        "region" -> CellarSort.Region
        else -> error("unknown sort $raw")
    }

    @Test
    fun filtersAndSorts() {
        val cellar = cellar()
        val lists = root.getValue("lists").jsonArray.map { it.jsonObject }
        assertTrue(lists.size > 20)
        for (list in lists) {
            val groups = cellar.groups(filter(list), sort(list.text("sort")), year)
            assertEquals(list.strings("groups"), names(groups), list.text("about") ?: list.text("query") ?: list.text("sort"))
        }
    }

    @Test
    fun chips() {
        val cellar = cellar()
        assertEquals(root.strings("tags"), cellar.wineTags)
        assertEquals(root.strings("grapes"), cellar.wineGrapes)
        assertEquals(root.strings("reservations"), cellar.reservations)
    }

    @Test
    fun savedSearches() {
        for (element: JsonElement in root.getValue("savedSearches").jsonArray) {
            val saved = element.jsonObject
            val cellar = cellar()
            for (step in saved.getValue("steps").jsonArray.map { it.jsonObject }) {
                val save = step.text("save")
                val delete = step.text("delete")
                if (save != null) {
                    cellar.saveSearch(save, filter(step))
                } else if (delete != null) {
                    val search = cellar.settings.savedSearches?.firstOrNull { it.name == delete }
                    assertNotNull(search, delete)
                    cellar.deleteSearch(search!!.id)
                }
            }
            val searches = cellar.settings.savedSearches
            assertEquals(saved.strings("names"), searches?.map { it.name }, saved.text("about"))
            val lists = saved.getValue("lists").jsonObject
            for (search in searches.orEmpty()) {
                // A saved search keeps its filters as typed, trimmed, in a stable order.
                assertEquals(search.query.trim(), search.query)
                assertEquals(search.colors.map { it.raw }.sorted(), search.colors.map { it.raw })
                assertEquals(search.maturities.map { it.raw }.sorted(), search.maturities.map { it.raw })
                val expected = lists[search.name]?.jsonArray?.map { it.jsonPrimitive.content }
                assertNotNull(expected, search.name)
                assertEquals(expected, names(cellar.groups(search.filter, CellarSort.Name, year)), search.name)
            }
        }
    }
}
