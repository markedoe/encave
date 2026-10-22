import Foundation
import Testing
@testable import CaveKit

/// CSV import and export from shared vectors. The Kotlin core (CsvVectorTest.kt) reads the
/// same file, `fixtures/csv-vectors.json`, so the two platforms read and write the same files.
@Suite struct CSVVectorTests {
    struct Vectors: Decodable {
        struct Parsed: Decodable {
            var text: String
            var rows: [[String]]
        }
        struct Summary: Decodable, Equatable {
            var winesAdded: Int
            var bottlesAdded: Int
            var rowsSkipped: Int
        }
        struct Import: Decodable {
            var about: String
            var csv: [String]
            var lineEnding: String?
            var byteOrderMark: Bool?
            var summary: Summary
            var wines: [[String: Field]]
            var bottles: [[String: Field]]
            var discoveries: [[String: Field]]?
            var roundTrip: Bool
            var exportLines: [String]?

            var text: String {
                let end = lineEnding ?? "\n"
                return (byteOrderMark == true ? "\u{FEFF}" : "") + csv.joined(separator: end) + end
            }
        }
        var headers: [String: String]
        var years: [String: Int?]
        var ratings: [String: Int?]
        var quantities: [String: Int]
        var sizes: [String: Int?]
        var colors: [String: String?]
        var cleaned: [String: String?]
        var escaped: [String: String]
        var parsed: [Parsed]
        var imports: [Import]
    }

    /// A string, a number or a list of strings, written the same way on both platforms.
    enum Field: Decodable, Equatable {
        case text(String)
        case list([String])

        init(from decoder: Decoder) throws {
            let container = try decoder.singleValueContainer()
            if let int = try? container.decode(Int.self) {
                self = .text(String(int))
            } else if let list = try? container.decode([String].self) {
                self = .list(list)
            } else {
                self = .text(try container.decode(String.self))
            }
        }

        var text: String {
            switch self {
            case let .text(value): value
            case let .list(values): values.joined(separator: "; ")
            }
        }
    }

    static func vectors() throws -> Vectors {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent() // CaveKitTests
            .deletingLastPathComponent().deletingLastPathComponent() // CaveKit
            .deletingLastPathComponent().deletingLastPathComponent() // apple
            .deletingLastPathComponent().appendingPathComponent("fixtures/csv-vectors.json")
        return try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: file))
    }

    /// The import's own date: mid-October 2026, the same day in every time zone but the farthest.
    static let now = Date(timeIntervalSince1970: 1_792_065_600) // 2026-10-15T12:00:00Z
    static let zone = TimeZone.current

    static func day(_ date: Date?) -> String {
        date.map(CellarExport.dayFormatter(timeZone: zone).string(from:)) ?? ""
    }

    static func price(_ value: Decimal?) -> String {
        value.map { NSDecimalNumber(decimal: $0).stringValue } ?? ""
    }

    static let wineKeys = ["name", "producer", "vintage", "color", "region", "country", "appellation", "grapes",
                           "drinkFrom", "drinkUntil", "tags", "currentPrice", "currentPriceCurrency", "currentPriceDate"]
    static let bottleKeys = ["wine", "status", "count", "sizeML", "currency", "price", "storage", "purchaseDate",
                             "purchasePlace", "receivedFrom", "reservedFor", "rating", "tastedOn", "note", "expectedOn"]
    static let discoveryKeys = ["wine", "date", "place", "rating", "note", "price", "currency"]

    static func line(_ keys: [String], _ fields: [String: String]) -> String {
        keys.map { "\($0)=\(fields[$0] ?? "")" }.joined(separator: " | ")
    }

    static func expected(_ keys: [String], _ items: [[String: Field]]) -> [String] {
        items.map { item in
            line(keys, item.mapValues { $0.text == "now" ? day(now) : $0.text })
        }.sorted()
    }

    static func wineLines(_ cellar: Cellar) -> [String] {
        cellar.wines.values.map { wine -> String in
            var fields: [String: String] = [:]
            fields["name"] = wine.name
            fields["producer"] = wine.producer ?? ""
            fields["vintage"] = wine.vintage.map(String.init) ?? ""
            fields["color"] = wine.color?.rawValue ?? ""
            fields["region"] = wine.region ?? ""
            fields["country"] = wine.country ?? ""
            fields["appellation"] = wine.appellation ?? ""
            fields["grapes"] = wine.grapes.joined(separator: "; ")
            fields["drinkFrom"] = wine.drinkFrom.map(String.init) ?? ""
            fields["drinkUntil"] = wine.drinkUntil.map(String.init) ?? ""
            fields["tags"] = (wine.tags ?? []).joined(separator: "; ")
            fields["currentPrice"] = price(wine.currentPrice)
            fields["currentPriceCurrency"] = wine.currentPriceCurrency ?? ""
            fields["currentPriceDate"] = day(wine.currentPriceDate)
            return line(wineKeys, fields)
        }.sorted()
    }

    /// Bottles alike are counted on one line.
    static func bottleLines(_ cellar: Cellar) -> [String] {
        let lines = cellar.bottles.values.map { bottle -> [String: String] in
            var fields: [String: String] = [:]
            fields["wine"] = cellar.wines[bottle.wineID]?.name ?? "?"
            fields["status"] = bottle.status.rawValue
            fields["sizeML"] = String(bottle.sizeML)
            fields["currency"] = bottle.currency ?? ""
            fields["price"] = price(bottle.purchasePrice)
            fields["storage"] = bottle.storageNote ?? ""
            fields["purchaseDate"] = day(bottle.purchaseDate)
            fields["purchasePlace"] = bottle.purchasePlace ?? ""
            fields["receivedFrom"] = bottle.receivedFrom ?? ""
            fields["reservedFor"] = bottle.reservedFor ?? ""
            fields["rating"] = bottle.tasting?.rating.map(String.init) ?? ""
            fields["tastedOn"] = day(bottle.tasting?.date)
            fields["note"] = bottle.tasting?.note ?? ""
            fields["expectedOn"] = day(bottle.expectedAt)
            return fields
        }
        var counts: [[String: String]: Int] = [:]
        for fields in lines { counts[fields, default: 0] += 1 }
        return counts.map { fields, count in
            var fields = fields
            fields["count"] = String(count)
            return line(bottleKeys, fields)
        }.sorted()
    }

    static func discoveryLines(_ cellar: Cellar) -> [String] {
        cellar.discoveries.values.map { item -> String in
            var fields: [String: String] = [:]
            fields["wine"] = cellar.wines[item.wineID]?.name ?? "?"
            fields["date"] = day(item.date)
            fields["place"] = item.place ?? ""
            fields["rating"] = item.rating.map(String.init) ?? ""
            fields["note"] = item.note ?? ""
            fields["price"] = price(item.price)
            fields["currency"] = item.currency ?? ""
            return line(discoveryKeys, fields)
        }.sorted()
    }

    @Test func headersInEnglishAndFrench() throws {
        let headers = try Self.vectors().headers
        #expect(headers.count > 40)
        for (header, column) in headers {
            #expect(CSVColumns.canonical(header) == column, "\(header)")
        }
    }

    @Test func cellValues() throws {
        let vectors = try Self.vectors()
        for (text, year) in vectors.years { #expect(CSVColumns.year(text) == year, "year \(text)") }
        for (text, rating) in vectors.ratings { #expect(CSVColumns.rating(text) == rating, "rating \(text)") }
        for (text, quantity) in vectors.quantities {
            #expect(CSVColumns.quantity(text.isEmpty ? nil : text) == quantity, "quantity \(text)")
            #expect(CSVColumns.quantity(text) == quantity, "quantity \(text)")
        }
        for (text, size) in vectors.sizes { #expect(CSVColumns.size(text) == size, "size \(text)") }
        for (text, color) in vectors.colors { #expect(CSVColumns.color(text)?.rawValue == color, "color \(text)") }
        for (text, cleaned) in vectors.cleaned { #expect(CSVColumns.cleaned(text) == cleaned, "cleaned \(text)") }
        for (text, escaped) in vectors.escaped { #expect(CellarExport.escape(text) == escaped, "escaped \(text)") }
    }

    @Test func parsing() throws {
        for item in try Self.vectors().parsed {
            #expect(CSVParser.parse(item.text) == item.rows, "\(item.text.debugDescription)")
        }
    }

    @Test func imports() throws {
        let imports = try Self.vectors().imports
        #expect(imports.count >= 4)
        for item in imports {
            var cellar = Cellar()
            let summary = try cellar.importCSV(item.text, now: Self.now, timeZone: Self.zone)
            let about = Comment(rawValue: item.about)
            #expect(Vectors.Summary(winesAdded: summary.winesAdded, bottlesAdded: summary.bottlesAdded,
                                    rowsSkipped: summary.rowsSkipped) == item.summary, about)
            #expect(Self.wineLines(cellar) == Self.expected(Self.wineKeys, item.wines), about)
            #expect(Self.bottleLines(cellar) == Self.expected(Self.bottleKeys, item.bottles), about)
            #expect(Self.discoveryLines(cellar) == Self.expected(Self.discoveryKeys, item.discoveries ?? []), about)

            let export = CellarExport.csv(cellar, timeZone: Self.zone)
            #expect(export.hasPrefix(CellarExport.csvColumns.joined(separator: ",") + "\n"))
            if let lines = item.exportLines {
                #expect(export.split(separator: "\n").dropFirst().map(String.init).sorted() == lines.sorted(), about)
            }
            guard item.roundTrip else { continue }
            // What Encave writes, it reads back the same.
            var copy = Cellar()
            let again = try copy.importCSV(export, now: Self.now, timeZone: Self.zone)
            #expect(again.winesAdded == summary.winesAdded && again.bottlesAdded == summary.bottlesAdded, about)
            #expect(Self.wineLines(copy) == Self.wineLines(cellar), about)
            #expect(Self.bottleLines(copy) == Self.bottleLines(cellar), about)
            #expect(CellarExport.csv(copy, timeZone: Self.zone).split(separator: "\n").sorted()
                    == export.split(separator: "\n").sorted(), about)
        }
    }
}
