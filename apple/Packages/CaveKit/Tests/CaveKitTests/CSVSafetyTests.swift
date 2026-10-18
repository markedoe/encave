import Foundation
import Testing
@testable import CaveKit

/// Spreadsheets from elsewhere: odd numbers must not crash or hang the import, and the
/// same file gives the same cellar as on Android (CsvSafetyTest.kt).
@Suite struct CSVSafetyTests {
    @Test func hugeOrOddNumbersDoNotCrash() throws {
        var cellar = Cellar()
        let csv = """
        name,quantity,vintage,rating,size_ml
        A,99999999999999999999999,99999999999999999999999,99999999999999999999999,99999999999999999999999
        B,-3,1e400,NaN,750.0
        C,2.5,2015.0,4.6,1500
        """
        try cellar.importCSV(csv)
        let byName = Dictionary(grouping: cellar.bottles.values) { cellar.wines[$0.wineID]!.name }
        #expect(byName["A"]?.count == CSVColumns.maxQuantity)
        #expect(byName["B"]?.count == 1)
        #expect(byName["C"]?.count == 3, "2.5 rounds to 3, as on Android")
        let a = try #require(byName["A"]?.first), c = try #require(byName["C"]?.first)
        #expect(cellar.wines[a.wineID]?.vintage == nil)
        #expect(a.sizeML == 750)
        #expect(cellar.wines[c.wineID]?.vintage == 2015)
        #expect(c.sizeML == 1500)
        #expect(CSVColumns.rating("99999999999999999999999") == 5)
        #expect(CSVColumns.rating("4.6") == 5)
    }

    @Test func longSlotLabelsAreNotPlaces() {
        let rack = Rack(name: "R", rows: 30, columns: 5, depth: 2)
        #expect(rack.slot(forLabel: "B3-2") == Slot(rackID: rack.id, row: 1, column: 2, depth: 1))
        #expect(rack.slot(forLabel: "AA1")?.row == 26)
        #expect(rack.slot(forLabel: "AAAAAAAAAAAAAAAAAAAAAAAAAAAA1") == nil)
        #expect(rack.slot(forLabel: "B3-9223372036854775808") == nil)
        #expect(rack.slot(forLabel: "B99999999999999999999") == nil)
        #expect(rack.slot(forLabel: "B+3") == nil)
    }

    @Test func windowsLineEndingsStillFindTheSeparator() throws {
        let rows = CSVParser.parse("name;price;note\r\nA;12,50;x, y\r\nB;3;z\r\n")
        #expect(rows == [["name", "price", "note"], ["A", "12,50", "x, y"], ["B", "3", "z"]])
    }

    @Test func pricesAreReadLikeOnAndroid() throws {
        var cellar = Cellar()
        try cellar.importCSV("name,price,current_price\nA,\"1 234,50\",\"1,234.50\"\nB,12.30,0\nC,abc,\"€ 45\"\n")
        let price = { (name: String) in cellar.bottles.values.first { cellar.wines[$0.wineID]?.name == name }?.purchasePrice }
        let current = { (name: String) in cellar.wines.values.first { $0.name == name }?.currentPrice }
        #expect(price("A") == Decimal(string: "1234.5"))
        #expect(current("A") == Decimal(string: "1234.5"))
        #expect(price("B") == Decimal(string: "12.3"))
        #expect(current("B") == nil)
        #expect(price("C") == nil)
        #expect(current("C") == 45)
        var history = Cellar()
        try history.importCSV("name,scan date,price\nTasted,2024-05-01,12.30\n")
        #expect(history.discoveries.values.first?.price == Decimal(string: "12.3"), "not 12.300000000000001")
    }

    @Test func datesAreDaysOfTheUsersTimeZone() throws {
        let paris = try #require(TimeZone(identifier: "Europe/Paris"))
        var cellar = Cellar()
        let wine = Wine(name: "Late night")
        // 00:30 in Paris on 10 March is still 9 March in UTC.
        let bought = try #require(ISO8601DateFormatter().date(from: "2024-03-09T23:30:00Z"))
        try cellar.addBottles(of: wine, quantity: 1, purchase: PurchaseInfo(date: bought))
        let csv = CellarExport.csv(cellar, timeZone: paris)
        #expect(csv.contains("2024-03-10"))
        var imported = Cellar()
        try imported.importCSV(csv, timeZone: paris)
        let date = try #require(imported.bottles.values.first?.purchaseDate)
        #expect(date == ISO8601DateFormatter().date(from: "2024-03-09T23:00:00Z"), "the start of 10 March in Paris")
    }

    @Test func formulasAreNotRunBySpreadsheets() throws {
        var cellar = Cellar()
        var wine = Wine(name: "=HYPERLINK(\"http://x\")", producer: "+33 Domaine")
        wine.notes = nil
        try cellar.addBottles(of: wine, quantity: 1)
        let csv = CellarExport.csv(cellar)
        #expect(csv.contains("\"'=HYPERLINK(\"\"http://x\"\")\""))
        #expect(csv.contains(",'+33 Domaine,"))
        var imported = Cellar()
        try imported.importCSV(csv)
        #expect(imported.wines.values.first?.name == wine.name)
        #expect(imported.wines.values.first?.producer == "+33 Domaine")
    }
}
