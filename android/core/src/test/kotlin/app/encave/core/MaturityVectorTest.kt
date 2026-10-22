package app.encave.core

import java.io.File
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Drinking windows from shared vectors. CaveKit (MaturityVectorTests.swift) reads the same
 * file, `fixtures/maturity-vectors.json`, so the two platforms cannot drift.
 */
class MaturityVectorTest {
    /** Tests run in android/core. */
    private val root: JsonObject = Json.parseToJsonElement(File("../../fixtures/maturity-vectors.json").readText()).jsonObject

    private fun list(key: String) = root.getValue(key).jsonArray.map { it.jsonObject }
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
    private fun JsonObject.text(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf { it.isString }?.content

    private fun color(raw: String?): WineColor? = raw?.let { WineColor.fromRaw(it).also { color -> assertNotNull(color, raw) } }

    /** A cellar with the wines' bottles: those in the cellar, and those already drunk. */
    private fun cellar(wines: List<JsonElement>): Cellar {
        val cellar = Cellar()
        val added = Instant.parse("2026-01-01T00:00:00Z")
        for (element in wines) {
            val item = element.jsonObject
            val wine = Wine(
                name = item.text("name")!!, vintage = item.int("vintage"),
                drinkFrom = item.int("from"), drinkUntil = item.int("until"),
            )
            val drunk = item.int("drunk") ?: 0
            val bottles = cellar.addBottles(wine, quantity = item.int("inCellar")!! + drunk, addedAt = added)
            bottles.take(drunk).forEach { cellar.drink(it.id, Tasting(date = added)) }
        }
        return cellar
    }

    @Test
    fun maturityAtTheWindowsEdges() {
        val edges = list("maturity")
        assertTrue(edges.size >= 10)
        for (edge in edges) {
            val wine = Wine(name = "W", drinkFrom = edge.int("from"), drinkUntil = edge.int("until"))
            assertEquals(edge.text("maturity"), Maturity.of(wine, edge.int("year")!!).raw, edge.toString())
        }
    }

    @Test
    fun urgencyOrderIsTheSame() {
        val order = root.getValue("urgencyOrder").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(order, Maturity.entries.sorted().map { it.raw })
    }

    @Test
    fun estimatedWindowPerStyle() {
        for (item in list("estimatedWindow")) {
            val window = Wine(name = "W", vintage = item.int("vintage"), color = color(item.text("color"))).estimatedWindow()
            assertEquals(item.int("from"), window?.first, item.toString())
            assertEquals(item.int("until"), window?.last, item.toString())
        }
    }

    @Test
    fun whereTheWindowComesFrom() {
        for (item in list("windowSource")) {
            val wine = Wine(
                name = "W", vintage = item.int("vintage"), color = color(item.text("color")),
                drinkFrom = item.int("from"), drinkUntil = item.int("until"),
            )
            val source = when (val s = wine.windowSource) {
                is WindowSource.Estimated -> "estimated ${s.yearsAfterHarvest} ${s.yearsAtBest}"
                WindowSource.Entered -> "entered"
                WindowSource.Missing -> "missing"
            }
            assertEquals(item.text("source"), source, item.toString())
        }
    }

    @Test
    fun movingTheEndOfTheWindow() {
        for (item in list("movingWindowEnd")) {
            val moved = Wine(name = "W", drinkFrom = item.int("from"), drinkUntil = item.int("until"))
                .movingWindowEnd(item.int("by")!!)
            assertEquals(item.int("until after"), moved.drinkUntil, item.toString())
            assertEquals(item.int("from"), moved.drinkFrom)
        }
    }

    @Test
    fun monthlyReminderCounts() {
        val digests = list("digests")
        assertTrue(digests.isNotEmpty())
        for (item in digests) {
            val digest = cellar(item.getValue("wines").jsonArray).drinkingDigest(item.int("year")!!)
            assertEquals(item.int("drinkSoon"), digest.drinkSoon)
            assertEquals(item.int("newlyReady"), digest.newlyReady)
            assertEquals(item.int("pastPeak"), digest.pastPeak)
            assertEquals(item.text("firstWine"), digest.firstWine)
            assertEquals(item.getValue("isEmpty").jsonPrimitive.boolean, digest.isEmpty)
        }
    }

    @Test
    fun drinkDownPace() {
        val plans = list("drinkDownPlans")
        assertTrue(plans.isNotEmpty())
        for (item in plans) {
            val cellar = cellar(item.getValue("wines").jsonArray)
            val year = item.int("year")!!
            val month = item.int("month")!!
            val plan = item.int("horizon")?.let { cellar.drinkDownPlan(year, month, it) } ?: cellar.drinkDownPlan(year, month)
            val years = item.getValue("years").jsonArray.map { pair -> pair.jsonArray.map { it.jsonPrimitive.int } }
            val about = item.text("about")
            assertEquals(years, plan.years.map { listOf(it.year, it.leaving) }, about)
            assertEquals(item.int("pastPeak"), plan.pastPeak, about)
            assertEquals(item.int("withoutWindow"), plan.withoutWindow, about)
            assertEquals(item.getValue("bottlesPerMonth").jsonPrimitive.double, plan.bottlesPerMonth, 1e-9, about)
            assertEquals(item.getValue("isEmpty").jsonPrimitive.boolean, plan.isEmpty, about)
        }
    }
}
