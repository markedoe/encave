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
    /// Made by a newer version of Encave, in a format this one does not know.
    case newerVersion
    /// A ZIP file that is compressed, encrypted or split: not an archive Encave makes.
    case unsupportedArchive
    /// An archive whose cellar could not be read in full (cut short, or changed).
    case damagedArchive
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

    /// Reads a backup. One made by a newer version of Encave (a higher `version`) is
    /// refused before anything else is read: its fields may have changed.
    public static func backup(fromJSON data: Data) throws -> CellarBackup {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        guard let envelope = try? decoder.decode(Envelope.self, from: data), envelope.format == "encave",
              let version = envelope.version else {
            throw ImportError.notAnEncaveBackup
        }
        guard version <= Cellar.currentSchemaVersion else { throw ImportError.newerVersion }
        guard let backup = try? decoder.decode(CellarBackup.self, from: data) else { throw ImportError.notAnEncaveBackup }
        return backup
    }

    /// What a backup file says it is, read before the rest.
    struct Envelope: Decodable {
        var format: String?
        var version: Int?
    }

    // MARK: CSV

    public static let csvColumns = [
        "name", "producer", "vintage", "color", "region", "country", "appellation", "grapes",
        "drink_from", "drink_until", "status", "rack", "slot", "location", "storage",
        "purchase_date", "price", "currency", "purchase_place", "received_from", "size_ml",
        "rating", "tasted_on", "note", "expected_on", "tags", "reserved_for",
        "current_price", "current_price_currency", "current_price_date",
    ]

    /// One row per bottle, including bottles on order. Includes consumed and
    /// given-away bottles when `includeHistory` is true. Dates are days of `timeZone`,
    /// the calendar day the user saw when entering them. Next to the technical "slot"
    /// ("B3-2"), "location" names the position for people, "Cabinet · Shelf 1, position 6":
    /// `placeName` gives it in the person's language (English when nil). The import reads
    /// the "rack" column before it, so "location" is ignored there.
    public static func csv(_ cellar: Cellar, includeHistory: Bool = true, timeZone: TimeZone = .current,
                           placeName: ((Rack, Slot) -> String)? = nil) -> String {
        let day = dayFormatter(timeZone: timeZone)
        let bottles = cellar.bottles.values
            .filter { includeHistory || $0.status == .inCellar || $0.status == .ordered }
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
                rack?.name, bottle.slot.flatMap { slot in rack?.label(for: slot) },
                bottle.slot.flatMap { slot in rack.map { "\($0.name) · \(placeName?($0, slot) ?? $0.placeName(for: slot))" } },
                bottle.storageNote,
                bottle.purchaseDate.map(day.string(from:)), bottle.purchasePrice.map(price), bottle.currency,
                bottle.purchasePlace, bottle.receivedFrom, String(bottle.sizeML),
                bottle.tasting?.rating.map(String.init), bottle.tasting.map { day.string(from: $0.date) },
                bottle.tasting?.note, bottle.expectedAt.map(day.string(from:)), wine.tags?.joined(separator: "; "),
                bottle.reservedFor,
                wine.currentPrice.map(price), wine.currentPrice == nil ? nil : wine.currentPriceCurrency,
                wine.currentPrice == nil ? nil : wine.currentPriceDate.map(day.string(from:)),
            ]
            lines.append(fields.map { escape($0 ?? "") }.joined(separator: ","))
        }
        return lines.joined(separator: "\n") + "\n"
    }

    static func escape(_ field: String) -> String {
        // A spreadsheet runs a cell that starts with "=", "+", "-" or "@" as a formula: such
        // text (from any device of the cellar) is written after a quote mark, which the
        // import removes. Encave writes no negative numbers.
        let field = field.first.map { "=+-@".contains($0) } == true ? "'" + field : field
        // Scalars, not Characters: "\r\n" is one Character, neither "\r" nor "\n".
        guard field.unicodeScalars.contains(where: { $0 == "," || $0 == "\"" || $0 == "\n" || $0 == "\r" || $0 == ";" })
        else { return field }
        return "\"" + field.replacingOccurrences(of: "\"", with: "\"\"") + "\""
    }

    /// A price rounded to the cent, as the CSV export writes and reads it ("8.333" is 8.33).
    public static func rounded(_ price: Decimal) -> Decimal {
        var value = price, result = Decimal()
        NSDecimalRound(&result, &value, 2, .plain)
        // Without trailing zeros (10, not 10.00), as the Kotlin core keeps it.
        return Decimal(string: withoutTrailingZeros("\(result)"), locale: Locale(identifier: "en_US_POSIX")) ?? result
    }

    /// "8.33", "120.5", "18".
    static func price(_ value: Decimal) -> String {
        withoutTrailingZeros("\(rounded(value))")
    }

    /// "10.00" → "10", "8.50" → "8.5".
    private static func withoutTrailingZeros(_ number: String) -> String {
        guard number.contains(".") else { return number }
        var text = number
        while text.hasSuffix("0") { text.removeLast() }
        if text.hasSuffix(".") { text.removeLast() }
        return text
    }

    static func dayFormatter(timeZone: TimeZone = .current) -> DateFormatter {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = timeZone
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter
    }
}

public extension Cellar {
    /// Adds everything from a backup that is not already in this cellar (by id), and the saved
    /// searches it does not have (by id and name). A bottle whose place is taken, or no longer
    /// in its rack (made smaller since), is added without a place.
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
            if let slot = copy.slot, self.bottle(at: slot) != nil || !(self.rack(slot.rackID)?.contains(slot) ?? false) {
                copy.slot = nil
            }
            bottles[id] = copy
            bottleCount += 1
        }
        for (id, discovery) in other.discoveries where discoveries[id] == nil && wines[discovery.wineID] != nil {
            discoveries[id] = discovery
        }
        let searches = settings.savedSearches ?? []
        let ids = Set(searches.map(\.id)), names = Set(searches.map { $0.name.lowercased() })
        let missing = (other.settings.savedSearches ?? []).filter { !ids.contains($0.id) && !names.contains($0.name.lowercased()) }
        if !missing.isEmpty { settings.savedSearches = SavedSearch.ordered(searches + missing) }
        return ImportSummary(winesAdded: wineCount, bottlesAdded: bottleCount, rowsSkipped: 0)
    }

    /// Imports a CSV: Encave's own export, or a spreadsheet or another app's export
    /// (CellarTracker, Vivino…). Columns are recognized by name in English or French,
    /// in any order; only a wine name is required. A quantity column creates that
    /// many bottles per row. A Vivino scan history (a "scan date" column and no
    /// quantity) becomes discoveries rather than bottles.
    /// Dates ("2024-03-10") are days of `timeZone`, as the export writes them.
    @discardableResult
    mutating func importCSV(_ text: String, now: Date = .now, timeZone: TimeZone = .current) throws -> ImportSummary {
        let rows = CSVParser.parse(text)
        guard let header = rows.first else { throw ImportError.emptyFile }
        let columns = header.map(CSVColumns.canonical)
        guard let nameIndex = columns.firstIndex(of: "name") else { throw ImportError.missingNameColumn }
        let day = CellarExport.dayFormatter(timeZone: timeZone)
        if columns.contains("scan_date"), !columns.contains("quantity") {
            return importDiscoveries(rows: rows, columns: columns, nameIndex: nameIndex, now: now, day: day,
                                     timeZone: timeZone)
        }
        // Encave's own columns ("price", not "Prix") hold plain numbers: "8.333" is not 8,333.
        func own(_ column: String) -> Bool {
            guard let index = columns.firstIndex(of: column) else { return false }
            return header[index].trimmingCharacters(in: .whitespaces) == column
        }
        let plainPrice = own("price"), plainCurrentPrice = own("current_price")
        var winesByKey = Dictionary(wines.values.map { ($0.identityKey, $0) }, uniquingKeysWith: { a, _ in a })
        var summary = ImportSummary(winesAdded: 0, bottlesAdded: 0, rowsSkipped: 0)

        for row in rows.dropFirst() {
            func value(_ column: String) -> String? {
                guard let index = columns.firstIndex(of: column), index < row.count else { return nil }
                return CSVColumns.cleaned(row[index])
            }
            guard nameIndex < row.count, let name = value("name") else {
                if row.contains(where: { !$0.trimmingCharacters(in: .whitespaces).isEmpty }) { summary.rowsSkipped += 1 }
                continue
            }
            var wine = CSVColumns.wine(name: name, value: value, day: day, plainPrice: plainCurrentPrice)
            if let existing = winesByKey[wine.identityKey] {
                wine = existing
            } else {
                wines[wine.id] = wine
                winesByKey[wine.identityKey] = wine
                summary.winesAdded += 1
            }

            let quantity = CSVColumns.quantity(value("quantity"))
            for _ in 0..<quantity {
                var bottle = Bottle(
                    wineID: wine.id, storageNote: value("storage"), addedAt: now,
                    purchaseDate: value("purchase_date").flatMap { CSVColumns.day($0, day) },
                    purchasePrice: value("price").flatMap { CSVColumns.price($0, plain: plainPrice) },
                    currency: value("currency") ?? settings.currency, purchasePlace: value("purchase_place"),
                    receivedFrom: value("received_from"), sizeML: value("size_ml").flatMap(CSVColumns.size) ?? 750
                )
                bottle.reservedFor = value("reserved_for")
                let status = value("status").flatMap { Bottle.Status(rawValue: $0) } ?? .inCellar
                if status == .consumed {
                    bottle.status = .consumed
                    bottle.tasting = Tasting(date: value("tasted_on").flatMap { CSVColumns.day($0, day) } ?? now,
                                             rating: value("rating").flatMap(CSVColumns.rating), note: value("note"))
                } else if status == .gone {
                    bottle.status = .gone
                } else if status == .ordered {
                    bottle.status = .ordered
                    bottle.expectedAt = value("expected_on").flatMap { CSVColumns.day($0, day) }
                } else if let rackName = value("rack"), let label = value("slot"),
                          let rack = racks.first(where: { $0.name.caseInsensitiveCompare(rackName) == .orderedSame }),
                          let wanted = rack.slot(forLabel: label),
                          let slot = self.bottle(at: wanted) == nil ? wanted : freeSlot(in: wanted.cell) {
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

extension Cellar {
    /// Rows of a tasting history: each becomes a discovery of the wine.
    mutating func importDiscoveries(rows: [[String]], columns: [String], nameIndex: Int, now: Date,
                                    day: DateFormatter, timeZone: TimeZone) -> ImportSummary {
        var summary = ImportSummary(winesAdded: 0, bottlesAdded: 0, rowsSkipped: 0)
        for row in rows.dropFirst() {
            func value(_ column: String) -> String? {
                guard let index = columns.firstIndex(of: column), index < row.count else { return nil }
                return CSVColumns.cleaned(row[index])
            }
            guard nameIndex < row.count, let name = value("name") else {
                if row.contains(where: { !$0.trimmingCharacters(in: .whitespaces).isEmpty }) { summary.rowsSkipped += 1 }
                continue
            }
            let wine = CSVColumns.wine(name: name, value: value, day: day)
            let known = wines.values.contains { $0.identityKey == wine.identityKey }
            let discovery = Discovery(
                wineID: wine.id, date: value("scan_date").flatMap { CSVColumns.date($0, timeZone: timeZone) } ?? now,
                place: value("place"), rating: value("rating").flatMap(CSVColumns.rating), note: value("note"),
                price: value("price").flatMap { CSVColumns.price($0, plain: false) },
                currency: value("currency") ?? settings.currency
            )
            addDiscovery(discovery, wine: wine)
            if !known { summary.winesAdded += 1 }
        }
        return summary
    }
}

/// Column names and values of other apps' and spreadsheets' exports.
enum CSVColumns {
    /// Header → the name used by Encave's own export.
    static let synonyms: [String: String] = [
        "name": "name", "wine": "name", "winename": "name", "nom": "name", "vin": "name", "cuvee": "name",
        "producer": "producer", "winery": "producer", "domaine": "producer", "producteur": "producer", "estate": "producer",
        "vintage": "vintage", "millesime": "vintage", "year": "vintage", "annee": "vintage",
        "color": "color", "colour": "color", "couleur": "color", "type": "color", "winetype": "color",
        "region": "region", "country": "country", "pays": "country", "appellation": "appellation",
        "grapes": "grapes", "varietal": "grapes", "grape": "grapes", "cepage": "grapes", "cepages": "grapes",
        "drinkfrom": "drink_from", "beginconsume": "drink_from", "boirea": "drink_from",
        "drinkuntil": "drink_until", "endconsume": "drink_until", "boirejusqua": "drink_until",
        "drinkingwindow": "drinking_window", "fenetrededegustation": "drinking_window",
        "quantity": "quantity", "qty": "quantity", "quantite": "quantity", "totalquantity": "quantity", "bottles": "quantity",
        "price": "price", "prix": "price", "wineprice": "price", "purchaseprice": "price",
        "currency": "currency", "devise": "currency",
        "rack": "rack", "location": "rack", "casier": "rack", "emplacement": "rack", "slot": "slot", "bin": "slot", "case": "slot",
        "storage": "storage", "status": "status", "purchasedate": "purchase_date", "purchaseplace": "purchase_place",
        "receivedfrom": "received_from", "sizeml": "size_ml", "tastedon": "tasted_on",
        "rating": "rating", "yourrating": "rating", "note": "note", "notes": "note", "yourreview": "note",
        "personalnote": "note", "commentaire": "note",
        "currentprice": "current_price", "currentvalue": "current_price", "prixactuel": "current_price",
        "valeuractuelle": "current_price", "currentpricecurrency": "current_price_currency",
        "currentpricedate": "current_price_date",
        "tags": "tags", "tag": "tags", "reservedfor": "reserved_for", "reserved": "reserved_for",
        "expectedon": "expected_on", "deliverydate": "expected_on", "livraison": "expected_on",
        "scandate": "scan_date", "scanreviewlocation": "place", "place": "place", "lieu": "place",
    ]

    /// "Wine Name", "wine_name" and "Millésime" become "name" and "vintage".
    static func canonical(_ header: String) -> String {
        let folded = header.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: Locale(identifier: "en"))
            .lowercased().filter { $0.isLetter || $0.isNumber }
        return synonyms[folded] ?? header.trimmingCharacters(in: .whitespaces).lowercased().replacingOccurrences(of: " ", with: "_")
    }

    /// A cell without surrounding spaces, and without the quote mark the export puts
    /// before text a spreadsheet would run as a formula; nil if empty.
    static func cleaned(_ cell: String) -> String? {
        var text = cell.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.hasPrefix("'"), let next = text.dropFirst().first, "=+-@".contains(next) { text.removeFirst() }
        return text.isEmpty ? nil : text
    }

    /// Bottles a row creates: 1 to `maxQuantity` (a number larger than that is a mistake, not bottles).
    static let maxQuantity = 999

    static func quantity(_ text: String?) -> Int {
        guard let value = text.flatMap(number), !value.isNaN else { return 1 }
        return integer(min(Double(maxQuantity), max(1, value))) ?? 1
    }

    /// "750", "750.0"; nil unless positive.
    static func size(_ text: String) -> Int? {
        guard let value = number(text).flatMap(integer), (1...100_000).contains(value) else { return nil }
        return value
    }

    /// The nearest integer, or nil if there is none (NaN, infinite, too large).
    static func integer(_ value: Double) -> Int? {
        Int(exactly: value.rounded())
    }

    static func wine(name: String, value: (String) -> String?, day: DateFormatter, plainPrice: Bool = false) -> Wine {
        var from = value("drink_from").flatMap(year), until = value("drink_until").flatMap(year)
        if from == nil, until == nil, let window = value("drinking_window") {
            let years = window.split { !$0.isNumber }.compactMap { Int($0) }.filter { (1900...2200).contains($0) }
            from = years.first; until = years.count > 1 ? years[1] : nil
        }
        var wine = Wine(
            name: name, producer: value("producer"), vintage: value("vintage").flatMap(year),
            color: value("color").flatMap(color), region: value("region"),
            country: value("country"), appellation: value("appellation"),
            grapes: (value("grapes") ?? "").split(whereSeparator: { $0 == ";" || $0 == "," })
                .map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty },
            drinkFrom: from, drinkUntil: until,
            tags: value("tags")?.split(whereSeparator: { $0 == ";" || $0 == "," }).map(String.init)
        )
        if let price = value("current_price").flatMap({ CSVColumns.price($0, plain: plainPrice) }), price > 0 {
            wine.currentPrice = price
            wine.currentPriceCurrency = value("current_price_currency") ?? value("currency")
            wine.currentPriceDate = value("current_price_date").flatMap { CSVColumns.day($0, day) }
        }
        return wine
    }

    /// "2015", "2015.0"; not "NV" or "1001" (CellarTracker writes 1001 for non-vintage).
    static func year(_ text: String) -> Int? {
        guard let value = number(text).flatMap(integer), (1800...2200).contains(value) else { return nil }
        return value
    }

    /// A price rounded to the cent. In Encave's own columns (`plain`), "8.333" is 8.33; elsewhere
    /// a three-digit tail is thousands ("1.234" is 1234), as on a web page.
    static func price(_ text: String, plain: Bool) -> Decimal? {
        let isPlain = plain && text.range(of: #"^[0-9]+(\.[0-9]+)?$"#, options: .regularExpression) != nil
        let value = isPlain ? Decimal(string: text, locale: Locale(identifier: "en_US_POSIX")) : PriceExtractor.decimal(text)
        return value.map(CellarExport.rounded)
    }

    static func number(_ text: String) -> Double? {
        Double(text.replacingOccurrences(of: ",", with: ".").filter { $0.isASCIIDigit || $0 == "." || $0 == "-" })
    }

    /// 1 to 5 (decimals rounded); 100-point scores are converted.
    static func rating(_ text: String) -> Int? {
        guard let value = number(text), value > 0 else { return nil }
        let five = min(5, value > 5 ? value / 20 : value)
        return min(5, max(1, Int(five.rounded())))
    }

    static func color(_ text: String) -> WineColor? {
        let folded = text.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: Locale(identifier: "en")).lowercased()
        let table: [(String, WineColor)] = [
            ("sparkling", .sparkling), ("effervescent", .sparkling), ("champagne", .sparkling), ("petillant", .sparkling),
            ("rose", .rose), ("dessert", .dessert), ("sweet", .dessert), ("liquoreux", .dessert), ("moelleux", .dessert),
            ("fortified", .fortified), ("port", .fortified), ("porto", .fortified), ("fortifie", .fortified),
            ("orange", .orange), ("red", .red), ("rouge", .red), ("white", .white), ("blanc", .white),
        ]
        return table.first { folded.contains($0.0) }?.1
    }

    /// "2024-03-10" as Encave writes it, or a date with a time or in another order
    /// ("10/03/2024"), as other apps write them.
    static func day(_ text: String, _ formatter: DateFormatter) -> Date? {
        formatter.date(from: text) ?? date(text, timeZone: formatter.timeZone)
    }

    /// Other apps' dates in `timeZone`: "2025-07-14 19:30:00", "2024-3-5", "10/03/2024" (day
    /// first, else month first); nil for a day that does not exist ("2024-02-30").
    static func date(_ text: String, timeZone: TimeZone) -> Date? {
        for format in ["yyyy-MM-dd HH:mm:ss", "yyyy-M-d", "d/M/yyyy", "M/d/yyyy"] {
            let formatter = DateFormatter()
            formatter.calendar = Calendar(identifier: .gregorian)
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.timeZone = timeZone
            formatter.isLenient = false
            formatter.dateFormat = format
            if let date = formatter.date(from: text) { return date }
        }
        return nil
    }
}

public extension Rack {
    /// Parses "B3" or "B3-2" back into a slot of this rack.
    func slot(forLabel label: String) -> Slot? {
        let parts = label.trimmingCharacters(in: .whitespaces).uppercased().split(separator: "-", maxSplits: 1)
        guard let first = parts.first else { return nil }
        let upper = String(first)
        let letters = upper.prefix { $0.isLetter }
        let digits = upper.dropFirst(letters.count)
        // Short labels only ("AB12-3"): longer ones are no place of a rack, and would overflow.
        guard (1...3).contains(letters.count), (1...4).contains(digits.count), digits.allSatisfy(\.isASCIIDigit),
              let column = Int(digits) else { return nil }
        var depth = 0
        if parts.count > 1 {
            guard (1...4).contains(parts[1].count), parts[1].allSatisfy(\.isASCIIDigit), let value = Int(parts[1])
            else { return nil }
            depth = value - 1
        }
        var row = 0
        for scalar in letters.unicodeScalars {
            guard (65...90).contains(scalar.value) else { return nil }
            row = row * 26 + Int(scalar.value - 64)
        }
        let slot = Slot(rackID: id, row: row - 1, column: column - 1, depth: depth)
        guard contains(slot) else { return nil }
        return slot
    }
}

/// The text of a CSV file: UTF-8 with or without a byte order mark, UTF-16 with one, else
/// Windows-1252 (French Excel's "CSV (point-virgule)", CellarTracker). The five bytes
/// Windows-1252 leaves undefined (0x81, 0x8D, 0x8F, 0x90, 0x9D) become the same code point,
/// as in Latin-1. A byte order mark is removed. The Kotlin core decodes the same way.
public enum CSVText {
    public static func decode(_ data: Data) -> String {
        let bytes = [UInt8](data)
        if bytes.starts(with: [0xFF, 0xFE]) { return utf16(bytes, littleEndian: true) }
        if bytes.starts(with: [0xFE, 0xFF]) { return utf16(bytes, littleEndian: false) }
        let body = bytes.starts(with: [0xEF, 0xBB, 0xBF]) ? bytes[3...] : bytes[...]
        return utf8(body) ?? windows1252(body)
    }

    /// Strict: nil unless every byte is part of a valid UTF-8 sequence.
    static func utf8(_ bytes: ArraySlice<UInt8>) -> String? {
        var scalars = String.UnicodeScalarView()
        var iterator = bytes.makeIterator()
        var decoder = UTF8()
        while true {
            switch decoder.decode(&iterator) {
            case let .scalarValue(scalar): scalars.append(scalar)
            case .emptyInput: return String(scalars)
            case .error: return nil
            }
        }
    }

    /// After the byte order mark; an unpaired surrogate or a last odd byte is U+FFFD.
    static func utf16(_ bytes: [UInt8], littleEndian: Bool) -> String {
        let count = (bytes.count - 2) / 2
        func unit(_ index: Int) -> UInt32 {
            let first = UInt32(bytes[2 + 2 * index]), second = UInt32(bytes[3 + 2 * index])
            return littleEndian ? first | second << 8 : first << 8 | second
        }
        let replacement: Unicode.Scalar = "\u{FFFD}"
        var scalars = String.UnicodeScalarView()
        var index = 0
        while index < count {
            let high = unit(index)
            if (0xD800...0xDBFF).contains(high), index + 1 < count, (0xDC00...0xDFFF).contains(unit(index + 1)) {
                let value = 0x10000 + ((high - 0xD800) << 10) + (unit(index + 1) - 0xDC00)
                scalars.append(Unicode.Scalar(value) ?? replacement)
                index += 2
            } else {
                scalars.append(Unicode.Scalar(high) ?? replacement)
                index += 1
            }
        }
        if (bytes.count - 2) % 2 == 1 { scalars.append(replacement) }
        return String(scalars)
    }

    /// Code points of 0x80 to 0x9F; the other bytes are their own code point.
    static let high: [UInt32] = [
        0x20AC, 0x0081, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021, 0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0x008D, 0x017D, 0x008F,
        0x0090, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014, 0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0x009D, 0x017E, 0x0178,
    ]

    static func windows1252(_ bytes: ArraySlice<UInt8>) -> String {
        var scalars = String.UnicodeScalarView()
        for byte in bytes {
            let value = (0x80...0x9F).contains(byte) ? high[Int(byte) - 0x80] : UInt32(byte)
            if let scalar = Unicode.Scalar(value) { scalars.append(scalar) }
        }
        return String(scalars)
    }
}

/// Minimal RFC 4180 CSV parser. Accepts "," or ";" separators (auto-detected).
public enum CSVParser {
    public static func parse(_ text: String) -> [[String]] {
        let body = text.hasPrefix("\u{FEFF}") ? String(text.dropFirst()) : text
        // "\r\n" is one Character in Swift, neither "\n" nor "\r".
        let firstLine = body.prefix { $0 != "\n" && $0 != "\r" && $0 != "\r\n" }
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
