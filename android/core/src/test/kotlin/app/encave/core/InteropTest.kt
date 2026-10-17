package app.encave.core

import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Fixtures written by the Swift CaveKit (`CellarExport.json` and `CellarExport.csv`
 * of `SampleData.cellar(year: 2026)`), so both apps read each other's files.
 */
class InteropTest {
    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader.getResource(name)) { "missing $name" }.readText(Charsets.UTF_8)

    private val appleBackup = resource("apple-backup.json")

    @Test
    fun decodesTheAppleBackup() {
        val backup = CellarExport.backupFromJson(appleBackup)
        assertEquals("encave", backup.format)
        assertEquals(2, backup.version)
        assertEquals(Instant.ofEpochSecond(1_790_000_000), backup.exportedAt)
        val cellar = backup.cellar
        val sample = SampleData.cellar(year = 2026)
        assertEquals(sample.racks.map { it.name to it.layout }, cellar.racks.map { it.name to it.layout })
        assertEquals(sample.bottles.size, cellar.bottles.size)
        assertEquals(sample.wines.values.map { it.displayName }.toSet(), cellar.wines.values.map { it.displayName }.toSet())
        assertEquals(2, cellar.consumedBottles.size)
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
        val ours = CellarExport.csv(cellar)
        val theirs = resource("apple-export.csv")
        // Bottles of one wine added at the same instant have no defined order.
        assertEquals(theirs.lines().first(), ours.lines().first())
        assertEquals(theirs.lines().sorted(), ours.lines().sorted())
    }

    @Test
    fun importsTheAppleCsv() {
        val apple = CellarExport.backupFromJson(appleBackup).cellar
        val target = Cellar(racks = apple.racks)
        val summary = target.importCsv(resource("apple-export.csv"))
        assertEquals(apple.bottles.size, summary.bottlesAdded)
        assertEquals(apple.wines.size, summary.winesAdded)
        assertEquals(apple.bottlesInCellar.mapNotNull { it.slot }.toSet(), target.bottlesInCellar.mapNotNull { it.slot }.toSet())
    }
}
