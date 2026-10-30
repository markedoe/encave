import Foundation

/// What to expect from a wine when tasting it: aromas, structure, serving and pairings.
/// Written as "expectedProfile" on the wine (docs/SYNC.md §1), from the bundled profiles
/// (`ExpectedProfiles`), the AI (from the label photo, or from the wine's identity only)
/// or the AI with web search. Read the same way by the Kotlin core
/// (`fixtures/expected-profile-vectors.json`).
public struct ExpectedProfile: Codable, Hashable, Sendable {
    public static let bundled = "bundled"
    public static let ai = "ai"
    public static let web = "web"

    /// Aroma ids of `Vocabulary`, each once, in order. Ids this version does not know are kept.
    public var aromas: [String]?
    /// Levels 1 to 5 by structure attribute id ("acidity"); nil when none.
    public var structure: [String: Int]?
    /// Serving temperature, in degrees Celsius (0…30).
    public var servingMin: Int?
    public var servingMax: Int?
    /// Minutes of decanting (0: none), up to a day.
    public var decantMinutes: Int?
    /// `DishCategory` ids, each once. Ids this version does not know are kept.
    public var pairings: [String]?
    /// A sentence or two from the AI, in `language`; bundled profiles have their note in each language.
    public var text: String?
    /// Language of `text` ("en", "fr").
    public var language: String?
    /// "bundled", "ai" or "web"; another value from a newer version is kept.
    public var source: String
    /// The model that wrote it (ai, web).
    public var model: String?
    /// The bundled profile's id ("pinotNoir").
    public var bundledID: String?
    /// When it was made.
    public var date: Date?
    /// Keys written by a newer version, kept and written back unchanged (docs/SYNC.md §1).
    public var unknownFields = UnknownFields()

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case aromas, structure, servingMin, servingMax, decantMinutes, pairings, text, language, source, model
        case bundledID = "bundled"
        case date
    }

    public init(source: String, aromas: [String]? = nil, structure: [String: Int]? = nil, servingMin: Int? = nil,
                servingMax: Int? = nil, decantMinutes: Int? = nil, pairings: [String]? = nil, text: String? = nil,
                language: String? = nil, model: String? = nil, bundledID: String? = nil, date: Date? = nil) {
        self.source = source
        self.aromas = aromas; self.structure = structure; self.servingMin = servingMin; self.servingMax = servingMax
        self.decantMinutes = decantMinutes; self.pairings = pairings; self.text = text; self.language = language
        self.model = model; self.bundledID = bundledID; self.date = date
        normalize()
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        aromas = try c.decodeIfPresent([String].self, forKey: .aromas)
        structure = try c.decodeIfPresent([String: Int].self, forKey: .structure)
        servingMin = try c.decodeIfPresent(Int.self, forKey: .servingMin)
        servingMax = try c.decodeIfPresent(Int.self, forKey: .servingMax)
        decantMinutes = try c.decodeIfPresent(Int.self, forKey: .decantMinutes)
        pairings = try c.decodeIfPresent([String].self, forKey: .pairings)
        text = try c.decodeIfPresent(String.self, forKey: .text)
        language = try c.decodeIfPresent(String.self, forKey: .language)
        source = try c.decodeIfPresent(String.self, forKey: .source) ?? ""
        model = try c.decodeIfPresent(String.self, forKey: .model)
        bundledID = try c.decodeIfPresent(String.self, forKey: .bundledID)
        date = try c.decodeIfPresent(Date.self, forKey: .date)
        unknownFields = try UnknownFields(from: decoder, known: CodingKeys.self)
        normalize()
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encodeIfPresent(aromas, forKey: .aromas)
        try c.encodeIfPresent(structure, forKey: .structure)
        try c.encodeIfPresent(servingMin, forKey: .servingMin)
        try c.encodeIfPresent(servingMax, forKey: .servingMax)
        try c.encodeIfPresent(decantMinutes, forKey: .decantMinutes)
        try c.encodeIfPresent(pairings, forKey: .pairings)
        try c.encodeIfPresent(text, forKey: .text)
        try c.encodeIfPresent(language, forKey: .language)
        try c.encode(source, forKey: .source)
        try c.encodeIfPresent(model, forKey: .model)
        try c.encodeIfPresent(bundledID, forKey: .bundledID)
        try c.encodeIfPresent(date, forKey: .date)
        try unknownFields.encode(to: encoder, known: CodingKeys.self)
    }

    /// The same on every device: ids once and not empty, levels in 1…5, temperatures in
    /// 0…30 (the lower first), decanting in 0…1440, empty texts, lists and objects left out.
    mutating func normalize() {
        aromas = Tasting.uniqueIDs(aromas)
        pairings = Tasting.uniqueIDs(pairings)
        let levels = (structure ?? [:]).filter { !$0.key.isEmpty }.mapValues { min(5, max(1, $0)) }
        structure = levels.isEmpty ? nil : levels
        if let value = servingMin, !(0...30).contains(value) { servingMin = nil }
        if let value = servingMax, !(0...30).contains(value) { servingMax = nil }
        if let low = servingMin, let high = servingMax, low > high { servingMin = high; servingMax = low }
        if let value = decantMinutes, !(0...1440).contains(value) { decantMinutes = nil }
        text = Tasting.id(text)
        language = Tasting.id(language)
        model = Tasting.id(model)
        bundledID = Tasting.id(bundledID)
    }

    /// Nothing to show: no aromas, structure, serving, pairings or text.
    public var isEmpty: Bool {
        aromas == nil && structure == nil && servingMin == nil && servingMax == nil && decantMinutes == nil
            && pairings == nil && text == nil
    }

    /// The structure level of an attribute (1…5).
    public func level(_ attribute: String) -> Int? { structure?[attribute] }

    /// Aromas compared with those found (a tasting, a guess).
    public func comparison(found: [String]) -> AromaComparison {
        AromaComparison(expected: aromas ?? [], found: found)
    }
}

/// The expected aromas and those found: "You found 4 of 6 expected aromas", and the others found.
public struct AromaComparison: Hashable, Sendable {
    /// Expected and found, in the expected order.
    public var matched: [String]
    /// Expected, not found.
    public var missed: [String]
    /// Found, not expected, in the order found.
    public var extra: [String]

    public init(expected: [String], found: [String]) {
        let foundSet = Set(found), expectedSet = Set(expected)
        var seen = Set<String>()
        let expected = expected.filter { seen.insert($0).inserted }
        matched = expected.filter { foundSet.contains($0) }
        missed = expected.filter { !foundSet.contains($0) }
        seen = []
        extra = found.filter { !expectedSet.contains($0) && seen.insert($0).inserted }
    }

    public var expectedCount: Int { matched.count + missed.count }
}

// MARK: Bundled profiles

/// The profiles bundled with the app, by appellation or region, blend, grape or color
/// (`fixtures/expected-profiles.json`, embedded as it is). Offline: nothing is sent.
public enum ExpectedProfiles {
    public struct Entry: Decodable, Hashable, Sendable, Identifiable {
        public var id: String
        public var appellations: [String]?
        public var grapes: [[String]]?
        public var colors: [String]?
        public var fallback: Bool?
        public var aromas: [String]
        public var structure: [String: Int]
        public var servingMin: Int
        public var servingMax: Int
        public var decantMinutes: Int
        public var pairings: [String]
        /// The note by language ("en", "fr").
        public var note: [String: String]

        func suits(_ color: WineColor?) -> Bool {
            guard let color, let colors, !colors.isEmpty else { return true }
            return colors.contains(color.rawValue)
        }
    }

    private struct File: Decodable { var profiles: [Entry] }

    public static let entries: [Entry] = {
        (try? JSONDecoder().decode(File.self, from: Data(json.utf8)))?.profiles ?? []
    }()

    public static func entry(id: String) -> Entry? { entries.first { $0.id == id } }

    /// The note of a bundled profile in a language, English otherwise.
    public static func note(id: String, language: String) -> String? {
        guard let entry = entry(id: id) else { return nil }
        return entry.note[language] ?? entry.note["en"]
    }

    /// Accents removed, lower case, anything but letters and digits as one space, trimmed.
    public static func normalized(_ text: String) -> String {
        let folded = String(String.UnicodeScalarView(text.decomposedStringWithCanonicalMapping.unicodeScalars.filter {
            $0.properties.generalCategory != .nonspacingMark
        })).lowercased()
        var out = ""
        var pendingSpace = false
        for character in folded {
            if character.isLetter || character.isNumber {
                if pendingSpace, !out.isEmpty { out.append(" ") }
                pendingSpace = false
                out.append(character)
            } else {
                pendingSpace = true
            }
        }
        return out
    }

    /// The name is this one, or a longer one starting with it and a space.
    static func names(_ name: String, match listed: [String]) -> Bool {
        guard !name.isEmpty else { return false }
        return listed.contains { name == $0 || name.hasPrefix($0 + " ") }
    }

    /// The wine's main grape: the largest share, else the first listed.
    static func mainGrape(of wine: Wine) -> String? {
        guard var best = wine.grapes.first else { return nil }
        var bestShare = wine.share(of: best) ?? 0
        for grape in wine.grapes.dropFirst() {
            let share = wine.share(of: grape) ?? 0
            if share > bestShare { best = grape; bestShare = share }
        }
        return best
    }

    /// The first match: appellation, then region, then a blend, then the main grape, then the color.
    public static func entry(for wine: Wine) -> Entry? {
        let candidates = entries.filter { $0.suits(wine.color) }
        let appellation = normalized(wine.appellation ?? ""), region = normalized(wine.region ?? "")
        for name in [appellation, region] where !name.isEmpty {
            if let entry = candidates.first(where: { names(name, match: $0.appellations ?? []) }) { return entry }
        }
        let grapes = Set(wine.grapes.map(normalized).filter { !$0.isEmpty })
        if grapes.count >= 2, let entry = candidates.first(where: { entry in
            guard let groups = entry.grapes, groups.count >= 2 else { return false }
            return groups.allSatisfy { group in group.contains { grapes.contains($0) } }
        }) { return entry }
        if let main = mainGrape(of: wine).map(normalized), !main.isEmpty, let entry = candidates.first(where: { entry in
            guard let groups = entry.grapes, groups.count == 1 else { return false }
            return groups[0].contains(main)
        }) { return entry }
        guard let color = wine.color else { return nil }
        return candidates.first { $0.fallback == true && $0.colors?.contains(color.rawValue) == true }
    }

    /// The bundled profile for a wine, as stored on a wine (without its note, given by language).
    public static func profile(for wine: Wine) -> ExpectedProfile? {
        entry(for: wine).map(profile(of:))
    }

    public static func profile(of entry: Entry) -> ExpectedProfile {
        ExpectedProfile(source: ExpectedProfile.bundled, aromas: entry.aromas, structure: entry.structure,
                        servingMin: entry.servingMin, servingMax: entry.servingMax, decantMinutes: entry.decantMinutes,
                        pairings: entry.pairings, bundledID: entry.id)
    }
}

extension Wine {
    /// What to expect: the profile kept with the wine, else the bundled one for its
    /// appellation, grapes or color; nil when none matches.
    public var expectedOrBundledProfile: ExpectedProfile? {
        if let expectedProfile, !expectedProfile.isEmpty { return expectedProfile }
        return ExpectedProfiles.profile(for: self)
    }
}
