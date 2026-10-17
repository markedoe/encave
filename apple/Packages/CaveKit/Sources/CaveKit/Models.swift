import Foundation

/// Style of wine. Always shown with a label, never by color alone.
public enum WineColor: String, Codable, CaseIterable, Sendable {
    case red, white, rose, sparkling, dessert, fortified, orange
}

/// A wine: the invisible grouping behind bottles ("Château X 2015").
public struct Wine: Identifiable, Codable, Hashable, Sendable {
    public var id: UUID
    public var name: String
    public var producer: String?
    public var vintage: Int?
    public var color: WineColor?
    public var region: String?
    public var country: String?
    public var appellation: String?
    public var grapes: [String]
    /// First and last year of the drinking window, when known.
    public var drinkFrom: Int?
    public var drinkUntil: Int?

    public init(
        id: UUID = UUID(), name: String, producer: String? = nil, vintage: Int? = nil,
        color: WineColor? = nil, region: String? = nil, country: String? = nil,
        appellation: String? = nil, grapes: [String] = [], drinkFrom: Int? = nil, drinkUntil: Int? = nil
    ) {
        self.id = id; self.name = name; self.producer = producer; self.vintage = vintage
        self.color = color; self.region = region; self.country = country
        self.appellation = appellation; self.grapes = grapes
        self.drinkFrom = drinkFrom; self.drinkUntil = drinkUntil
    }

    /// "Château X 2015" or "Château X" for non-vintage wines.
    public var displayName: String {
        if let vintage { return "\(name) \(vintage)" }
        return name
    }
}

/// Where a bottle sits: rack, row and column (zero-based).
public struct Slot: Codable, Hashable, Sendable {
    public var rackID: UUID
    public var row: Int
    public var column: Int

    public init(rackID: UUID, row: Int, column: Int) {
        self.rackID = rackID; self.row = row; self.column = column
    }
}

/// A physical rack, modeled as a grid. Rows are lettered, columns numbered.
public struct Rack: Identifiable, Codable, Hashable, Sendable {
    public var id: UUID
    public var name: String
    public var rows: Int
    public var columns: Int

    public init(id: UUID = UUID(), name: String, rows: Int, columns: Int) {
        self.id = id; self.name = name
        self.rows = max(1, rows); self.columns = max(1, columns)
    }

    public var capacity: Int { rows * columns }

    /// "B3" for row 1, column 2.
    public func label(row: Int, column: Int) -> String {
        "\(Rack.rowLetters(row))\(column + 1)"
    }

    static func rowLetters(_ index: Int) -> String {
        var n = index
        var letters = ""
        repeat {
            letters = String(UnicodeScalar(UInt8(65 + n % 26))) + letters
            n = n / 26 - 1
        } while n >= 0
        return letters
    }
}

/// A record of drinking a bottle.
public struct Tasting: Codable, Hashable, Sendable {
    public var date: Date
    /// 1 to 5, optional.
    public var rating: Int?
    public var note: String?
    public var occasion: String?

    public init(date: Date = .now, rating: Int? = nil, note: String? = nil, occasion: String? = nil) {
        self.date = date
        self.rating = rating.map { min(5, max(1, $0)) }
        self.note = note; self.occasion = occasion
    }
}

/// One physical bottle. Each bottle is its own entity with a status, so two
/// people drinking "the last bottle" at once is a visible conflict, not a wrong count.
public struct Bottle: Identifiable, Codable, Hashable, Sendable {
    public enum Status: String, Codable, Sendable { case inCellar, consumed }

    public var id: UUID
    public var wineID: UUID
    public var status: Status
    public var slot: Slot?
    public var addedAt: Date
    public var purchasePrice: Decimal?
    public var currency: String?
    public var tasting: Tasting?

    public init(
        id: UUID = UUID(), wineID: UUID, status: Status = .inCellar, slot: Slot? = nil,
        addedAt: Date = .now, purchasePrice: Decimal? = nil, currency: String? = nil, tasting: Tasting? = nil
    ) {
        self.id = id; self.wineID = wineID; self.status = status; self.slot = slot
        self.addedAt = addedAt; self.purchasePrice = purchasePrice; self.currency = currency
        self.tasting = tasting
    }
}
