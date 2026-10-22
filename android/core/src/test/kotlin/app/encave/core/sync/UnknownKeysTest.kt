package app.encave.core.sync

import app.encave.core.Bottle
import app.encave.core.Cellar
import app.encave.core.CellarArchive
import app.encave.core.CellarJson
import app.encave.core.Discovery
import app.encave.core.NoUnknownKeys
import app.encave.core.Rack
import app.encave.core.Tasting
import app.encave.core.Wine
import app.encave.core.updateDiscovery
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Keys written by a newer version are kept and written back unchanged (docs/SYNC.md §1),
 * for every kind of record. `fixtures/interop/unknown-keys-*.json` are shared with CaveKit
 * (`UnknownFieldsTests.swift`): each record has keys this version does not know, with a
 * scalar, an object and an array value.
 */
class UnknownKeysTest {
    private fun fixture(name: String) = File(InteropFixture.folder, "unknown-keys-$name.json").readText()
    private fun cellar() = SyncJson.decodeCellar(fixture("cellar").toByteArray())
    private fun tree(text: String) = Json.parseToJsonElement(text)

    private val wineId = InteropFixture.id("000000000011")
    private val bottleId = InteropFixture.id("000000000021")
    private val discoveryId = InteropFixture.id("000000000041")
    private val futureKeys = setOf("futureScalar", "futureObject", "futureArray")

    /** The unknown keys of every record of the cellar, by a name for each record. */
    private fun unknowns(cellar: Cellar): Map<String, JsonObject> {
        val wine = cellar.wines[wineId]
        val bottle = cellar.bottles[bottleId]
        return mapOf(
            "wine" to wine?.unknownKeys, "wine attachment" to wine?.attachments?.firstOrNull()?.unknownKeys,
            "bottle" to bottle?.unknownKeys, "slot" to bottle?.slot?.unknownKeys,
            "tasting" to bottle?.tasting?.unknownKeys, "bottle attachment" to bottle?.attachments?.firstOrNull()?.unknownKeys,
            "rack" to cellar.racks.firstOrNull()?.unknownKeys, "discovery" to cellar.discoveries[discoveryId]?.unknownKeys,
            "settings" to cellar.settings.unknownKeys, "search" to cellar.settings.savedSearches?.firstOrNull()?.unknownKeys,
        ).filterValues { it != null }.mapValues { it.value!! }
    }

    @Test
    fun everyRecordKeepsItsUnknownKeys() {
        val found = unknowns(cellar())
        assertEquals(10, found.size)
        for ((record, keys) in found) assertEquals(futureKeys, keys.keys, record)
    }

    @Test
    fun decodeThenEncodeKeepsThemExactly() {
        val expected = tree(fixture("cellar"))
        // The sync payload and the on-device file.
        assertEquals(expected, tree(SyncJson.encodeCellar(cellar()).decodeToString()))
        assertEquals(expected, tree(CellarJson.encode(CellarJson.decode(fixture("cellar")))))
        val members = SyncJson.decode(Membership.serializer(), fixture("members").toByteArray())
        assertEquals(futureKeys, members.members.values.single().unknownKeys.keys)
        assertEquals(tree(fixture("members")), tree(SyncJson.encode(Membership.serializer(), members).decodeToString()))
    }

    @Test
    fun editsKeepThem() {
        val original = cellar()
        val before = unknowns(original)
        val wine = original.wines.getValue(wineId)
        val bottle = original.bottles.getValue(bottleId)
        val edited = Cellar(
            listOf(wine.copy(name = "Renamed", attachments = wine.attachments!!.map { it.copy(name = "Renamed.pdf") })),
            listOf(bottle.copy(slot = bottle.slot!!.copy(column = 2), tasting = bottle.tasting!!.copy(note = "Even better"))),
            original.racks.map { it.copy(name = "Renamed") },
            original.settings.copy(currency = "CHF", savedSearches = original.settings.savedSearches!!.map { it.copy(name = "Renamed") }),
            original.discoveries.values.map { it.copy(note = "Again") },
        )
        val read = SyncJson.decodeCellar(SyncJson.encodeCellar(edited))
        assertEquals(before, unknowns(read))
        assertEquals("Renamed", read.wines.getValue(wineId).name)
    }

    @Test
    fun editsFromAFormKeepThem() {
        val original = cellar()
        val cellar = Cellar(
            original.wines.values, original.bottles.values.map { it.copy(status = Bottle.Status.Consumed) },
            original.racks, original.settings, original.discoveries.values,
        )
        val before = unknowns(cellar)
        // Forms build the record again from the fields they show.
        cellar.updateTasting(bottleId, Tasting(rating = 5, note = "New"))
        cellar.updateWine(Wine(id = wineId, name = "Rebuilt"))
        cellar.updateRack(Rack(id = InteropFixture.grid, name = "Rebuilt", rows = 2, columns = 3))
        cellar.updateDiscovery(Discovery(id = discoveryId, wineId = wineId))
        val bottle = cellar.bottles.getValue(bottleId)
        cellar.updateBottle(bottle.copy(unknownKeys = NoUnknownKeys, tasting = bottle.tasting!!.copy(unknownKeys = NoUnknownKeys)))
        val after = unknowns(cellar)
        for (record in listOf("wine", "bottle", "tasting", "rack", "discovery")) assertEquals(before[record], after[record], record)
        assertEquals("Rebuilt", cellar.wines.getValue(wineId).name)
    }

    @Test
    fun nestedRecordsKeepThemWhenTheirParentHasNone() {
        val bottle = cellar().bottles.getValue(bottleId).copy(unknownKeys = NoUnknownKeys)
        val read = SyncJson.decode(Bottle.serializer(), SyncJson.encode(Bottle.serializer(), bottle))
        assertEquals(bottle, read)
        assertEquals(futureKeys, read.tasting!!.unknownKeys.keys)
        assertEquals(futureKeys, read.slot!!.unknownKeys.keys)
        assertEquals(futureKeys, read.attachments!!.single().unknownKeys.keys)
    }

    @Test
    fun theOldRackLayoutKeyIsNotKept() {
        val json = """{"id":"00000000-0000-4000-8000-000000000031","name":"Old","layout":"diamond","rows":2,"columns":3}"""
        val rack = SyncJson.decode(Rack.serializer(), json.toByteArray())
        assertEquals(NoUnknownKeys, rack.unknownKeys)
        assertEquals(null, tree(SyncJson.encode(Rack.serializer(), rack).decodeToString()).jsonObject["layout"])
    }

    @Test
    fun knownKeysWin() {
        val wine = cellar().wines.getValue(wineId)
        val odd = wine.copy(unknownKeys = JsonObject(wine.unknownKeys + mapOf("name" to JsonPrimitive("Not the name"), "vintage" to JsonPrimitive(1900))))
        val written = tree(SyncJson.encode(Wine.serializer(), odd).decodeToString()).jsonObject
        assertEquals(JsonPrimitive("Château Futur"), written["name"])
        assertEquals(JsonPrimitive(2030), written["vintage"])
        // And on reading, a known key is never kept.
        assertEquals(futureKeys, SyncJson.decode(Wine.serializer(), SyncJson.encode(Wine.serializer(), odd)).unknownKeys.keys)
    }

    @Test
    fun equalityCountsThemExceptForPlaces() {
        val cellar = cellar()
        val wine = cellar.wines.getValue(wineId)
        // A merge that only brings in a newer version's keys still changes the cellar.
        assertNotEquals(wine, wine.copy(unknownKeys = NoUnknownKeys))
        // A place is a place: two bottles in it are found whatever keys they carry.
        val slot = cellar.bottles.getValue(bottleId).slot!!
        assertEquals(slot, slot.copy(unknownKeys = NoUnknownKeys))
        assertEquals(1, setOf(slot, slot.copy(unknownKeys = NoUnknownKeys)).size)
    }

    @Test
    fun aMergeKeepsTheKeysOfTheNewerVersion() {
        val remote = cellar()
        // The same records as an older build holds them: without the newer keys.
        val local = Cellar(
            remote.wines.values.map { it.copy(unknownKeys = NoUnknownKeys) },
            remote.bottles.values.map { it.copy(unknownKeys = NoUnknownKeys) },
            remote.racks, remote.settings, remote.discoveries.values,
        )
        val device = InteropFixture.android
        val keys = listOf(SyncKey.of(SyncKey.Kind.Wine, wineId), SyncKey.of(SyncKey.Kind.Bottle, bottleId))
        val localState = SyncState(device = InteropFixture.iphone, clock = 5, stamps = keys.associateWith { Stamp(1, device) })
        val remoteState = SyncState(device = device, clock = 6, stamps = keys.associateWith { Stamp(6, device) })
        val merged = CellarMerge.merge(local, localState, remote, remoteState).cellar
        assertEquals(remote.wines[wineId], merged.wines[wineId])
        assertEquals(unknowns(remote), unknowns(merged))
        assertNotEquals(local, merged)
    }

    /** SD-16: a backup (JSON or readable ZIP archive) goes through the same encoding, so a restore keeps them. */
    @Test
    fun backupsAndArchivesKeepThem(@TempDir work: File) {
        val cellar = cellar()
        val backup = CellarJson.backupFrom(CellarJson.backup(cellar, InteropFixture.exportedAt))
        assertEquals(unknowns(cellar), unknowns(backup.cellar))

        val archive = File(work, "archive.zip")
        archive.outputStream().use { CellarArchive.write(cellar, InteropFixture.exportedAt, it, { null }) }
        val contents = archive.inputStream().use { CellarArchive.read(it, work) }
        assertEquals(unknowns(cellar), unknowns(contents.backup.cellar))
        assertEquals(10, unknowns(contents.backup.cellar).size)
    }
}
