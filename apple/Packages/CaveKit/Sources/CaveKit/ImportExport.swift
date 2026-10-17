import Foundation

/// Full backup format: the whole cellar in a small envelope.
public struct CellarBackup: Codable, Sendable {
    public var format: String
    public var version: Int
    public var exportedAt: Date
    public var cellar: Cellar

    public init(cellar: Cellar, exportedAt: Date = .now) {
        format = "encave"
        version = Cellar.currentSchemaVersion
        self.exportedAt = exportedAt
        self.cellar = cellar
    }
}

public enum ImportError: Error, Equatable {
    case notAnEncaveBackup
    case emptyFile
    case missingNameColumn
}

/// Result of an import, for the confirmation message.
public struct ImportSummary: Equatable, Sendable {
    public var winesAdded: Int
    public var bottlesAdded: Int
    public var rowsSkipped: Int
}

public enum CellarExport {
    // MARK: JSON

    public static func json(_ cellar: Cellar, exportedAt: Date = .now) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        return try encoder.encode(CellarBackup(cellar: cellar, exportedAt: exportedAt))
    }

    public static func backup(fromJSON data: Data) throws -> CellarBackup {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        guard let backup = try? decoder.decode(CellarBackup.self, from: data), backup.format == "encave" else {
            throw ImportError.notAnEncaveBackup
        }
        return backup
    }

    // MARK: CSV

    public static let csvColumns = [
        "name", "producer", "vintage", "color", "region", "country", "appellation", "grapes",
        "drink_from", "drink_until", "status", "rack", "slot", "storage",
        "purchase_date", "price", "currency", "purchase_place", "received_from", "size_ml",
        "rating", "tasted_on", "note",
    ]

    /// One row per bottle. Includes consumed bottles when `includeHistory` is true.
    public static func csv(_ cellar: Cellar, includeHistory: Bool = true) -> String {
        let day = dayFormatter()
        let bottles = cellar.bottles.values
            .filter { includeHistory || $0.status == .inCellar }
            .sorted { a, b in
                let wa = cellar.wines[a.wineID], wb = cellar.wines[b.wineID]
                if let wa, let wb, wa.id != wb.id { return Cellar.nameOrder(wa, wb) }
                return a.addedAt < b.addedAt
            }
        var lines = [csvColumns.joined(separator: ",")]
        for bottle in bottles {
            guard let wine = cellar.wines[bottle.wineID] else { continue }
            let rack = bottle.slot.flatMap { cellar.rack($0.rackID) }
            let fields: [String?] = [
                wine.name, wine.producer, wine.vintage.map(String.init), wine.color?.rawValue,
                wine.region, wine.country, wine.appellation, wine.grapes.joined(separator: "; "),
                wine.drinkFrom.map(String.init), wine.drinkUntil.map(String.init), bottle.status.rawValue,
                rack?.name, bottle.slot.flatMap { slot in rack?.label(for: slot) }, bottle.storageNote,
                bottle.purchaseDate.map(day.string(from:)), bottle.purchasePrice.map { "\($0)" }, bottle.currency,
                bottle.purchasePlace, bottle.receivedFrom, String(bottle.sizeML),
                bottle.tasting?.rating.map(String.init), bottle.tasting.map { day.string(from: $0.date) },
                bottle.tasting?.note,
            ]
            lines.append(fields.map { escape($0 ?? "") }.joined(separator: ","))
        }
        return lines.joined(separator: "\n") + "\n"
    }

    static func escape(_ field: String) -> String {
        guard field.contains(where: { $0 == "," || $0 == "\"" || $0 == "\n" || $0 == "\r" || $0 == ";" }) else { return field }
        return "\"" + field.replacingOccurrences(of: "\"", with: "\"\"") + "\""
    }

    static func dayFormatter() -> DateFormatter {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter
    }
}

public extension Cellar {
    /// Adds everything from a backup that is not already in this cellar (by id).
    @discardableResult
    mutating func merge(_ other: Cellar) -> ImportSummary {
        var wineCount = 0, bottleCount = 0
        for newRack in other.racks where self.rack(newRack.id) == nil { racks.append(newRack) }
        for (id, wine) in other.wines where wines[id] == nil {
            wines[id] = wine
            wineCount += 1
        }
        for (id, bottle) in other.bottles where bottles[id] == nil {
            var copy = bottle
            if let slot = copy.slot, self.bottle(at: slot) != nil { copy.slot = nil }
            bottles[id] = copy
            bottleCount += 1
        }
        return ImportSummary(winesAdded: wineCount, bottlesAdded: bottleCount, rowsSkipped: 0)
    }

    /// Imports a CSV in the export format (column order may vary; only "name" is required).
    /// A "quantity" column, if present, creates that many bottles per row.
    @discardableResult
    mutating func importCSV(_ text: String, now: Date = .now) throws -> ImportSummary {
        let rows = CSVParser.parse(text)
        guard let header = rows.first else { throw ImportError.emptyFile }
        let columns = header.map { $0.trimmingCharacters(in: .whitespaces).lowercased().replacingOccurrences(of: " ", with: "_") }
        guard let nameIndex = columns.firstIndex(of: "name") else { throw ImportError.missingNameColumn }
        let day = CellarExport.dayFormatter()
        var winesByKey = Dictionary(wines.values.map { ($0.identityKey, $0) }, uniquingKeysWith: { a, _ in a })
        var summary = ImportSummary(winesAdded: 0, bottlesAdded: 0, rowsSkipped: 0)

        for row in rows.dropFirst() {
            func value(_ column: String) -> String? {
                guard let index = columns.firstIndex(of: column), index < row.count else { return nil }
                let trimmed = row[index].trimmingCharacters(in: .whitespacesAndNewlines)
                return trimmed.isEmpty ? nil : trimmed
            }
            guard nameIndex < row.count, let name = value("name") else {
                if row.contains(where: { !$0.trimmingCharacters(in: .whitespaces).isEmpty }) { summary.rowsSkipped += 1 }
                continue
            }
            var wine = Wine(
                name: name, producer: value("producer"), vintage: value("vintage").flatMap { Int($0) },
                color: value("color").flatMap { WineColor(rawValue: $0.lowercased()) }, region: value("region"),
                country: value("country"), appellation: value("appellation"),
                grapes: (value("grapes") ?? "").split(separator: ";").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty },
                drinkFrom: value("drink_from").flatMap { Int($0) }, drinkUntil: value("drink_until").flatMap { Int($0) }
            )
            if let existing = winesByKey[wine.identityKey] {
                wine = existing
            } else {
                wines[wine.id] = wine
                winesByKey[wine.identityKey] = wine
                summary.winesAdded += 1
            }

            let quantity = max(1, value("quantity").flatMap { Int($0) } ?? 1)
            for _ in 0..<quantity {
                var bottle = Bottle(
                    wineID: wine.id, storageNote: value("storage"), addedAt: now,
                    purchaseDate: value("purchase_date").flatMap(day.date(from:)),
                    purchasePrice: value("price").flatMap { Decimal(string: $0.replacingOccurrences(of: ",", with: ".")) },
                    currency: value("currency") ?? settings.currency, purchasePlace: value("purchase_place"),
                    receivedFrom: value("received_from"), sizeML: value("size_ml").flatMap { Int($0) } ?? 750
                )
                let status = value("status").flatMap { Bottle.Status(rawValue: $0) } ?? .inCellar
                if status == .consumed {
                    bottle.status = .consumed
                    bottle.tasting = Tasting(date: value("tasted_on").flatMap(day.date(from:)) ?? now,
                                             rating: value("rating").flatMap { Int($0) }, note: value("note"))
                } else if status == .gone {
                    bottle.status = .gone
                } else if let rackName = value("rack"), let label = value("slot"),
                          let rack = racks.first(where: { $0.name.caseInsensitiveCompare(rackName) == .orderedSame }),
                          let slot = rack.slot(forLabel: label), self.bottle(at: slot) == nil {
                    bottle.slot = slot
                } else if let rackName = value("rack") {
                    let label = [rackName, value("slot")].compactMap { $0 }.joined(separator: " · ")
                    bottle.storageNote = bottle.storageNote ?? label
                }
                bottles[bottle.id] = bottle
                summary.bottlesAdded += 1
            }
        }
        return summary
    }
}

public extension Rack {
    /// Parses "B3" back into a slot of this rack.
    func slot(forLabel label: String) -> Slot? {
        let upper = label.trimmingCharacters(in: .whitespaces).uppercased()
        let letters = upper.prefix { $0.isLetter }
        guard !letters.isEmpty, let column = Int(upper.dropFirst(letters.count)) else { return nil }
        var row = 0
        for scalar in letters.unicodeScalars {
            guard (65...90).contains(scalar.value) else { return nil }
            row = row * 26 + Int(scalar.value - 64)
        }
        let slot = Slot(rackID: id, row: row - 1, column: column - 1)
        guard (0..<rows).contains(slot.row), (0..<columns).contains(slot.column) else { return nil }
        return slot
    }
}

/// Minimal RFC 4180 CSV parser. Accepts "," or ";" separators (auto-detected).
public enum CSVParser {
    public static func parse(_ text: String) -> [[String]] {
        let body = text.hasPrefix("\u{FEFF}") ? String(text.dropFirst()) : text
        let firstLine = body.prefix { $0 != "\n" && $0 != "\r" }
        let separator: Character = firstLine.filter { $0 == ";" }.count > firstLine.filter { $0 == "," }.count ? ";" : ","
        var rows: [[String]] = []
        var row: [String] = []
        var field = ""
        var inQuotes = false
        var iterator = Array(body)
        var index = 0
        while index < iterator.count {
            let char = iterator[index]
            if inQuotes {
                if char == "\"" {
                    if index + 1 < iterator.count, iterator[index + 1] == "\"" {
                        field.append("\"")
                        index += 1
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(char)
                }
            } else if char == "\"" {
                inQuotes = true
            } else if char == separator {
                row.append(field)
                field = ""
            } else if char == "\n" || char == "\r" || char == "\r\n" {
                row.append(field)
                rows.append(row)
                row = []
                field = ""
                if char == "\r", index + 1 < iterator.count, iterator[index + 1] == "\n" { index += 1 }
            } else {
                field.append(char)
            }
            index += 1
        }
        if !field.isEmpty || !row.isEmpty {
            row.append(field)
            rows.append(row)
        }
        iterator.removeAll()
        return rows.filter { !($0.count == 1 && $0[0].isEmpty) }
    }
}
