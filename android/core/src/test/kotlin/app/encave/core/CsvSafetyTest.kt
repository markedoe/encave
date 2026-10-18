package app.encave.core

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Mirrors CaveKit's CSVSafetyTests: the same file gives the same cellar on both platforms. */
class CsvSafetyTest {
    @Test
    fun hugeOrOddNumbersDoNotHang() {
        val cellar = Cellar()
        val csv = """
            name,quantity,vintage,rating,size_ml
            A,99999999999999999999999,99999999999999999999999,99999999999999999999999,99999999999999999999999
            B,-3,1e400,NaN,750.0
            C,2.5,2015.0,4.6,1500
        """.trimIndent()
        cellar.importCsv(csv)
        val byName = cellar.bottles.values.groupBy { cellar.wines.getValue(it.wineId).name }
        assertEquals(CsvColumns.MAX_QUANTITY, byName["A"]?.size)
        assertEquals(1, byName["B"]?.size)
        assertEquals(3, byName["C"]?.size, "2.5 rounds to 3, as in Swift")
        val a = byName.getValue("A").first()
        val c = byName.getValue("C").first()
        assertNull(cellar.wines[a.wineId]?.vintage)
        assertEquals(750, a.sizeML)
        assertEquals(2015, cellar.wines[c.wineId]?.vintage)
        assertEquals(1500, c.sizeML)
        assertEquals(5, CsvColumns.rating("99999999999999999999999"))
        assertEquals(5, CsvColumns.rating("4.6"))
    }

    @Test
    fun longSlotLabelsAreNotPlaces() {
        val rack = Rack(name = "R", rows = 30, columns = 5, depth = 2)
        assertEquals(Slot(rack.id, 1, 2, 1), rack.slotForLabel("B3-2"))
        assertEquals(26, rack.slotForLabel("AA1")?.row)
        assertNull(rack.slotForLabel("AAAAAAAAAAAAAAAAAAAAAAAAAAAA1"))
        assertNull(rack.slotForLabel("B3-9223372036854775808"))
        assertNull(rack.slotForLabel("B99999999999999999999"))
        assertNull(rack.slotForLabel("B+3"))
    }

    @Test
    fun windowsLineEndingsStillFindTheSeparator() {
        val rows = CsvParser.parse("name;price;note\r\nA;12,50;x, y\r\nB;3;z\r\n")
        assertEquals(listOf(listOf("name", "price", "note"), listOf("A", "12,50", "x, y"), listOf("B", "3", "z")), rows)
    }

    @Test
    fun pricesAreReadLikeInSwift() {
        val cellar = Cellar()
        cellar.importCsv("name,price,current_price\nA,\"1 234,50\",\"1,234.50\"\nB,12.30,0\nC,abc,\"€ 45\"\n")
        fun price(name: String) = cellar.bottles.values.first { cellar.wines[it.wineId]?.name == name }.purchasePrice
        fun current(name: String) = cellar.wines.values.first { it.name == name }.currentPrice
        assertEquals(BigDecimal("1234.5"), price("A"))
        assertEquals(BigDecimal("1234.5"), current("A"))
        assertEquals(BigDecimal("12.3"), price("B"))
        assertNull(current("B"))
        assertNull(price("C"))
        assertEquals(BigDecimal("45"), current("C"))
        val history = Cellar()
        history.importCsv("name,scan date,price\nTasted,2024-05-01,12.30\n")
        assertEquals(BigDecimal("12.3"), history.discoveries.values.first().price)
    }

    @Test
    fun datesAreDaysOfTheUsersTimeZone() {
        val paris = ZoneId.of("Europe/Paris")
        val cellar = Cellar()
        // 00:30 in Paris on 10 March is still 9 March in UTC.
        cellar.addBottles(Wine(name = "Late night"), 1, purchase = PurchaseInfo(date = Instant.parse("2024-03-09T23:30:00Z")))
        val csv = CellarExport.csv(cellar, zone = paris)
        assertTrue(csv.contains("2024-03-10"))
        assertTrue(CellarExport.csv(cellar, zone = ZoneOffset.UTC).contains("2024-03-09"))
        val imported = Cellar()
        imported.importCsv(csv, zone = paris)
        assertEquals(Instant.parse("2024-03-09T23:00:00Z"), imported.bottles.values.first().purchaseDate)
    }

    @Test
    fun formulasAreNotRunBySpreadsheets() {
        val cellar = Cellar()
        val wine = Wine(name = "=HYPERLINK(\"http://x\")", producer = "+33 Domaine")
        cellar.addBottles(wine, 1)
        val csv = CellarExport.csv(cellar)
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"http://x\"\")\""))
        assertTrue(csv.contains(",'+33 Domaine,"))
        val imported = Cellar()
        imported.importCsv(csv)
        assertEquals(wine.name, imported.wines.values.first().name)
        assertEquals("+33 Domaine", imported.wines.values.first().producer)
    }
}
