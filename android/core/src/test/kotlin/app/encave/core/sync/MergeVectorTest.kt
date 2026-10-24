package app.encave.core.sync

import app.encave.core.Cellar
import java.io.File
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Merge conflicts from shared vectors, run both ways. CaveKit (MergeVectorTests.swift) reads
 * the same file, `fixtures/sync/merge-vectors.json`, whose "about" describes the format.
 */
class MergeVectorTest {
    /** Tests run in android/core. */
    private val root: JsonObject = Json.parseToJsonElement(File("../../fixtures/sync/merge-vectors.json").readText()).jsonObject

    private fun bytes(element: kotlinx.serialization.json.JsonElement) = element.toString().toByteArray()

    private data class Compact(val wines: Map<String, String>, val bottles: Map<String, String>, val racks: List<String>)

    private fun compact(cellar: Cellar) = Compact(
        cellar.wines.values.associate { it.id.toString() to it.name },
        cellar.bottles.values.associate { bottle ->
            val place = bottle.slot?.let { slot ->
                val rack = cellar.racks.firstOrNull { it.id == slot.rackId }?.name ?: slot.rackId.toString()
                " $rack ${slot.row},${slot.column}"
            }
            bottle.id.toString() to bottle.status.raw + place.orEmpty()
        },
        cellar.racks.map { "${it.name} ${it.rows}x${it.columns}" },
    )

    @Test
    fun mergesGiveTheSharedResultBothWays() {
        for (element in root.getValue("cases").jsonArray) {
            val case = element.jsonObject
            val name = case.getValue("name").jsonPrimitive.content
            fun side(key: String): Pair<Cellar, SyncState> {
                val side = case.getValue(key).jsonObject
                return SyncJson.decodeCellar(bytes(side.getValue("cellar"))) to
                    SyncJson.decode(SyncState.serializer(), bytes(side.getValue("state")))
            }
            val (cellarA, stateA) = side("a")
            val (cellarB, stateB) = side("b")
            val expect = case.getValue("expect").jsonObject
            val compact = Compact(
                expect.getValue("wines").jsonObject.mapValues { it.value.jsonPrimitive.content },
                expect.getValue("bottles").jsonObject.mapValues { it.value.jsonPrimitive.content },
                expect.getValue("racks").jsonArray.map { it.jsonPrimitive.content },
            )
            val stamps = SyncJson.decode(MapSerializer(String.serializer(), Stamp.serializer()), bytes(expect.getValue("stamps")))
            val clock = expect.getValue("clock").jsonPrimitive.long

            val onA = CellarMerge.merge(cellarA, stateA, cellarB, stateB)
            val onB = CellarMerge.merge(cellarB, stateB, cellarA, stateA)
            for ((side, result) in listOf("A" to onA, "B" to onB)) {
                assertEquals(compact, compact(result.cellar), "$name, on $side")
                assertEquals(stamps, result.stamps, "$name, stamps on $side")
                assertEquals(clock, result.clock, "$name, clock on $side")
            }
            assertEquals(onA.cellar, onB.cellar, name)
        }
    }
}
