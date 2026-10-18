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
    /// Barcodes (EAN, UPC) seen on its bottles, to recognize it at the next scan.
    /// Nil rather than empty, so files of wines without barcodes stay as they were.
    public var barcodes: [String]?
    /// The user's own groups ("Gift", "Birth year"). Nil rather than empty, like barcodes.
    public var tags: [String]?
    /// More photos (back label, receipt, the bottle in its rack). Nil rather than empty.
    public var extraPhotoIDs: [String]?
    /// What a 75 cl bottle (or the wine's usual size) sells for today, accepted by the user
    /// from a web search or typed: the amount, its currency code and the day it was set.
    public var currentPrice: Decimal?
    public var currentPriceCurrency: String?
    public var currentPriceDate: Date?
    /// The picture shown for the wine instead of the label photo: one found on the
    /// internet or an illustration made on the device. Nil shows the label photo.
    public var coverPhotoID: String?

    public init(
        id: UUID = UUID(), name: String, producer: String? = nil, vintage: Int? = nil,
        color: WineColor? = nil, region: String? = nil, country: String? = nil,
        appellation: String? = nil, grapes: [String] = [], drinkFrom: Int? = nil, drinkUntil: Int? = nil,
        notes: String? = nil, photoID: String? = nil, barcodes: [String]? = nil, tags: [String]? = nil
    ) {
        self.id = id; self.name = name; self.producer = producer; self.vintage = vintage
        self.color = color; self.region = region; self.country = country
        self.appellation = appellation; self.grapes = grapes
        self.drinkFrom = drinkFrom; self.drinkUntil = drinkUntil; self.notes = notes
        self.photoID = photoID
        self.barcodes = barcodes?.isEmpty == true ? nil : barcodes
        self.tags = Wine.normalizedTags(tags)
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
        barcodes = try c.decodeIfPresent([String].self, forKey: .barcodes)
        tags = try c.decodeIfPresent([String].self, forKey: .tags)
        extraPhotoIDs = try c.decodeIfPresent([String].self, forKey: .extraPhotoIDs)
        currentPrice = try c.decodeIfPresent(Decimal.self, forKey: .currentPrice)
        currentPriceCurrency = try c.decodeIfPresent(String.self, forKey: .currentPriceCurrency)
        currentPriceDate = try c.decodeIfPresent(Date.self, forKey: .currentPriceDate)
        coverPhotoID = try c.decodeIfPresent(String.self, forKey: .coverPhotoID)
    }

    /// The label photo first, then the others, then the cover.
    public var allPhotoIDs: [String] { [photoID].compactMap { $0 } + (extraPhotoIDs ?? []) + [coverPhotoID].compactMap { $0 } }

    /// The picture to show for the wine: the cover if any, else the label photo.
    public var shownPhotoID: String? { coverPhotoID ?? photoID }

    /// Trimmed, without empty or duplicate tags (ignoring case); nil when none are left.
    public static func normalizedTags(_ tags: [String]?) -> [String]? {
        let clean = Discovery.normalized(tags ?? [])
        return clean.isEmpty ? nil : clean
    }

    /// True if the wine has this tag, ignoring case.
    public func hasTag(_ tag: String) -> Bool {
        (tags ?? []).contains { $0.caseInsensitiveCompare(tag) == .orderedSame }
    }

    /// Digits only: "3 760 1234 5678 9" and "3760123456789" are the same barcode.
    public static func normalizedBarcode(_ code: String) -> String {
        String(code.filter(\.isNumber).prefix(20))
    }

    /// Adds a barcode if it is new. Returns false if it was already known.
    @discardableResult
    public mutating func addBarcode(_ code: String) -> Bool {
        let normalized = Self.normalizedBarcode(code)
        guard normalized.count >= 6, !(barcodes ?? []).contains(normalized) else { return false }
        barcodes = (barcodes ?? []) + [normalized]
        return true
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

/// Where a bottle sits: rack, row and column (zero-based), and how deep in the cell
/// (front or back of a double-depth rack, or the place in a bin's pile).
public struct Slot: Codable, Hashable, Sendable {
    public var rackID: UUID
    public var row: Int
    public var column: Int
    /// 0 is the front (or the first bottle of a bin).
    public var depth: Int

    public init(rackID: UUID, row: Int, column: Int, depth: Int = 0) {
        self.rackID = rackID; self.row = row; self.column = column; self.depth = depth
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        rackID = try c.decode(UUID.self, forKey: .rackID)
        row = try c.decode(Int.self, forKey: .row)
        column = try c.decode(Int.self, forKey: .column)
        depth = try c.decodeIfPresent(Int.self, forKey: .depth) ?? 0
    }

    /// The cell this slot belongs to, whatever its depth.
    public var cell: RackCell { RackCell(rackID: rackID, row: row, column: column) }
}

/// One cell of a rack: a position in a grid, a place on a shelf or a whole bin.
public struct RackCell: Hashable, Sendable {
    public var rackID: UUID
    public var row: Int
    public var column: Int

    public init(rackID: UUID, row: Int, column: Int) {
        self.rackID = rackID; self.row = row; self.column = column
    }
}

/// The kind of storage, as it exists in real cellars.
public enum RackKind: String, Codable, CaseIterable, Sendable {
    /// Classic rack: one bottle per cell in rows and columns, possibly two deep.
    case grid
    /// Honeycomb rack: every other row is offset by half a bottle and holds one fewer.
    case staggered
    /// Wine fridge or cabinet: shelves holding a row of bottles, one or more deep.
    case shelves
    /// Diamond bins or crates: each bin holds several bottles in a pile.
    case bins
}

/// A place where bottles are stored: a rack, a wine fridge, a set of bins.
/// Rows (or shelves) are lettered from the top, cells numbered from the left.
public struct Rack: Identifiable, Codable, Hashable, Sendable {
    public var id: UUID
    public var name: String
    public var kind: RackKind
    /// Rows, shelves, or rows of bins.
    public var rows: Int
    /// Cells per row (the widest row for honeycomb racks).
    public var columns: Int
    /// Bottles one behind the other in a cell (grid and shelves).
    public var depth: Int
    /// Bottles per bin (bins only).
    public var binCapacity: Int
    /// Cells in each row when rows differ (a triangle top, an irregular cellar).
    public var rowColumns: [Int]?

    public static let maxDepth = 3

    public init(
        id: UUID = UUID(), name: String, kind: RackKind = .grid, rows: Int, columns: Int,
        depth: Int = 1, binCapacity: Int = 12, rowColumns: [Int]? = nil
    ) {
        self.id = id; self.name = name; self.kind = kind
        self.rows = max(1, rows); self.columns = max(1, columns)
        self.depth = min(Rack.maxDepth, max(1, depth)); self.binCapacity = max(1, binCapacity)
        self.rowColumns = rowColumns.map { $0.map { max(1, $0) } }
    }

    private enum CodingKeys: String, CodingKey {
        case id, name, kind, rows, columns, depth, binCapacity, rowColumns
        /// Version 0.1 and 0.2 had "grid" and a decorative "diamond" layout, both one bottle per cell.
        case layout
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        // Absent in files from version 0.1 and 0.2 (grids). An unknown value fails, like any enum.
        kind = try c.decodeIfPresent(RackKind.self, forKey: .kind) ?? .grid
        rows = max(1, try c.decode(Int.self, forKey: .rows))
        columns = max(1, try c.decode(Int.self, forKey: .columns))
        depth = min(Rack.maxDepth, max(1, try c.decodeIfPresent(Int.self, forKey: .depth) ?? 1))
        binCapacity = max(1, try c.decodeIfPresent(Int.self, forKey: .binCapacity) ?? 12)
        rowColumns = try c.decodeIfPresent([Int].self, forKey: .rowColumns)?.map { max(1, $0) }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(name, forKey: .name)
        try c.encode(kind, forKey: .kind)
        try c.encode(rows, forKey: .rows)
        try c.encode(columns, forKey: .columns)
        try c.encode(depth, forKey: .depth)
        try c.encode(binCapacity, forKey: .binCapacity)
        try c.encodeIfPresent(rowColumns, forKey: .rowColumns)
    }

    /// Cells in a row: custom widths first, then one fewer on offset honeycomb rows.
    public func columns(inRow row: Int) -> Int {
        if let rowColumns, rowColumns.indices.contains(row) { return rowColumns[row] }
        if kind == .staggered, !row.isMultiple(of: 2) { return max(1, columns - 1) }
        return columns
    }

    /// The widest row, for drawing.
    public var maxColumns: Int { (0..<rows).map(columns(inRow:)).max() ?? columns }

    /// Bottles that fit in one cell.
    public var positionsPerCell: Int { kind == .bins ? binCapacity : (kind == .staggered ? 1 : depth) }

    public var cellCount: Int { (0..<rows).reduce(0) { $0 + columns(inRow: $1) } }

    public var capacity: Int { cellCount * positionsPerCell }

    public func contains(_ slot: Slot) -> Bool {
        slot.rackID == id && (0..<rows).contains(slot.row)
            && (0..<columns(inRow: slot.row)).contains(slot.column)
            && (0..<positionsPerCell).contains(slot.depth)
    }

    public func contains(_ cell: RackCell) -> Bool {
        cell.rackID == id && (0..<rows).contains(cell.row) && (0..<columns(inRow: cell.row)).contains(cell.column)
    }

    public var cells: [RackCell] {
        (0..<rows).flatMap { row in (0..<columns(inRow: row)).map { RackCell(rackID: id, row: row, column: $0) } }
    }

    /// Every slot, in the order bottles are placed automatically: a bin is filled before
    /// the next one; elsewhere the front of a row is filled before the back.
    public var allSlots: [Slot] {
        (0..<rows).flatMap { row -> [Slot] in
            let width = columns(inRow: row)
            if kind == .bins {
                return (0..<width).flatMap { column in
                    (0..<positionsPerCell).map { Slot(rackID: id, row: row, column: column, depth: $0) }
                }
            }
            return (0..<positionsPerCell).flatMap { depth in
                (0..<width).map { Slot(rackID: id, row: row, column: $0, depth: depth) }
            }
        }
    }

    /// "B3" for row 1, column 2.
    public func label(row: Int, column: Int) -> String {
        "\(Rack.rowLetters(row))\(column + 1)"
    }

    /// "B3", or "B3-2" for the second bottle of a cell that holds several.
    public func label(for slot: Slot) -> String {
        let base = label(row: slot.row, column: slot.column)
        return positionsPerCell > 1 ? "\(base)-\(slot.depth + 1)" : base
    }

    public func label(for cell: RackCell) -> String { label(row: cell.row, column: cell.column) }

    public static func rowLetters(_ index: Int) -> String {
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
        /// Bought but not delivered yet (in transit, en primeur). Never in a rack,
        /// not counted in the cellar until received.
        case ordered
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
    /// When an ordered bottle should arrive. Only meaningful for `.ordered`.
    public var expectedAt: Date?
    /// Kept for an occasion ("Paul's 40th"): not suggested for tonight.
    public var reservedFor: String?

    public init(
        id: UUID = UUID(), wineID: UUID, status: Status = .inCellar, slot: Slot? = nil,
        storageNote: String? = nil, addedAt: Date = .now, purchaseDate: Date? = nil,
        purchasePrice: Decimal? = nil, currency: String? = nil, purchasePlace: String? = nil,
        receivedFrom: String? = nil, sizeML: Int = 750, tasting: Tasting? = nil, expectedAt: Date? = nil
    ) {
        self.id = id; self.wineID = wineID; self.status = status; self.slot = slot
        self.storageNote = storageNote; self.addedAt = addedAt; self.purchaseDate = purchaseDate
        self.purchasePrice = purchasePrice; self.currency = currency; self.purchasePlace = purchasePlace
        self.receivedFrom = receivedFrom; self.sizeML = sizeML; self.tasting = tasting
        self.expectedAt = expectedAt
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
        expectedAt = try c.decodeIfPresent(Date.self, forKey: .expectedAt)
        reservedFor = try c.decodeIfPresent(String.self, forKey: .reservedFor)
    }
}

/// User preferences stored with the cellar.
public struct CellarSettings: Codable, Hashable, Sendable {
    /// ISO 4217 code used by default for prices ("EUR", "CHF"...).
    public var currency: String
    /// Searches the user named, shown as chips in the Cellar. Nil when there are none.
    public var savedSearches: [SavedSearch]?

    public init(currency: String = "EUR", savedSearches: [SavedSearch]? = nil) {
        self.currency = currency
        self.savedSearches = savedSearches
    }
}

/// A search and filters kept under a name ("Burgundy, ready").
public extension CellarSettings {
    /// The settings that sync as one object; saved searches sync one by one.
    var withoutSearches: CellarSettings {
        var copy = self
        copy.savedSearches = nil
        return copy
    }
}

public extension SavedSearch {
    /// By name (ignoring case), then by ID: the same order on every device.
    static func ordered(_ searches: [SavedSearch]) -> [SavedSearch] {
        searches.sorted { a, b in
            let na = a.name.lowercased(), nb = b.name.lowercased()
            return na != nb ? na < nb : a.id.uuidString.lowercased() < b.id.uuidString.lowercased()
        }
    }
}

public struct SavedSearch: Codable, Hashable, Sendable, Identifiable {
    public var id: UUID
    public var name: String
    public var query: String
    public var colors: [WineColor]
    public var maturities: [Maturity]
    public var rackID: UUID?
    public var tag: String?
    public var reservation: String?

    public init(id: UUID = UUID(), name: String, filter: CellarFilter) {
        self.id = id
        self.name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        query = filter.query.trimmingCharacters(in: .whitespaces)
        // Sorted, so the same filters always encode the same way.
        colors = filter.colors.sorted { $0.rawValue < $1.rawValue }
        maturities = filter.maturities.sorted { $0.rawValue < $1.rawValue }
        rackID = filter.rackID
        tag = filter.tag
        reservation = filter.reservation
    }

    public var filter: CellarFilter {
        CellarFilter(query: query, colors: Set(colors), maturities: Set(maturities), rackID: rackID, tag: tag,
                     reservation: reservation)
    }
}
