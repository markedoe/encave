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
    public var grapes: [String] {
        didSet { grapeShares = Wine.keptShares(grapeShares, grapes: grapes) }
    }
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
    /// More photos (back label, receipt, the bottle in its rack). Nil rather than empty,
    /// and each once: photos are named after their content, so the same picture added
    /// twice is kept once (as in the Kotlin core).
    public var extraPhotoIDs: [String]? {
        didSet { extraPhotoIDs = Wine.uniquePhotoIDs(extraPhotoIDs) }
    }
    /// What a 75 cl bottle (or the wine's usual size) sells for today, accepted by the user
    /// from a web search or typed: the amount, its currency code and the day it was set.
    public var currentPrice: Decimal?
    public var currentPriceCurrency: String?
    public var currentPriceDate: Date?
    /// The picture shown for the wine instead of the label photo: one found on the
    /// internet or an illustration made on the device. Nil shows the label photo.
    public var coverPhotoID: String?
    /// Documents kept with the wine, so with all its bottles (an invoice, a technical sheet).
    /// Nil rather than empty, and each once by ID.
    public var attachments: [Attachment]? {
        didSet { attachments = Attachment.unique(attachments) }
    }
    /// When the details were last completed by the AI from the label photo, so the app
    /// can say so. Nil when never.
    public var aiCompletedAt: Date?
    /// Where the whole front label is in the label photo, from the AI label recognition,
    /// to frame the studio picture. Used only while it names the current `photoID`
    /// (see `labelFrame(forPhoto:)`). Nil when never found.
    public var labelFrame: WineLabelFrame?
    /// Alcohol by volume, in percent (13.5).
    public var abv: Decimal?
    /// Free text, with suggestions ("Grand Cru", "Cru Bourgeois").
    public var classification: String?
    /// The lieu-dit or climat (a named vineyard).
    public var lieuDit: String?
    /// Sparkling wines: a `Vocabulary.dosages` id ("brut"). Kept even when unknown.
    public var dosage: String?
    /// Still wines: a `Vocabulary.sweetnesses` id ("dry", "offDry"…). Kept even when unknown.
    public var sweetness: String?
    /// Share of each grape in percent (1 to 100), keyed by the grape as written in `grapes`;
    /// `grapes` stays the list older versions read. A share whose grape is no longer in
    /// `grapes` is dropped. Nil rather than empty.
    public var grapeShares: [String: Int]? {
        didSet { grapeShares = Wine.keptShares(grapeShares, grapes: grapes) }
    }
    /// How it was aged, `Vocabulary.ageings` ids, each once. Nil rather than empty.
    public var ageing: [String]? {
        didSet { ageing = Tasting.uniqueIDs(ageing) }
    }
    /// `Vocabulary.farmings` id ("organic"), and whether it is certified (true or nil, only
    /// with a farming).
    public var farming: String?
    public var farmingCertified: Bool?
    /// `Vocabulary.closures` id ("screwCap").
    public var closure: String?
    /// Critics' and guides' scores and awards, as typed. Nil rather than empty.
    public var criticScores: [CriticScore]? {
        didSet { if criticScores?.isEmpty == true { criticScores = nil } }
    }
    /// What to expect when tasting it, from the AI or kept from the bundled profiles; nil
    /// shows the bundled profile (`expectedOrBundledProfile`).
    public var expectedProfile: ExpectedProfile?
    /// The pages the AI cited when it last completed the wine from the web. Nil rather than empty.
    public var webSources: [WineWebSource]? {
        didSet { if webSources?.isEmpty == true { webSources = nil } }
    }
    /// Keys written by a newer version, kept and written back unchanged (docs/SYNC.md §1).
    /// Part of equality, so a merge that only brings them in is still saved.
    public var unknownFields = UnknownFields()

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case id, name, producer, vintage, color, region, country, appellation, grapes, drinkFrom, drinkUntil, notes
        case photoID, barcodes, tags, extraPhotoIDs, currentPrice, currentPriceCurrency, currentPriceDate
        case coverPhotoID, attachments, aiCompletedAt, labelFrame
        case abv, classification, lieuDit, dosage, sweetness, grapeShares, ageing, farming, farmingCertified, closure
        case criticScores, expectedProfile, webSources
    }

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
        // Normalized as the initializer does, and as the Kotlin core reads them, so both
        // platforms hold and write back the same wine.
        barcodes = try c.decodeIfPresent([String].self, forKey: .barcodes).flatMap { $0.isEmpty ? nil : $0 }
        tags = Wine.normalizedTags(try c.decodeIfPresent([String].self, forKey: .tags))
        extraPhotoIDs = Wine.uniquePhotoIDs(try c.decodeIfPresent([String].self, forKey: .extraPhotoIDs))
        currentPrice = try c.decodeIfPresent(Decimal.self, forKey: .currentPrice)
        currentPriceCurrency = try c.decodeIfPresent(String.self, forKey: .currentPriceCurrency)
        currentPriceDate = try c.decodeIfPresent(Date.self, forKey: .currentPriceDate)
        coverPhotoID = try c.decodeIfPresent(String.self, forKey: .coverPhotoID)
        attachments = Attachment.unique(try c.decodeIfPresent([Attachment].self, forKey: .attachments))
        aiCompletedAt = try c.decodeIfPresent(Date.self, forKey: .aiCompletedAt)
        labelFrame = try c.decodeIfPresent(WineLabelFrame.self, forKey: .labelFrame)
        // The wine details, normalized the same way on every device (docs/SYNC.md §1). Ids
        // this version does not know are kept as they are.
        abv = try c.decodeIfPresent(Decimal.self, forKey: .abv)
        classification = try c.decodeIfPresent(String.self, forKey: .classification)
        lieuDit = try c.decodeIfPresent(String.self, forKey: .lieuDit)
        dosage = Tasting.id(try c.decodeIfPresent(String.self, forKey: .dosage))
        sweetness = Tasting.id(try c.decodeIfPresent(String.self, forKey: .sweetness))
        grapeShares = Wine.keptShares(try c.decodeIfPresent([String: Int].self, forKey: .grapeShares), grapes: grapes)
        ageing = Tasting.uniqueIDs(try c.decodeIfPresent([String].self, forKey: .ageing))
        farming = Tasting.id(try c.decodeIfPresent(String.self, forKey: .farming))
        let certified = try c.decodeIfPresent(Bool.self, forKey: .farmingCertified)
        farmingCertified = farming != nil && certified == true ? true : nil
        closure = Tasting.id(try c.decodeIfPresent(String.self, forKey: .closure))
        criticScores = try c.decodeIfPresent([CriticScore].self, forKey: .criticScores).flatMap { $0.isEmpty ? nil : $0 }
        expectedProfile = try c.decodeIfPresent(ExpectedProfile.self, forKey: .expectedProfile)
        webSources = try c.decodeIfPresent([WineWebSource].self, forKey: .webSources).flatMap { $0.isEmpty ? nil : $0 }
        unknownFields = try UnknownFields(from: decoder, known: CodingKeys.self)
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(name, forKey: .name)
        try c.encodeIfPresent(producer, forKey: .producer)
        try c.encodeIfPresent(vintage, forKey: .vintage)
        try c.encodeIfPresent(color, forKey: .color)
        try c.encodeIfPresent(region, forKey: .region)
        try c.encodeIfPresent(country, forKey: .country)
        try c.encodeIfPresent(appellation, forKey: .appellation)
        try c.encode(grapes, forKey: .grapes)
        try c.encodeIfPresent(drinkFrom, forKey: .drinkFrom)
        try c.encodeIfPresent(drinkUntil, forKey: .drinkUntil)
        try c.encodeIfPresent(notes, forKey: .notes)
        try c.encodeIfPresent(photoID, forKey: .photoID)
        try c.encodeIfPresent(barcodes, forKey: .barcodes)
        try c.encodeIfPresent(tags, forKey: .tags)
        try c.encodeIfPresent(extraPhotoIDs, forKey: .extraPhotoIDs)
        try c.encodeIfPresent(currentPrice, forKey: .currentPrice)
        try c.encodeIfPresent(currentPriceCurrency, forKey: .currentPriceCurrency)
        try c.encodeIfPresent(currentPriceDate, forKey: .currentPriceDate)
        try c.encodeIfPresent(coverPhotoID, forKey: .coverPhotoID)
        try c.encodeIfPresent(attachments, forKey: .attachments)
        try c.encodeIfPresent(aiCompletedAt, forKey: .aiCompletedAt)
        try c.encodeIfPresent(labelFrame, forKey: .labelFrame)
        try c.encodeIfPresent(abv, forKey: .abv)
        try c.encodeIfPresent(classification, forKey: .classification)
        try c.encodeIfPresent(lieuDit, forKey: .lieuDit)
        try c.encodeIfPresent(dosage, forKey: .dosage)
        try c.encodeIfPresent(sweetness, forKey: .sweetness)
        try c.encodeIfPresent(grapeShares, forKey: .grapeShares)
        try c.encodeIfPresent(ageing, forKey: .ageing)
        try c.encodeIfPresent(farming, forKey: .farming)
        try c.encodeIfPresent(farming != nil && farmingCertified == true ? true : nil, forKey: .farmingCertified)
        try c.encodeIfPresent(closure, forKey: .closure)
        try c.encodeIfPresent(criticScores, forKey: .criticScores)
        try c.encodeIfPresent(expectedProfile, forKey: .expectedProfile)
        try c.encodeIfPresent(webSources, forKey: .webSources)
        try unknownFields.encode(to: encoder, known: CodingKeys.self)
    }

    /// Shares in percent kept for the grapes listed (trimmed, ignoring case), each brought
    /// into 1…100 (0 and below are dropped); nil when none are left.
    public static func keptShares(_ shares: [String: Int]?, grapes: [String]) -> [String: Int]? {
        guard let shares else { return nil }
        let listed = Set(grapes.map(Wine.grapeKey))
        var kept: [String: Int] = [:], seen = Set<String>()
        // A grape written twice (in another case) keeps the key that sorts first, by UTF-16
        // code units as in Kotlin, so every device keeps the same one.
        for grape in shares.keys.sorted(by: { $0.utf16.lexicographicallyPrecedes($1.utf16) }) {
            let key = Wine.grapeKey(grape)
            guard let share = shares[grape], share > 0, listed.contains(key), seen.insert(key).inserted else { continue }
            kept[grape] = min(100, share)
        }
        return kept.isEmpty ? nil : kept
    }

    /// A grape compared for its share: trimmed, in Unicode NFC, ignoring case (as in Kotlin).
    static func grapeKey(_ grape: String) -> String {
        grape.trimmingCharacters(in: .whitespacesAndNewlines).precomposedStringWithCanonicalMapping.lowercased()
    }

    /// The share of a grape in percent, if given.
    public func share(of grape: String) -> Int? {
        let key = Wine.grapeKey(grape)
        return grapeShares?.first { Wine.grapeKey($0.key) == key }?.value
    }

    /// True when any of the details under "More details" is filled.
    public var hasMoreDetails: Bool {
        abv != nil || !(classification ?? "").isEmpty || !(lieuDit ?? "").isEmpty || dosage != nil || sweetness != nil
            || grapeShares != nil || ageing != nil || farming != nil || closure != nil
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

    /// Each ID once, in the order given; nil when none are left.
    public static func uniquePhotoIDs(_ ids: [String]?) -> [String]? {
        var seen = Set<String>()
        let unique = (ids ?? []).filter { seen.insert($0).inserted }
        return unique.isEmpty ? nil : unique
    }

    /// True if the wine has this tag, ignoring case.
    public func hasTag(_ tag: String) -> Bool {
        (tags ?? []).contains { $0.caseInsensitiveCompare(tag) == .orderedSame }
    }

    public func hasGrape(_ grape: String) -> Bool {
        let name = grape.trimmingCharacters(in: .whitespacesAndNewlines)
        return grapes.contains { $0.trimmingCharacters(in: .whitespacesAndNewlines).caseInsensitiveCompare(name) == .orderedSame }
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
    /// Keys written by a newer version, kept and written back unchanged (docs/SYNC.md §1).
    /// Not part of equality: a slot is compared as a place (two bottles in one place).
    public var unknownFields = UnknownFields()

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case rackID, row, column, depth
    }

    public init(rackID: UUID, row: Int, column: Int, depth: Int = 0) {
        self.rackID = rackID; self.row = row; self.column = column; self.depth = depth
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        rackID = try c.decode(UUID.self, forKey: .rackID)
        row = try c.decode(Int.self, forKey: .row)
        column = try c.decode(Int.self, forKey: .column)
        depth = try c.decodeIfPresent(Int.self, forKey: .depth) ?? 0
        unknownFields = try UnknownFields(from: decoder, known: CodingKeys.self)
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(rackID, forKey: .rackID)
        try c.encode(row, forKey: .row)
        try c.encode(column, forKey: .column)
        try c.encode(depth, forKey: .depth)
        try unknownFields.encode(to: encoder, known: CodingKeys.self)
    }

    public static func == (a: Slot, b: Slot) -> Bool {
        a.rackID == b.rackID && a.row == b.row && a.column == b.column && a.depth == b.depth
    }

    public func hash(into hasher: inout Hasher) {
        hasher.combine(rackID); hasher.combine(row); hasher.combine(column); hasher.combine(depth)
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
    /// Honeycomb: rows offset by half a bottle, nesting into each other. Either continuous
    /// (a honeycomb rack: every other row holds one fewer) or on shelves (a wine fridge whose
    /// shelves hold bottles laid in layers stacked in quincunx, see `Rack.shelfLayers`).
    /// Up to two bottles one behind the other.
    case staggered
    /// Wine fridge or cabinet: shelves holding a row of bottles, one or more deep.
    case shelves
    /// Diamond bins or crates: each bin holds several bottles in a pile.
    case bins
}

/// What a storage of bins was made as. Written as "binStyle" on the rack (docs/SYNC.md).
public enum BinStyle: String, Codable, CaseIterable, Sendable {
    /// Diamond bins in a rack, shown under Rack.
    case diamond
    /// Crates or boxes.
    case crates
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
    /// Bottles one behind the other in a cell (grid, honeycomb and shelves); when rows
    /// differ (`rowDepths`), the deepest row, so older versions still show every place.
    public var depth: Int
    /// Honeycomb on shelves (a wine fridge): how many layers each shelf holds, from the top
    /// shelf, each 1 to 3; nil for a continuous honeycomb. Rows are the layers, lettered from
    /// the top: a shelf's last row is its bottom layer.
    public var shelfLayers: [Int]?
    /// Fridge with shelf layers and temperature zones: the shelves (0 is the top one) that
    /// start a new zone, below a divider. Nil for a single zone.
    public var zoneBreaks: [Int]?
    /// Fridge with shelf layers, two deep: the back bottles lie the other way round
    /// ("head-to-tail"), necks facing you between the front bottles' bases. Nil (false)
    /// elsewhere. When shelves differ (`rowBackReversed`), true if any shelf does.
    public var backReversed: Bool?
    /// Bottles one behind the other in each row, from the top, when rows differ (one row
    /// single, the next two deep); each 1 to `depth`, the same for every layer of a shelf.
    /// Nil when every row is `depth` deep. See `depth(ofRow:)`.
    public var rowDepths: [Int]?
    /// Fridge with shelf layers: whether each row lies head-to-tail, from the top, when the
    /// shelves two deep differ; the same for every layer of a shelf, false where a row is not
    /// two deep. Nil when every shelf two deep follows `backReversed`. See `isBackReversed(row:)`.
    public var rowBackReversed: [Bool]?
    /// Bottles per bin (bins only).
    public var binCapacity: Int
    /// Cells in each row when rows differ (a triangle top, an irregular cellar).
    public var rowColumns: [Int]?
    /// "binStyle" as read or set: kept even when this version does not know the value, so
    /// writing the rack back does not lose it (docs/SYNC.md). See `binStyle`.
    public var binStyleValue: String?
    /// Diamond bins: the sides whose edge triangles hold bottles ("edgeTriangles"), as read or
    /// set, values of a newer version kept. They apply only while the rows are shaped for
    /// them (`diamondLayout`); otherwise every cell is a whole diamond, as older versions show it.
    public var edgeTrianglesValue: [String]?
    /// Bottles an edge triangle holds ("triangleCapacity"), as read or set. See `triangleCapacity`.
    public var triangleCapacityValue: Int?
    /// Keys written by a newer version, kept and written back unchanged (docs/SYNC.md §1).
    /// Part of equality, so a merge that only brings them in is still saved.
    public var unknownFields = UnknownFields()

    public static let maxDepth = 3
    /// Honeycomb racks hold at most two bottles one behind the other.
    public static let maxStaggeredDepth = 2
    /// Layers of bottles stacked on one shelf.
    public static let maxLayers = 3
    /// Largest number of rows, of cells in a row and of bottles in a bin. The editors
    /// stay well below; a rack from another device or a file is cut to it, so a
    /// damaged or hostile one cannot make every device allocate millions of cells.
    public static let maxSize = 100

    public init(
        id: UUID = UUID(), name: String, kind: RackKind = .grid, rows: Int, columns: Int,
        depth: Int = 1, binCapacity: Int = 12, rowColumns: [Int]? = nil, shelfLayers: [Int]? = nil,
        zoneBreaks: [Int]? = nil, backReversed: Bool? = nil, binStyle: BinStyle? = nil,
        rowDepths: [Int]? = nil, rowBackReversed: [Bool]? = nil
    ) {
        self.id = id; self.name = name; self.kind = kind
        self.rows = Rack.bounded(rows); self.columns = Rack.bounded(columns)
        self.depth = Rack.boundedDepth(depth, kind: kind); self.binCapacity = Rack.bounded(binCapacity)
        self.rowColumns = rowColumns.map { $0.prefix(Rack.maxSize).map(Rack.bounded) }
        applyShelfLayers(shelfLayers, zoneBreaks: zoneBreaks)
        self.backReversed = backReversed == true && hasDepthBands ? true : nil
        applyRowSettings(depths: rowDepths, headToTail: rowBackReversed)
        self.binStyleValue = binStyle?.rawValue
    }

    /// Bins only: whether they were made as diamond bins in a rack or as crates. Nil when
    /// not saved (racks from older versions) or unknown to this version: the shape decides
    /// (see `storageType`).
    public var binStyle: BinStyle? {
        get { binStyleValue.flatMap(BinStyle.init(rawValue:)) }
        set { binStyleValue = newValue?.rawValue }
    }

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case id, name, kind, rows, columns, depth, binCapacity, rowColumns, shelfLayers, zoneBreaks, backReversed, binStyle
        case rowDepths, rowBackReversed, edgeTriangles, triangleCapacity
        /// Version 0.1 and 0.2 had "grid" and a decorative "diamond" layout, both one bottle per cell.
        case layout
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        // Absent in files from version 0.1 and 0.2 (grids). An unknown value fails, like any enum.
        kind = try c.decodeIfPresent(RackKind.self, forKey: .kind) ?? .grid
        rows = Rack.bounded(try c.decode(Int.self, forKey: .rows))
        columns = Rack.bounded(try c.decode(Int.self, forKey: .columns))
        depth = Rack.boundedDepth(try c.decodeIfPresent(Int.self, forKey: .depth) ?? 1, kind: kind)
        binCapacity = Rack.bounded(try c.decodeIfPresent(Int.self, forKey: .binCapacity) ?? 12)
        rowColumns = try c.decodeIfPresent([Int].self, forKey: .rowColumns)?.prefix(Rack.maxSize).map(Rack.bounded)
        applyShelfLayers(try c.decodeIfPresent([Int].self, forKey: .shelfLayers),
                         zoneBreaks: try c.decodeIfPresent([Int].self, forKey: .zoneBreaks))
        // Absent from older racks; kept only where it applies, true or left out.
        backReversed = try c.decodeIfPresent(Bool.self, forKey: .backReversed) == true && hasDepthBands ? true : nil
        // Absent from older racks; kept only where rows really differ.
        applyRowSettings(depths: try c.decodeIfPresent([Int].self, forKey: .rowDepths),
                         headToTail: try c.decodeIfPresent([Bool].self, forKey: .rowBackReversed))
        // Absent from older racks; any string is kept, even one this version does not know.
        binStyleValue = try c.decodeIfPresent(String.self, forKey: .binStyle)
        // Absent from older racks; kept as read, even for a shape they no longer fit.
        edgeTrianglesValue = try c.decodeIfPresent([String].self, forKey: .edgeTriangles).map { Array($0.prefix(8)) }
        triangleCapacityValue = try c.decodeIfPresent(Int.self, forKey: .triangleCapacity).map(Rack.bounded)
        // "layout" is a known key (read as a grid), so it is not kept.
        unknownFields = try UnknownFields(from: decoder, known: CodingKeys.self)
    }

    /// 1 to `maxSize`.
    static func bounded(_ value: Int) -> Int { min(maxSize, max(1, value)) }

    /// 1 to 3, or to 2 for a honeycomb.
    static func boundedDepth(_ value: Int, kind: RackKind) -> Int {
        min(kind == .staggered ? maxStaggeredDepth : maxDepth, max(1, value))
    }

    /// Keeps shelf layers only on a honeycomb, each brought into 1…3, as many shelves as fit
    /// in `maxSize` rows; the rows are then the layers. Zone breaks are kept only between
    /// the kept shelves.
    private mutating func applyShelfLayers(_ layers: [Int]?, zoneBreaks breaks: [Int]?) {
        guard kind == .staggered, let layers, !layers.isEmpty else {
            shelfLayers = nil
            zoneBreaks = nil
            return
        }
        var kept: [Int] = [], total = 0
        for count in layers {
            let count = min(Rack.maxLayers, max(1, count))
            guard total + count <= Rack.maxSize else { break }
            kept.append(count)
            total += count
        }
        shelfLayers = kept
        rows = total
        // Breaks between shelves only, each once, in order.
        let between = Set((breaks ?? []).filter { (1..<kept.count).contains($0) }).sorted()
        zoneBreaks = between.isEmpty ? nil : between
    }

    /// Keeps per-row depths and head-to-tail only where rows really differ, the same way on
    /// every device (docs/SYNC.md): one value per row (an array of another length is
    /// ignored), each depth brought into 1…`depth`, every layer of a shelf taking the value
    /// of its bottom layer. Depths are dropped when every row is `depth` deep, or when none is
    /// (an older version made the rack deeper: it shows every row that deep). Head-to-tail
    /// applies only to rows two deep of a head-to-tail fridge, and is dropped when every such
    /// row lies head-to-tail, or none does (`backReversed` then says it for all).
    private mutating func applyRowSettings(depths: [Int]?, headToTail: [Bool]?) {
        rowDepths = nil
        rowBackReversed = nil
        guard kind != .bins else { return }
        // An array of another length is stale (an older version added or removed rows or
        // shelves without knowing it): the rows are read as uniform.
        if let depths, depths.count == rows {
            let read = (0..<rows).map { row in depths.indices.contains(row) ? min(depth, max(1, depths[row])) : depth }
            let kept = alongShelves(read)
            if kept.contains(depth), kept.contains(where: { $0 != depth }) { rowDepths = kept }
        }
        if isBackReversed, let headToTail, headToTail.count == rows {
            let read = (0..<rows).map { row in headToTail.indices.contains(row) ? headToTail[row] : true }
            let kept = alongShelves(read).enumerated().map { row, reversed in reversed && depth(ofRow: row) == 2 }
            let deep = (0..<rows).filter { depth(ofRow: $0) == 2 }
            if deep.contains(where: { kept[$0] }), deep.contains(where: { !kept[$0] }) { rowBackReversed = kept }
        }
    }

    /// Every layer of a shelf takes the value of its bottom layer (the shelf's last row).
    private func alongShelves<Value>(_ values: [Value]) -> [Value] {
        guard hasShelfLayers else { return values }
        var result = values
        for rows in shelfRows {
            for row in rows { result[row] = values[rows.upperBound - 1] }
        }
        return result
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
        try c.encodeIfPresent(shelfLayers, forKey: .shelfLayers)
        try c.encodeIfPresent(zoneBreaks, forKey: .zoneBreaks)
        try c.encodeIfPresent(backReversed == true && hasDepthBands ? true : nil, forKey: .backReversed)
        try c.encodeIfPresent(binStyleValue, forKey: .binStyle)
        try c.encodeIfPresent(rowDepths, forKey: .rowDepths)
        try c.encodeIfPresent(rowBackReversed, forKey: .rowBackReversed)
        try c.encodeIfPresent(edgeTrianglesValue, forKey: .edgeTriangles)
        try c.encodeIfPresent(triangleCapacityValue, forKey: .triangleCapacity)
        try unknownFields.encode(to: encoder, known: CodingKeys.self)
    }

    /// Cells in a row: custom widths first, then one fewer on offset honeycomb rows (on
    /// shelves: one fewer on the layer above the bottom one, as bottles rest in the gaps).
    public func columns(inRow row: Int) -> Int {
        if let rowColumns, rowColumns.indices.contains(row) { return rowColumns[row] }
        return kind == .staggered && isOffset(row: row) ? max(1, columns - 1) : columns
    }

    /// A honeycomb on shelves, as a wine fridge with bottles laid in layers.
    public var hasShelfLayers: Bool { kind == .staggered && shelfLayers != nil }

    /// A fridge with shelf layers two deep (on some shelves at least): drawn as a front band
    /// and a back band on each shelf two deep (see `hasDepthBands(shelf:)`).
    public var hasDepthBands: Bool { hasShelfLayers && depth == 2 }

    /// The back bottles of a fridge drawn in bands lie head-to-tail, on some shelves at least
    /// (see `backReversed` and `isBackReversed(row:)`).
    public var isBackReversed: Bool { backReversed == true && hasDepthBands }

    /// Bottles one behind the other in this row: its own when rows differ, else `depth`.
    /// 1 for bins, whose bottles are piled (`positions(inRow:)`).
    public func depth(ofRow row: Int) -> Int {
        guard kind != .bins else { return 1 }
        if let rowDepths, rowDepths.indices.contains(row) { return rowDepths[row] }
        return depth
    }

    /// Bottles one behind the other on a shelf of a fridge with shelf layers (0 is the top one).
    public func depth(ofShelf shelf: Int) -> Int {
        shelfRows.indices.contains(shelf) ? depth(ofRow: shelfRows[shelf].upperBound - 1) : depth
    }

    /// Bottles that fit in one cell of this row: its depth, or a bin's capacity.
    public func positions(inRow row: Int) -> Int { kind == .bins ? binCapacity : depth(ofRow: row) }

    /// Rows that are not all the same beyond their number of places: their depth or how
    /// they lie (`rowDepths`, `rowBackReversed`).
    public var rowsDiffer: Bool { rowDepths != nil || rowBackReversed != nil }

    /// This shelf of a fridge with shelf layers is two deep: drawn as a front and a back band.
    public func hasDepthBands(shelf: Int) -> Bool { hasDepthBands && depth(ofShelf: shelf) == 2 }

    /// The back bottles of this row lie head-to-tail: a fridge with shelf layers, the row two
    /// deep, its shelf head-to-tail.
    public func isBackReversed(row: Int) -> Bool {
        guard isBackReversed, depth(ofRow: row) == 2 else { return false }
        if let rowBackReversed, rowBackReversed.indices.contains(row) { return rowBackReversed[row] }
        return true
    }

    /// The back bottles of this shelf lie head-to-tail (0 is the top shelf).
    public func isBackReversed(shelf: Int) -> Bool {
        shelfRows.indices.contains(shelf) && isBackReversed(row: shelfRows[shelf].upperBound - 1)
    }

    /// How far the layers of a band are drawn right of the same layers in front, in places:
    /// head-to-tail, each neck behind sits between two front bases, half a place along.
    /// Drawing only: places, numbering and counts stay the same.
    public func bandShift(row: Int, depth: Int?) -> Double { isBackReversed(row: row) && depth == 1 ? 0.5 : 0 }

    /// The largest shift of a band (`bandShift(row:depth:)`), for the width of the drawing.
    public var maxBandShift: Double { isBackReversed ? 0.5 : 0 }

    /// The temperature zone of each shelf, 0 for the top one (only with shelf layers).
    public func zone(ofShelf shelf: Int) -> Int { (zoneBreaks ?? []).filter { $0 <= shelf }.count }

    /// Zones of a fridge with shelf layers: 1 without breaks.
    public var zoneCount: Int { (zoneBreaks?.count ?? 0) + 1 }

    /// The rows of each shelf, from the top shelf (only with shelf layers).
    public var shelfRows: [Range<Int>] {
        guard kind == .staggered, let shelfLayers else { return [] }
        var start = 0
        return shelfLayers.map { count in
            defer { start += count }
            return start..<(start + count)
        }
    }

    /// Where a row is on a fridge with shelf layers: its shelf (0 is the top one), its
    /// layer on that shelf (0 is the bottom one) and the layers of that shelf.
    public func shelfPlace(ofRow row: Int) -> (shelf: Int, layer: Int, layers: Int)? {
        guard kind == .staggered, let shelfLayers else { return nil }
        var start = 0
        for (shelf, count) in shelfLayers.enumerated() {
            if row < start + count { return (shelf, start + count - 1 - row, count) }
            start += count
        }
        return nil
    }

    /// Is this row drawn offset by half a bottle? Odd honeycomb rows, or upper layers resting
    /// in the gaps of the layer below; bins too.
    public func isOffset(row: Int) -> Bool {
        switch kind {
        case .bins: return diamondLayout?.isOffset(row: row) ?? !row.isMultiple(of: 2)
        case .staggered: return shelfPlace(ofRow: row).map { !$0.layer.isMultiple(of: 2) } ?? !row.isMultiple(of: 2)
        case .grid, .shelves: return false
        }
    }

    /// The widest row, for drawing.
    public var maxColumns: Int { (0..<rows).map(columns(inRow:)).max() ?? columns }

    /// Bottles that fit in one cell, in the deepest row when rows differ
    /// (`positions(inRow:)` gives each row's).
    public var positionsPerCell: Int { kind == .bins ? binCapacity : depth }

    public var cellCount: Int { (0..<rows).reduce(0) { $0 + columns(inRow: $1) } }

    public var capacity: Int {
        let layout = diamondLayout
        return (0..<rows).reduce(0) { total, row in
            total + (0..<columns(inRow: row)).reduce(0) { $0 + positions(row: row, column: $1, layout: layout) }
        }
    }

    /// Bottles that fit in this cell: a bin's capacity, fewer in an edge triangle
    /// (`triangleCapacity`), elsewhere its row's depth.
    public func positions(row: Int, column: Int) -> Int { positions(row: row, column: column, layout: diamondLayout) }

    public func positions(of cell: RackCell) -> Int { positions(row: cell.row, column: cell.column) }

    func positions(row: Int, column: Int, layout: DiamondLayout?) -> Int {
        if let layout, case .triangle = layout.place(row: row, column: column) { return triangleCapacity }
        return positions(inRow: row)
    }

    /// A place of this rack as every version reads it: within the rack's `depth` (or a bin's
    /// capacity), whatever its row's own depth. Sync repair and imports judge places with
    /// it, so a version that does not know `rowDepths` and one that does keep the same
    /// bottles in place (docs/SYNC.md). A place behind a row's depth (`hasPosition`) holds a
    /// bottle an older version put there: it is kept and shown.
    public func contains(_ slot: Slot) -> Bool {
        slot.rackID == id && (0..<rows).contains(slot.row)
            && (0..<columns(inRow: slot.row)).contains(slot.column)
            && (0..<positionsPerCell).contains(slot.depth)
    }

    /// A position the row really has, within its own depth: what the apps offer to put a
    /// bottle in, and what an edit keeps (`Cellar.displacedBottles`).
    public func hasPosition(_ slot: Slot) -> Bool {
        contains(slot) && slot.depth < positions(row: slot.row, column: slot.column)
    }

    /// The places drawn and named for a cell of this row: its own depth, and as deep as the
    /// bottles there go (a bottle an older version put behind a single row stays in view).
    public func shownPositions(inRow row: Int, deepest: Int?) -> Int {
        max(positions(inRow: row), (deepest ?? -1) + 1)
    }

    /// The places drawn and counted for this cell: its own (fewer in an edge triangle), and as
    /// many as its bottles go (an older version may fill a triangle like a whole diamond).
    public func shownPositions(of cell: RackCell, deepest: Int?) -> Int {
        max(positions(of: cell), (deepest ?? -1) + 1)
    }

    public func contains(_ cell: RackCell) -> Bool {
        cell.rackID == id && (0..<rows).contains(cell.row) && (0..<columns(inRow: cell.row)).contains(cell.column)
    }

    public var cells: [RackCell] {
        (0..<rows).flatMap { row in (0..<columns(inRow: row)).map { RackCell(rackID: id, row: row, column: $0) } }
    }

    /// Every slot, in the order bottles are placed automatically: a bin is filled before
    /// the next one; elsewhere the front of a row is filled before the back. On a fridge with
    /// shelf layers, shelves are filled from the top as rows are, each from its bottom layer
    /// up (an upper layer rests on the one below).
    public var allSlots: [Slot] {
        let order: [Int] = hasShelfLayers ? shelfRows.flatMap { $0.reversed() } : Array(0..<rows)
        let layout = diamondLayout
        return order.flatMap { row -> [Slot] in
            let width = columns(inRow: row), positions = positions(inRow: row)
            if kind == .bins {
                return (0..<width).flatMap { column in
                    (0..<self.positions(row: row, column: column, layout: layout))
                        .map { Slot(rackID: id, row: row, column: column, depth: $0) }
                }
            }
            return (0..<positions).flatMap { depth in
                (0..<width).map { Slot(rackID: id, row: row, column: $0, depth: depth) }
            }
        }
    }

    /// Where each row of a fridge on shelves goes when its shelves are edited. `origins`
    /// gives, for each shelf of the new rack from the top, the shelf of `old` it was (nil
    /// for a new one). A layer keeps its place counted from the bottom of its shelf; the
    /// rows of a removed shelf or layer map to nil. Empty unless both racks have shelf layers.
    public static func shelfRowMap(from old: Rack, to new: Rack, origins: [Int?]) -> [Int: Int?] {
        guard old.hasShelfLayers, new.hasShelfLayers else { return [:] }
        let newShelves = new.shelfRows
        var map: [Int: Int?] = [:]
        for row in 0..<old.rows { map[row] = .some(nil) }
        for (shelf, origin) in origins.enumerated() where newShelves.indices.contains(shelf) {
            guard let origin, old.shelfRows.indices.contains(origin) else { continue }
            let from = old.shelfRows[origin], to = newShelves[shelf]
            // Layer 0 is the bottom one, the shelf's last row.
            for layer in 0..<from.count where layer < to.count {
                map[from.upperBound - 1 - layer] = .some(to.upperBound - 1 - layer)
            }
        }
        return map
    }

    /// A slot moved by a cell map, else by a row map (see `Cellar.updateRack`); nil when its
    /// cell or row is gone. Depth is kept.
    static func moved(_ slot: Slot, rowMap: [Int: Int?], cellMap: [RackCell: RackCell?] = [:]) -> Slot? {
        if let entry = cellMap[slot.cell] {
            guard let cell = entry else { return nil }
            return Slot(rackID: slot.rackID, row: cell.row, column: cell.column, depth: slot.depth)
        }
        guard let mapped = rowMap[slot.row] else { return slot }
        guard let row = mapped else { return nil }
        var moved = slot
        moved.row = row
        return moved
    }

    /// "B3" for row 1, column 2.
    public func label(row: Int, column: Int) -> String {
        "\(Rack.rowLetters(row))\(column + 1)"
    }

    /// "B3", or "B3-2" for the second bottle of a cell that holds several.
    public func label(for slot: Slot) -> String {
        let base = label(row: slot.row, column: slot.column)
        return shownPositions(inRow: slot.row, deepest: slot.depth) > 1 ? "\(base)-\(slot.depth + 1)" : base
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
    /// 1 to 5, optional. Always written, also when a precise `score100` was entered, so
    /// older versions show stars. Changing it drops a score it no longer matches.
    public var rating: Int? {
        didSet { score100 = Score.kept(score100, rating: rating) }
    }
    /// The precise score, out of 100 (50…100 in quarter points), when the rating was entered
    /// out of 20 or 100; kept only while `rating` is its stars (`Score.kept`). Set both with
    /// `setRating(_:on:)`.
    public var score100: Double?
    public var note: String?
    public var occasion: String?
    public var appearance: String?
    public var nose: String?
    public var palate: String?
    public var finish: String?
    public var foodPairing: String?
    /// Aromas of the guided tasting sheet, as vocabulary ids ("fruit.cherry"), each once, in
    /// the order chosen. Nil rather than empty. Ids this version does not know are kept.
    public var aromas: [String]? {
        didSet { aromas = Tasting.uniqueIDs(aromas) }
    }
    /// Structure, each 1 to 5 (`Vocabulary.structure`), optional.
    public var sweetness: Int?
    public var acidity: Int?
    public var tannin: Int?
    public var body: Int?
    public var alcohol: Int?
    public var length: Int?
    /// Appearance: intensity ("pale", "medium", "deep") and hue ("ruby", "gold"…), vocabulary ids.
    public var intensity: String?
    public var hue: String?
    /// Keys written by a newer version, kept and written back unchanged (docs/SYNC.md §1).
    /// Part of equality, so a merge that only brings them in is still saved.
    public var unknownFields = UnknownFields()

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case date, rating, note, occasion, appearance, nose, palate, finish, foodPairing
        case score100, aromas, sweetness, acidity, tannin, body, alcohol, length, intensity, hue
    }

    public init(
        date: Date = .now, rating: Int? = nil, note: String? = nil, occasion: String? = nil,
        appearance: String? = nil, nose: String? = nil, palate: String? = nil, finish: String? = nil,
        foodPairing: String? = nil, score100: Double? = nil, aromas: [String]? = nil,
        sweetness: Int? = nil, acidity: Int? = nil, tannin: Int? = nil, body: Int? = nil,
        alcohol: Int? = nil, length: Int? = nil, intensity: String? = nil, hue: String? = nil
    ) {
        self.date = date
        let rating = rating.map { min(5, max(1, $0)) }
        self.rating = rating
        self.score100 = Score.kept(score100, rating: rating)
        self.note = note; self.occasion = occasion
        self.appearance = appearance; self.nose = nose; self.palate = palate; self.finish = finish
        self.foodPairing = foodPairing
        self.aromas = Tasting.uniqueIDs(aromas)
        self.sweetness = Tasting.level(sweetness); self.acidity = Tasting.level(acidity)
        self.tannin = Tasting.level(tannin); self.body = Tasting.level(body)
        self.alcohol = Tasting.level(alcohol); self.length = Tasting.level(length)
        self.intensity = Tasting.id(intensity); self.hue = Tasting.id(hue)
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        date = try c.decode(Date.self, forKey: .date)
        rating = try c.decodeIfPresent(Int.self, forKey: .rating)
        note = try c.decodeIfPresent(String.self, forKey: .note)
        occasion = try c.decodeIfPresent(String.self, forKey: .occasion)
        appearance = try c.decodeIfPresent(String.self, forKey: .appearance)
        nose = try c.decodeIfPresent(String.self, forKey: .nose)
        palate = try c.decodeIfPresent(String.self, forKey: .palate)
        finish = try c.decodeIfPresent(String.self, forKey: .finish)
        foodPairing = try c.decodeIfPresent(String.self, forKey: .foodPairing)
        // Newer fields, normalized the same way on every device (docs/SYNC.md §1): a score
        // that no longer matches the rating is stale, levels are brought into 1…5, ids are
        // kept once and empty ones are left out.
        score100 = Score.kept(try c.decodeIfPresent(Double.self, forKey: .score100), rating: rating)
        aromas = Tasting.uniqueIDs(try c.decodeIfPresent([String].self, forKey: .aromas))
        sweetness = Tasting.level(try c.decodeIfPresent(Int.self, forKey: .sweetness))
        acidity = Tasting.level(try c.decodeIfPresent(Int.self, forKey: .acidity))
        tannin = Tasting.level(try c.decodeIfPresent(Int.self, forKey: .tannin))
        body = Tasting.level(try c.decodeIfPresent(Int.self, forKey: .body))
        alcohol = Tasting.level(try c.decodeIfPresent(Int.self, forKey: .alcohol))
        length = Tasting.level(try c.decodeIfPresent(Int.self, forKey: .length))
        intensity = Tasting.id(try c.decodeIfPresent(String.self, forKey: .intensity))
        hue = Tasting.id(try c.decodeIfPresent(String.self, forKey: .hue))
        unknownFields = try UnknownFields(from: decoder, known: CodingKeys.self)
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(date, forKey: .date)
        try c.encodeIfPresent(rating, forKey: .rating)
        try c.encodeIfPresent(note, forKey: .note)
        try c.encodeIfPresent(occasion, forKey: .occasion)
        try c.encodeIfPresent(appearance, forKey: .appearance)
        try c.encodeIfPresent(nose, forKey: .nose)
        try c.encodeIfPresent(palate, forKey: .palate)
        try c.encodeIfPresent(finish, forKey: .finish)
        try c.encodeIfPresent(foodPairing, forKey: .foodPairing)
        try c.encodeIfPresent(Score.kept(score100, rating: rating), forKey: .score100)
        try c.encodeIfPresent(aromas, forKey: .aromas)
        try c.encodeIfPresent(sweetness, forKey: .sweetness)
        try c.encodeIfPresent(acidity, forKey: .acidity)
        try c.encodeIfPresent(tannin, forKey: .tannin)
        try c.encodeIfPresent(body, forKey: .body)
        try c.encodeIfPresent(alcohol, forKey: .alcohol)
        try c.encodeIfPresent(length, forKey: .length)
        try c.encodeIfPresent(intensity, forKey: .intensity)
        try c.encodeIfPresent(hue, forKey: .hue)
        try unknownFields.encode(to: encoder, known: CodingKeys.self)
    }

    /// Sets the rating from a value entered on a scale: stars clear the score, out of 20 or
    /// 100 keep it with its stars (`Score.entered`). Nil clears both.
    public mutating func setRating(_ value: Double?, on scale: RatingScale) {
        guard let value, let entered = Score.entered(value, on: scale) else {
            rating = nil
            return
        }
        rating = entered.rating
        score100 = entered.score100
    }

    /// The value of a structure attribute by its vocabulary id.
    public func level(of attribute: String) -> Int? {
        switch attribute {
        case "sweetness": sweetness
        case "acidity": acidity
        case "tannin": tannin
        case "body": body
        case "alcohol": alcohol
        case "length": length
        default: nil
        }
    }

    public mutating func setLevel(_ value: Int?, of attribute: String) {
        let value = Tasting.level(value)
        switch attribute {
        case "sweetness": sweetness = value
        case "acidity": acidity = value
        case "tannin": tannin = value
        case "body": body = value
        case "alcohol": alcohol = value
        case "length": length = value
        default: break
        }
    }

    /// The labels of its aromas and hue in the person's language, for search.
    public var searchLabels: [String] {
        (aromas ?? []).map { Vocabulary.label(.aroma, $0) } + [hue.map { Vocabulary.label(.hue, $0) }].compactMap { $0 }
    }

    /// True when any part of the guided sheet is filled: aromas, structure or appearance.
    public var hasGuidedNotes: Bool {
        !(aromas ?? []).isEmpty || [sweetness, acidity, tannin, body, alcohol, length].contains { $0 != nil }
            || intensity != nil || hue != nil
    }

    /// True when any structured field beyond rating and note is filled.
    public var isDetailed: Bool {
        [appearance, nose, palate, finish, foodPairing].contains { !($0 ?? "").isEmpty } || hasGuidedNotes
    }

    /// 1 to 5, nil otherwise.
    static func level(_ value: Int?) -> Int? { value.map { min(5, max(1, $0)) } }

    /// Nil for an empty id.
    static func id(_ value: String?) -> String? { value?.isEmpty == false ? value : nil }

    /// Each non-empty id once, in the order given; nil when none are left.
    static func uniqueIDs(_ ids: [String]?) -> [String]? {
        var seen = Set<String>()
        let unique = (ids ?? []).filter { !$0.isEmpty && seen.insert($0).inserted }
        return unique.isEmpty ? nil : unique
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
    /// Documents of this bottle only (its certificate, its receipt). Nil rather than empty,
    /// and each once by ID.
    public var attachments: [Attachment]? {
        didSet { attachments = Attachment.unique(attachments) }
    }
    /// Keys written by a newer version, kept and written back unchanged (docs/SYNC.md §1).
    /// Part of equality, so a merge that only brings them in is still saved.
    public var unknownFields = UnknownFields()

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case id, wineID, status, slot, storageNote, addedAt, purchaseDate, purchasePrice, currency, purchasePlace
        case receivedFrom, sizeML, tasting, expectedAt, reservedFor, attachments
    }

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
        // Required: "now" would differ on every device that reads the same record.
        addedAt = try c.decode(Date.self, forKey: .addedAt)
        purchaseDate = try c.decodeIfPresent(Date.self, forKey: .purchaseDate)
        purchasePrice = try c.decodeIfPresent(Decimal.self, forKey: .purchasePrice)
        currency = try c.decodeIfPresent(String.self, forKey: .currency)
        purchasePlace = try c.decodeIfPresent(String.self, forKey: .purchasePlace)
        receivedFrom = try c.decodeIfPresent(String.self, forKey: .receivedFrom)
        sizeML = try c.decodeIfPresent(Int.self, forKey: .sizeML) ?? 750
        tasting = try c.decodeIfPresent(Tasting.self, forKey: .tasting)
        expectedAt = try c.decodeIfPresent(Date.self, forKey: .expectedAt)
        reservedFor = try c.decodeIfPresent(String.self, forKey: .reservedFor)
        attachments = Attachment.unique(try c.decodeIfPresent([Attachment].self, forKey: .attachments))
        unknownFields = try UnknownFields(from: decoder, known: CodingKeys.self)
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(wineID, forKey: .wineID)
        try c.encode(status, forKey: .status)
        try c.encodeIfPresent(slot, forKey: .slot)
        try c.encodeIfPresent(storageNote, forKey: .storageNote)
        try c.encode(addedAt, forKey: .addedAt)
        try c.encodeIfPresent(purchaseDate, forKey: .purchaseDate)
        try c.encodeIfPresent(purchasePrice, forKey: .purchasePrice)
        try c.encodeIfPresent(currency, forKey: .currency)
        try c.encodeIfPresent(purchasePlace, forKey: .purchasePlace)
        try c.encodeIfPresent(receivedFrom, forKey: .receivedFrom)
        try c.encode(sizeML, forKey: .sizeML)
        try c.encodeIfPresent(tasting, forKey: .tasting)
        try c.encodeIfPresent(expectedAt, forKey: .expectedAt)
        try c.encodeIfPresent(reservedFor, forKey: .reservedFor)
        try c.encodeIfPresent(attachments, forKey: .attachments)
        try unknownFields.encode(to: encoder, known: CodingKeys.self)
    }
}

/// User preferences stored with the cellar.
public struct CellarSettings: Codable, Hashable, Sendable {
    /// ISO 4217 code used by default for prices ("EUR", "CHF"...).
    public var currency: String
    /// Searches the user named, shown as chips in the Cellar. Nil when there are none.
    public var savedSearches: [SavedSearch]?
    /// Keys written by a newer version, kept and written back unchanged (docs/SYNC.md §1).
    /// Part of equality, so a merge that only brings them in is still saved.
    public var unknownFields = UnknownFields()

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case currency, savedSearches
    }

    public init(currency: String = "EUR", savedSearches: [SavedSearch]? = nil) {
        self.currency = currency
        self.savedSearches = savedSearches
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        currency = try c.decode(String.self, forKey: .currency)
        savedSearches = try c.decodeIfPresent([SavedSearch].self, forKey: .savedSearches)
        unknownFields = try UnknownFields(from: decoder, known: CodingKeys.self)
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(currency, forKey: .currency)
        try c.encodeIfPresent(savedSearches, forKey: .savedSearches)
        try unknownFields.encode(to: encoder, known: CodingKeys.self)
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
    public var grape: String?
    /// Keys written by a newer version, kept and written back unchanged (docs/SYNC.md §1).
    /// Part of equality, so a merge that only brings them in is still saved.
    public var unknownFields = UnknownFields()

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case id, name, query, colors, maturities, rackID, tag, reservation, grape
    }

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
        grape = filter.grape
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        query = try c.decode(String.self, forKey: .query)
        colors = try c.decode([WineColor].self, forKey: .colors)
        maturities = try c.decode([Maturity].self, forKey: .maturities)
        rackID = try c.decodeIfPresent(UUID.self, forKey: .rackID)
        tag = try c.decodeIfPresent(String.self, forKey: .tag)
        reservation = try c.decodeIfPresent(String.self, forKey: .reservation)
        grape = try c.decodeIfPresent(String.self, forKey: .grape)
        unknownFields = try UnknownFields(from: decoder, known: CodingKeys.self)
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(name, forKey: .name)
        try c.encode(query, forKey: .query)
        try c.encode(colors, forKey: .colors)
        try c.encode(maturities, forKey: .maturities)
        try c.encodeIfPresent(rackID, forKey: .rackID)
        try c.encodeIfPresent(tag, forKey: .tag)
        try c.encodeIfPresent(reservation, forKey: .reservation)
        try c.encodeIfPresent(grape, forKey: .grape)
        try unknownFields.encode(to: encoder, known: CodingKeys.self)
    }

    public var filter: CellarFilter {
        CellarFilter(query: query, colors: Set(colors), maturities: Set(maturities), rackID: rackID, tag: tag,
                     reservation: reservation, grape: grape)
    }
}
