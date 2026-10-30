package app.encave.core

import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The expected profile, as CaveKit's ExpectedProfileTests (`fixtures/expected-profile-vectors.json`, `fixtures/expected-profiles.json`). */
class ExpectedProfileTest {
    private val vectors = sharedFixture("expected-profile-vectors.json")
    private val json = CellarJson.json
    private fun rows(key: String) = vectors.getValue(key).jsonArray.map { it.jsonObject }.also { assertTrue(it.isNotEmpty(), key) }
    private fun strings(row: JsonObject, key: String) = row.getValue(key).jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun readVectors() {
        for (row in rows("read")) {
            val profile = json.decodeFromJsonElement(ExpectedProfile.serializer(), row.getValue("input"))
            val written = json.encodeToJsonElement(ExpectedProfile.serializer(), profile)
            assertEquals(canonical(row.getValue("output")), canonical(written), row["note"].toString())
        }
    }

    @Test
    fun lookupVectors() {
        for (row in rows("lookup")) {
            val wineObject = JsonObject(row.getValue("wine").jsonObject + ("id" to JsonPrimitive(UUID.randomUUID().toString())))
            val wine = json.decodeFromJsonElement(Wine.serializer(), wineObject)
            val expected = (row["profile"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            assertEquals(expected, ExpectedProfiles.entry(wine)?.id, row["note"].toString())
        }
    }

    @Test
    fun comparisonVectors() {
        for (row in rows("comparison")) {
            val comparison = AromaComparison.of(strings(row, "expected"), strings(row, "found"))
            assertEquals(strings(row, "matched"), comparison.matched)
            assertEquals(strings(row, "missed"), comparison.missed)
            assertEquals(strings(row, "extra"), comparison.extra)
        }
    }

    /** The embedded text is the shared file, and every id in it is one both apps know. */
    @Test
    fun bundledProfilesAreTheFixture() {
        assertEquals(File("../../fixtures/expected-profiles.json").readText().trimEnd('\n'), expectedProfilesJson.trimEnd('\n'))
        val entries = ExpectedProfiles.entries
        assertTrue(entries.size >= 40)
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
        val aromas = Vocabulary.aromaIds.toSet()
        val structure = Vocabulary.structure.map { it.id }.toSet()
        val dishes = DishCategory.entries.map { it.raw }.toSet()
        for (entry in entries) {
            assertTrue(entry.aromas.isNotEmpty() && entry.aromas.all { it in aromas }, entry.id)
            assertTrue(entry.structure.keys.all { it in structure } && entry.structure.values.all { it in 1..5 }, entry.id)
            assertTrue(entry.pairings.all { it in dishes }, entry.id)
            assertTrue(entry.servingMin <= entry.servingMax, entry.id)
            assertFalse(entry.note["en"].isNullOrEmpty() || entry.note["fr"].isNullOrEmpty(), entry.id)
            for (name in entry.appellations.orEmpty() + entry.grapes.orEmpty().flatten()) assertEquals(name, ExpectedProfiles.normalized(name), entry.id)
        }
        for (color in WineColor.entries) assertNotNull(ExpectedProfiles.entry(Wine(name = "X", color = color)), color.raw)
    }

    @Test
    fun wineRoundTripAndOlderVersions() {
        val wine = Wine(
            name = "Clos", vintage = 2019, color = WineColor.Red, grapes = listOf("Pinot Noir"),
            expectedProfile = ExpectedProfile(
                aromas = listOf("fruit.redCherry", "earth.forestFloor"), structure = mapOf("acidity" to 4, "tannin" to 2),
                servingMin = 15, servingMax = 17, decantMinutes = 0, pairings = listOf("poultry"), text = "Silky.", language = "en",
                source = ExpectedProfile.AI, model = "claude-opus-5-5", date = Instant.parse("2026-05-10T12:00:00Z"),
            ),
        )
        val text = json.encodeToString(Wine.serializer(), wine)
        assertEquals(wine, json.decodeFromString(Wine.serializer(), text))
        // A version without the field keeps it as an unknown key and writes it back unchanged.
        val older = json.decodeFromString(OlderWineSerializer, text)
        assertTrue("expectedProfile" in older.unknownKeys)
        val writtenBack = json.encodeToString(OlderWineSerializer, older.copy(name = "Clos"))
        assertEquals(wine, json.decodeFromString(Wine.serializer(), writtenBack))
        // No profile: the key is left out.
        assertFalse("expectedProfile" in json.encodeToJsonElement(Wine.serializer(), Wine(name = "X")).jsonObject)
    }

    @Test
    fun storedProfileWinsOverBundled() {
        val wine = Wine(name = "X", color = WineColor.Red, grapes = listOf("Merlot"))
        assertEquals("merlot", wine.expectedOrBundledProfile?.bundledId)
        assertEquals(ExpectedProfile.AI, wine.copy(expectedProfile = ExpectedProfile(aromas = listOf("fruit.plum"), source = ExpectedProfile.AI)).expectedOrBundledProfile?.source)
        assertEquals("merlot", wine.copy(expectedProfile = ExpectedProfile(source = ExpectedProfile.AI)).expectedOrBundledProfile?.bundledId)
        assertEquals(ExpectedProfiles.note("merlot", "en"), ExpectedProfiles.note("merlot", "de"))
        assertNull(ExpectedProfiles.entry(Wine(name = "Mystery")))
    }

    @Test
    fun mergeKeepsTheProfile() {
        val source = Wine(name = "A", expectedProfile = ExpectedProfile(aromas = listOf("fruit.plum"), source = ExpectedProfile.AI))
        val target = Wine(name = "A")
        val cellar = Cellar(wines = listOf(source, target))
        cellar.mergeWine(source.id, target.id)
        assertEquals(listOf("fruit.plum"), cellar.wines[target.id]?.expectedProfile?.aromas)
    }
}

/** A wine as an older version reads it, knowing only some keys: the others are kept and written back unchanged. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = OlderWineSerializer::class)
internal data class OlderWine(val id: String, val name: String, @Transient val unknownKeys: JsonObject = NoUnknownKeys)

internal object OlderWineSerializer : KeepingUnknownKeys<OlderWine>(OlderWine.generatedSerializer()) {
    override fun unknownKeys(value: OlderWine) = value.unknownKeys
    override fun withUnknownKeys(value: OlderWine, keys: JsonObject) = value.copy(unknownKeys = keys)
}
