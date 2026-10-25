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
        struct Decoded: Decodable {
            var about: String
            var base64: String
            var text: String
        }
        struct Dates: Decodable {
            var about: String
            var zone: String
            var values: [String: String?]
        }
        struct Summary: Decodable, Equatable {
            var winesAdded: Int
            var bottlesAdded: Int
            var rowsSkipped: Int
        }
        /// A rack of the cellar before the import.
        struct RackVector: Decodable {
            var name: String
            var rows: Int
            var columns: Int
            var depth: Int?

            var rack: Rack { Rack(name: name, rows: rows, columns: columns, depth: depth ?? 1) }
        }
        struct Import: Decodable {
            var about: String
            var zone: String?
            var racks: [RackVector]?
            var csv: [String]
            /// The file's bytes (base64), which decode to the csv lines.
            var encoded: String?
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
        var decoded: [Decoded]
        var ownPrices: [String: String?]
        var otherPrices: [String: String?]
        var prices: [String: String?]
        var exportedPrices: [String: String]
        var dates: Dates
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

    static func day(_ date: Date?, _ zone: TimeZone) -> String {
        date.map(CellarExport.dayFormatter(timeZone: zone).string(from:)) ?? ""
    }

    static func price(_ value: Decimal?) -> String {
        value.map { NSDecimalNumber(decimal: $0).stringValue } ?? ""
    }

    static let wineKeys = ["name", "producer", "vintage", "color", "region", "country", "appellation", "grapes",
                           "drinkFrom", "drinkUntil", "tags", "currentPrice", "currentPriceCurrency", "currentPriceDate"]
    static let bottleKeys = ["wine", "status", "count", "sizeML", "currency", "price", "storage", "purchaseDate",
                             "purchasePlace", "receivedFrom", "reservedFor", "rating", "tastedOn", "note", "expectedOn",
                             "place"]
    static let discoveryKeys = ["wine", "date", "place", "rating", "note", "price", "currency"]

    static func line(_ keys: [String], _ fields: [String: String]) -> String {
        keys.map { "\($0)=\(fields[$0] ?? "")" }.joined(separator: " | ")
    }

    static func expected(_ keys: [String], _ items: [[String: Field]], _ zone: TimeZone) -> [String] {
        items.map { item in
            line(keys, item.mapValues { $0.text == "now" ? day(now, zone) : $0.text })
        }.sorted()
    }

    static func wineLines(_ cellar: Cellar, _ zone: TimeZone) -> [String] {
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
            fields["currentPriceDate"] = day(wine.currentPriceDate, zone)
            return line(wineKeys, fields)
        }.sorted()
    }

    /// Bottles alike are counted on one line; "place" is the rack and the slot ("Cave B3-1").
    static func bottleLines(_ cellar: Cellar, _ zone: TimeZone) -> [String] {
        let lines = cellar.bottles.values.map { bottle -> [String: String] in
            var fields: [String: String] = [:]
            fields["wine"] = cellar.wines[bottle.wineID]?.name ?? "?"
            fields["status"] = bottle.status.rawValue
            fields["sizeML"] = String(bottle.sizeML)
            fields["currency"] = bottle.currency ?? ""
            fields["price"] = price(bottle.purchasePrice)
            fields["storage"] = bottle.storageNote ?? ""
            fields["purchaseDate"] = day(bottle.purchaseDate, zone)
            fields["purchasePlace"] = bottle.purchasePlace ?? ""
            fields["receivedFrom"] = bottle.receivedFrom ?? ""
            fields["reservedFor"] = bottle.reservedFor ?? ""
            fields["rating"] = bottle.tasting?.rating.map(String.init) ?? ""
            fields["tastedOn"] = day(bottle.tasting?.date, zone)
            fields["note"] = bottle.tasting?.note ?? ""
            fields["expectedOn"] = day(bottle.expectedAt, zone)
            fields["place"] = bottle.slot.flatMap { slot in
                cellar.rack(slot.rackID).map { "\($0.name) \($0.label(for: slot))" }
            } ?? ""
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

    static func discoveryLines(_ cellar: Cellar, _ zone: TimeZone) -> [String] {
        cellar.discoveries.values.map { item -> String in
            var fields: [String: String] = [:]
            fields["wine"] = cellar.wines[item.wineID]?.name ?? "?"
            fields["date"] = day(item.date, zone)
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
        // A loop over nothing would test nothing.
        #expect(!vectors.years.isEmpty && !vectors.ratings.isEmpty && !vectors.quantities.isEmpty)
        #expect(!vectors.sizes.isEmpty && !vectors.colors.isEmpty && !vectors.cleaned.isEmpty && !vectors.escaped.isEmpty)
        for (text, year) in vectors.years { #expect(CSVColumns.year(text) == year, "year \(text)") }
        for (text, rating) in vectors.ratings { #expect(CSVColumns.rating(text) == rating, "rating \(text)") }
        for (text, quantity) in vectors.quantities {
            #expect(CSVColumns.quantity(text.isEmpty ? nil : text) == quantity, "quantity \(text)")
            #expect(CSVColumns.quantity(text) == quantity, "quantity \(text)")
        }
        for (text, size) in vectors.sizes { #expect(CSVColumns.size(text) == size, "size \(text)") }
        for (text, color) in vectors.colors { #expect(CSVColumns.color(text)?.rawValue == color, "color \(text)") }
        for (text, cleaned) in vectors.cleaned { #expect(CSVColumns.cleaned(text) == cleaned, "cleaned \(text)") }
        for (text, escaped) in vectors.escaped {
            #expect(CellarExport.escape(text) == escaped, "escaped \(text.debugDescription)")
        }
    }

    @Test func decoding() throws {
        let decoded = try Self.vectors().decoded
        #expect(!decoded.isEmpty)
        for item in decoded {
            let data = try #require(Data(base64Encoded: item.base64), Comment(rawValue: item.about))
            #expect(CSVText.decode(data) == item.text, Comment(rawValue: item.about))
        }
    }

    @Test func prices() throws {
        let vectors = try Self.vectors()
        #expect(!vectors.ownPrices.isEmpty && !vectors.otherPrices.isEmpty && !vectors.exportedPrices.isEmpty)
        for (text, value) in vectors.ownPrices {
            #expect(CSVColumns.price(text, plain: true).map(CellarExport.price) == value, "own \(text)")
        }
        for (text, value) in vectors.otherPrices {
            #expect(CSVColumns.price(text, plain: false).map(CellarExport.price) == value, "other \(text)")
        }
        #expect(!vectors.prices.isEmpty)
        for (text, value) in vectors.prices {
            for plain in [true, false] {
                #expect(CSVColumns.price(text, plain: plain).map(CellarExport.price) == value, "price \(text) (\(plain))")
            }
        }
        for (text, value) in vectors.exportedPrices {
            let decimal = try #require(Decimal(string: text, locale: Locale(identifier: "en_US_POSIX")))
            #expect(CellarExport.price(decimal) == value, "exported \(text)")
        }
    }

    /// Other apps' dates are read in the import's zone, not the device's.
    @Test func dates() throws {
        let dates = try Self.vectors().dates
        let zone = try #require(TimeZone(identifier: dates.zone))
        #expect(!dates.values.isEmpty)
        let iso = ISO8601DateFormatter()
        for (text, value) in dates.values {
            var expected: Date?
            if let value { expected = try #require(iso.date(from: value), "\(value)") }
            #expect(CSVColumns.date(text, timeZone: zone) == expected, "date \(text)")
        }
    }

    @Test func parsing() throws {
        let parsed = try Self.vectors().parsed
        #expect(!parsed.isEmpty)
        for item in parsed {
            #expect(CSVParser.parse(item.text) == item.rows, "\(item.text.debugDescription)")
        }
    }

    @Test func imports() throws {
        let imports = try Self.vectors().imports
        #expect(imports.count >= 4)
        for item in imports {
            var zone = Self.zone
            if let identifier = item.zone { zone = try #require(TimeZone(identifier: identifier)) }
            let about = Comment(rawValue: item.about)
            var text = item.text
            if let encoded = item.encoded {
                // The file's bytes, as the app reads them.
                let decoded = CSVText.decode(try #require(Data(base64Encoded: encoded), about))
                #expect(decoded == text, about)
                text = decoded
            }
            let racks = (item.racks ?? []).map(\.rack)
            var cellar = Cellar()
            cellar.racks = racks
            let summary = try cellar.importCSV(text, now: Self.now, timeZone: zone)
            #expect(Vectors.Summary(winesAdded: summary.winesAdded, bottlesAdded: summary.bottlesAdded,
                                    rowsSkipped: summary.rowsSkipped) == item.summary, about)
            #expect(Self.wineLines(cellar, zone) == Self.expected(Self.wineKeys, item.wines, zone), about)
            #expect(Self.bottleLines(cellar, zone) == Self.expected(Self.bottleKeys, item.bottles, zone), about)
            #expect(Self.discoveryLines(cellar, zone) == Self.expected(Self.discoveryKeys, item.discoveries ?? [], zone), about)

            let export = CellarExport.csv(cellar, timeZone: zone)
            #expect(export.hasPrefix(CellarExport.csvColumns.joined(separator: ",") + "\n"))
            if let lines = item.exportLines {
                #expect(export.split(separator: "\n").dropFirst().map(String.init).sorted() == lines.sorted(), about)
            }
            guard item.roundTrip else { continue }
            // What Encave writes, it reads back the same (into a cellar with the same racks).
            var copy = Cellar()
            copy.racks = racks
            let again = try copy.importCSV(export, now: Self.now, timeZone: zone)
            #expect(again.winesAdded == summary.winesAdded && again.bottlesAdded == summary.bottlesAdded, about)
            #expect(Self.wineLines(copy, zone) == Self.wineLines(cellar, zone), about)
            #expect(Self.bottleLines(copy, zone) == Self.bottleLines(cellar, zone), about)
            #expect(CellarExport.csv(copy, timeZone: zone).split(separator: "\n").sorted()
                    == export.split(separator: "\n").sorted(), about)
        }
    }
}
