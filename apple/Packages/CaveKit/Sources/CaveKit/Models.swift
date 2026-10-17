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
    /// Free-form notes about the wine itself (not a tasting).
    public var notes: String?
    /// Identifier of a photo (label or bottle) stored by the app next to the cellar.
    public var photoID: String?

    public init(
        id: UUID = UUID(), name: String, producer: String? = nil, vintage: Int? = nil,
        color: WineColor? = nil, region: String? = nil, country: String? = nil,
        appellation: String? = nil, grapes: [String] = [], drinkFrom: Int? = nil, drinkUntil: Int? = nil,
        notes: String? = nil, photoID: String? = nil
    ) {
        self.id = id; self.name = name; self.producer = producer; self.vintage = vintage
        self.color = color; self.region = region; self.country = country
        self.appellation = appellation; self.grapes = grapes
        self.drinkFrom = drinkFrom; self.drinkUntil = drinkUntil; self.notes = notes
        self.photoID = photoID
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        producer = try c.decodeIfPresent(String.self, forKey: .producer)
        vintage = try c.decodeIfPresent(Int.self, forKey: .vintage)
        color = try c.decodeIfPresent(WineColor.self, forKey: .color)
        region = try c.decodeIfPresent(String.self, forKey: .region)
        country = try c.decodeIfPresent(String.self, forKey: .country)
        appellation = try c.decodeIfPresent(String.self, forKey: .appellation)
        grapes = try c.decodeIfPresent([String].self, forKey: .grapes) ?? []
        drinkFrom = try c.decodeIfPresent(Int.self, forKey: .drinkFrom)
        drinkUntil = try c.decodeIfPresent(Int.self, forKey: .drinkUntil)
        notes = try c.decodeIfPresent(String.self, forKey: .notes)
        photoID = try c.decodeIfPresent(String.self, forKey: .photoID)
    }

    /// "Château X 2015" or "Château X" for non-vintage wines.
    public var displayName: String {
        if let vintage { return "\(name) \(vintage)" }
        return name
    }

    /// Same wine label, used to merge duplicates on import ("same name, producer, vintage").
    public var identityKey: String {
        [name, producer ?? "", vintage.map(String.init) ?? ""]
            .map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
            .joined(separator: "|")
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

/// How the positions of a rack are drawn. The slot grid is the same; only the shape differs.
public enum RackLayout: String, Codable, CaseIterable, Sendable {
    /// Square cells in rows and columns.
    case grid
    /// Diamond (X-shaped) bins, rows offset like a brick wall.
    case diamond
}

/// A physical rack, modeled as a grid. Rows are lettered, columns numbered.
public struct Rack: Identifiable, Codable, Hashable, Sendable {
    public var id: UUID
    public var name: String
    public var rows: Int
    public var columns: Int
    public var layout: RackLayout

    public init(id: UUID = UUID(), name: String, rows: Int, columns: Int, layout: RackLayout = .grid) {
        self.id = id; self.name = name
        self.rows = max(1, rows); self.columns = max(1, columns)
        self.layout = layout
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        rows = max(1, try c.decode(Int.self, forKey: .rows))
        columns = max(1, try c.decode(Int.self, forKey: .columns))
        layout = try c.decodeIfPresent(RackLayout.self, forKey: .layout) ?? .grid
    }

    public var capacity: Int { rows * columns }

    /// "B3" for row 1, column 2.
    public func label(row: Int, column: Int) -> String {
        "\(Rack.rowLetters(row))\(column + 1)"
    }

    public func label(for slot: Slot) -> String { label(row: slot.row, column: slot.column) }

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

/// A record of drinking a bottle, from a quick rating to a full tasting note.
public struct Tasting: Codable, Hashable, Sendable {
    public var date: Date
    /// 1 to 5, optional.
    public var rating: Int?
    public var note: String?
    public var occasion: String?
    public var appearance: String?
    public var nose: String?
    public var palate: String?
    public var finish: String?
    public var foodPairing: String?

    public init(
        date: Date = .now, rating: Int? = nil, note: String? = nil, occasion: String? = nil,
        appearance: String? = nil, nose: String? = nil, palate: String? = nil, finish: String? = nil,
        foodPairing: String? = nil
    ) {
        self.date = date
        self.rating = rating.map { min(5, max(1, $0)) }
        self.note = note; self.occasion = occasion
        self.appearance = appearance; self.nose = nose; self.palate = palate; self.finish = finish
        self.foodPairing = foodPairing
    }

    /// True when any structured field beyond rating and note is filled.
    public var isDetailed: Bool {
        [appearance, nose, palate, finish, foodPairing].contains { !($0 ?? "").isEmpty }
    }
}

/// One physical bottle. Each bottle is its own entity with a status, so two
/// people drinking "the last bottle" at once is a visible conflict, not a wrong count.
public struct Bottle: Identifiable, Codable, Hashable, Sendable {
    public enum Status: String, Codable, Sendable {
        case inCellar
        case consumed
        /// Given away, sold or broken: no longer in the cellar, not drunk by you.
        case gone
    }

    public var id: UUID
    public var wineID: UUID
    public var status: Status
    public var slot: Slot?
    /// Where the bottle is when it is not in a rack ("Kitchen", "Box in garage").
    public var storageNote: String?
    public var addedAt: Date
    public var purchaseDate: Date?
    public var purchasePrice: Decimal?
    public var currency: String?
    public var purchasePlace: String?
    public var receivedFrom: String?
    /// Bottle size in millilitres (750 standard, 1500 magnum...).
    public var sizeML: Int
    public var tasting: Tasting?

    public init(
        id: UUID = UUID(), wineID: UUID, status: Status = .inCellar, slot: Slot? = nil,
        storageNote: String? = nil, addedAt: Date = .now, purchaseDate: Date? = nil,
        purchasePrice: Decimal? = nil, currency: String? = nil, purchasePlace: String? = nil,
        receivedFrom: String? = nil, sizeML: Int = 750, tasting: Tasting? = nil
    ) {
        self.id = id; self.wineID = wineID; self.status = status; self.slot = slot
        self.storageNote = storageNote; self.addedAt = addedAt; self.purchaseDate = purchaseDate
        self.purchasePrice = purchasePrice; self.currency = currency; self.purchasePlace = purchasePlace
        self.receivedFrom = receivedFrom; self.sizeML = sizeML; self.tasting = tasting
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        wineID = try c.decode(UUID.self, forKey: .wineID)
        status = try c.decodeIfPresent(Status.self, forKey: .status) ?? .inCellar
        slot = try c.decodeIfPresent(Slot.self, forKey: .slot)
        storageNote = try c.decodeIfPresent(String.self, forKey: .storageNote)
        addedAt = try c.decodeIfPresent(Date.self, forKey: .addedAt) ?? .now
        purchaseDate = try c.decodeIfPresent(Date.self, forKey: .purchaseDate)
        purchasePrice = try c.decodeIfPresent(Decimal.self, forKey: .purchasePrice)
        currency = try c.decodeIfPresent(String.self, forKey: .currency)
        purchasePlace = try c.decodeIfPresent(String.self, forKey: .purchasePlace)
        receivedFrom = try c.decodeIfPresent(String.self, forKey: .receivedFrom)
        sizeML = try c.decodeIfPresent(Int.self, forKey: .sizeML) ?? 750
        tasting = try c.decodeIfPresent(Tasting.self, forKey: .tasting)
    }
}

/// User preferences stored with the cellar.
public struct CellarSettings: Codable, Hashable, Sendable {
    /// ISO 4217 code used by default for prices ("EUR", "CHF"...).
    public var currency: String

    public init(currency: String = "EUR") { self.currency = currency }
}
