package app.encave.core

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The sample cellar as CaveKit exports it (`fixtures/interop/swift-sample-backup.json` and
 * `swift-sample-export.csv`, written by CaveKit's SampleInteropTests from
 * `SampleData.cellar(year: 2026, now:)` with fixed dates), so both apps read each other's
 * backups and spreadsheets: every kind of storage, a head-to-tail fridge, bottles on order,
 * tags, reservations and tastings. CaveKit checks that the files are up to date.
 */
class InteropTest {
    /** Tests run in android/core. */
    private fun swift(name: String): String = File("../../fixtures/interop/swift-$name").readText()

    private val now = Instant.ofEpochSecond(1_790_000_000)
    private val sample = SampleData.cellar(year = 2026, now = now)
    private val appleBackup = swift("sample-backup.json")
    private val appleCsv = swift("sample-export.csv")

    /** The header, then the rows in a fixed order: bottles of a wine added at the same instant have no defined order. */
    private fun rows(text: String) = text.lines().take(1) + text.lines().drop(1).sorted()

    @Test
    fun decodesTheAppleBackup() {
        val backup = CellarExport.backupFromJson(appleBackup)
        assertEquals("encave", backup.format)
        assertEquals(2, backup.version)
        assertEquals(now, backup.exportedAt)
        val cellar = backup.cellar
        // The same sample on both platforms, to the last field.
        assertEquals(sample, cellar)
        val fridge = cellar.racks.single { it.isBackReversed }
        assertNotNull(cellar.bottleAt(Slot(fridge.id, 1, 3, 1)))
        assertEquals(6, cellar.bottlesOnOrder.size)
        assertEquals(listOf("Christmas dinner"), cellar.reservations)
        assertTrue(cellar.wines.values.any { !it.tags.isNullOrEmpty() })
        assertEquals(2, cellar.consumedBottles.size)
        assertEquals(2, cellar.discoveries.size)
        assertEquals(2, cellar.shoppingListCount)
        assertEquals(4.5, cellar.statistics(2026).averageRating)
        assertTrue(cellar.bottlesInCellar.all { it.purchasePrice != null })
    }

    @Test
    fun reencodesTheAppleBackupIdentically() {
        val backup = CellarExport.backupFromJson(appleBackup)
        val ours = CellarExport.json(backup.cellar, backup.exportedAt)
        val parse = { text: String -> Json.parseToJsonElement(text) }
        assertEquals(parse(appleBackup), parse(ours))
    }

    @Test
    fun writesWholeSecondsAndUppercaseIds() {
        val ours = CellarExport.json(SampleData.cellar(year = 2026), Instant.parse("2026-10-17T08:58:49.123Z"))
        assertTrue(ours.contains("\"exportedAt\": \"2026-10-17T08:58:49Z\""), "no fractional seconds")
        val ids = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").findAll(ours).map { it.value }.toList()
        assertFalse(ids.isEmpty())
        assertTrue(ids.all { it == it.uppercase() }, "UUIDs are uppercase")
        assertTrue(Regex("\"purchasePrice\": [0-9]+[,\n]").containsMatchIn(ours), "prices are bare numbers")
    }

    @Test
    fun exportsTheSameCsvAsSwift() {
        val cellar = CellarExport.backupFromJson(appleBackup).cellar
        assertEquals(rows(appleCsv), rows(CellarExport.csv(cellar, zone = ZoneOffset.UTC)))
        assertEquals(rows(appleCsv), rows(CellarExport.csv(sample, zone = ZoneOffset.UTC)))
    }

    @Test
    fun importsTheAppleCsv() {
        val apple = CellarExport.backupFromJson(appleBackup).cellar
        val target = Cellar(racks = apple.racks)
        val summary = target.importCsv(appleCsv, now = now, zone = ZoneOffset.UTC)
        assertEquals(apple.bottles.size, summary.bottlesAdded)
        assertEquals(apple.bottles.values.map { it.wineId }.toSet().size, summary.winesAdded)
        assertEquals(apple.bottlesInCellar.mapNotNull { it.slot }.toSet(), target.bottlesInCellar.mapNotNull { it.slot }.toSet())
        assertEquals(apple.bottlesOnOrder.size, target.bottlesOnOrder.size)
        assertEquals(apple.bottlesInCellar.count { it.reservedFor != null }, target.bottlesInCellar.count { it.reservedFor != null })
    }
}
