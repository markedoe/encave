package app.encave.core

import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Finding the wine a new bottle belongs to, from shared vectors. CaveKit
 * (WineLookupVectorTests.swift) reads the same file, `fixtures/wine-lookup-vectors.json`.
 */
class WineLookupVectorTest {
    /** Tests run in android/core. */
    private val root: JsonObject = Json.parseToJsonElement(File("../../fixtures/wine-lookup-vectors.json").readText()).jsonObject

    private fun JsonObject.text(key: String): String? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
    private fun JsonObject.int(key: String): Int? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.intOrNull
    /** The vectors of one table; an empty one would let its test pass without checking anything. */
    private fun list(key: String) = root.getValue(key).jsonArray.map { it.jsonObject }.also { assertTrue(it.isNotEmpty(), "no $key") }

    private fun cellar(): Cellar {
        val cellar = Cellar()
        val added = Instant.parse("2026-01-01T00:00:00Z")
        for (item in list("wines")) {
            val wine = Wine(
                id = UUID.fromString(item.text("id")), name = item.text("name")!!, producer = item.text("producer"),
                vintage = item.int("vintage"), barcodes = item["barcodes"]?.jsonArray?.map { it.jsonPrimitive.content },
            )
            val drunk = item.int("drunk") ?: 0
            val bottles = cellar.addBottles(wine, quantity = item.int("inCellar")!! + drunk, addedAt = added)
            bottles.take(drunk).forEach { cellar.drink(it.id, Tasting(date = added)) }
        }
        return cellar
    }

    @Test
    fun byBarcode() {
        val cellar = cellar()
        for (lookup in list("barcodes")) {
            val code = lookup.text("code")!!
            val found = cellar.wines(code)
            assertEquals(lookup.getValue("wines").jsonArray.map { it.jsonPrimitive.content }, found.map { it.displayName }, lookup.text("about") ?: code)
            assertEquals(found.firstOrNull()?.id, cellar.wine(barcode = code)?.id)
        }
    }

    @Test
    fun byNameProducerAndVintage() {
        val cellar = cellar()
        for (draft in list("duplicates")) {
            val wine = Wine(
                id = draft.text("id")?.let(UUID::fromString) ?: UUID.randomUUID(), name = draft.text("name")!!,
                producer = draft.text("producer"), vintage = draft.int("vintage"),
            )
            assertEquals(draft.text("match")?.let(UUID::fromString), cellar.wine(matching = wine)?.id, draft.text("about") ?: draft.text("name"))
        }
    }
}
