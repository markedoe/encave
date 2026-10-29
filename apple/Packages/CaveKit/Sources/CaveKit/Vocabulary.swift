import Foundation

/// The shared vocabulary of the guided tasting sheet and the wine details: stable ids, never
/// labels, the same in the Kotlin core. `fixtures/tasting-vocabulary.json` is the reference
/// (checked by `VocabularyTests`); the apps give each id an English and a French label.
/// Records keep ids this version does not know and write them back unchanged.
public enum Vocabulary {
    /// What an id names: labels are looked up by kind and id, as the same id ("medium",
    /// "orange") can mean different things.
    public enum Kind: String, CaseIterable, Sendable {
        case aroma, aromaFamily, structure, level, intensity, hue, dosage, sweetness, ageing, farming, closure
    }

    /// An id and the wine colors it is offered for first (empty: every color).
    public struct Entry: Hashable, Sendable {
        public var id: String
        public var colors: Set<WineColor>

        init(_ id: String, _ colors: [String]) {
            self.id = id
            self.colors = Set(colors.compactMap(WineColor.init(rawValue:)))
        }

        /// Offered first for a wine of this color; everything is for a wine without a color.
        public func suits(_ color: WineColor?) -> Bool {
            guard let color, !colors.isEmpty else { return true }
            return colors.contains(color)
        }
    }

    public struct AromaFamily: Hashable, Sendable, Identifiable {
        public var id: String
        public var aromas: [Entry]
    }

    /// A structure attribute of a tasting (sweetness, acidity…), noted 1 to 5. `scale` names
    /// the words for its five levels (see `levels`).
    public struct Attribute: Hashable, Sendable, Identifiable {
        public var id: String
        public var scale: String
        /// Offered for these colors only, unless already set (tannin); nil for every color.
        public var colors: Set<WineColor>?

        init(id: String, scale: String, colors: [String]?) {
            self.id = id; self.scale = scale
            self.colors = colors.map { Set($0.compactMap(WineColor.init(rawValue:))) }
        }

        /// Shown for a wine of this color: always when it has a value, or for a wine without a color.
        public func isOffered(for color: WineColor?, value: Int? = nil) -> Bool {
            guard value == nil, let colors, let color else { return true }
            return colors.contains(color)
        }
    }

    public static let aromaFamilies: [AromaFamily] = [
        AromaFamily(id: "redFruit", aromas: [
            Entry("fruit.strawberry", ["red", "rose", "sparkling"]),
            Entry("fruit.raspberry", ["red", "rose", "sparkling"]),
            Entry("fruit.redCherry", ["red", "rose", "fortified"]),
            Entry("fruit.redcurrant", ["red", "rose"]),
        ]),
        AromaFamily(id: "blackFruit", aromas: [
            Entry("fruit.blackberry", ["red", "fortified"]),
            Entry("fruit.blackcurrant", ["red", "fortified"]),
            Entry("fruit.blackCherry", ["red", "fortified"]),
            Entry("fruit.plum", ["red", "fortified"]),
        ]),
        AromaFamily(id: "citrus", aromas: [
            Entry("fruit.lemon", ["white", "rose", "sparkling", "orange"]),
            Entry("fruit.grapefruit", ["white", "rose", "sparkling", "orange"]),
            Entry("fruit.orangePeel", ["white", "sparkling", "dessert", "fortified", "orange"]),
        ]),
        AromaFamily(id: "orchardFruit", aromas: [
            Entry("fruit.greenApple", ["white", "sparkling", "orange"]),
            Entry("fruit.pear", ["white", "sparkling", "dessert"]),
        ]),
        AromaFamily(id: "stoneTropicalFruit", aromas: [
            Entry("fruit.peach", ["white", "rose", "sparkling", "dessert"]),
            Entry("fruit.apricot", ["white", "dessert", "orange"]),
            Entry("fruit.pineapple", ["white", "dessert"]),
            Entry("fruit.passionFruit", ["white", "sparkling"]),
            Entry("fruit.lychee", ["white", "rose"]),
        ]),
        AromaFamily(id: "floral", aromas: [
            Entry("floral.rose", ["red", "white", "rose"]),
            Entry("floral.violet", ["red"]),
            Entry("floral.acacia", ["white", "sparkling", "dessert"]),
            Entry("floral.elderflower", ["white", "sparkling"]),
        ]),
        AromaFamily(id: "herbal", aromas: [
            Entry("herbal.cutGrass", ["white", "sparkling"]),
            Entry("herbal.greenPepper", ["red", "white"]),
            Entry("herbal.mint", ["red", "white"]),
            Entry("herbal.garrigue", ["red", "white", "rose"]),
        ]),
        AromaFamily(id: "spice", aromas: [
            Entry("spice.blackPepper", ["red", "fortified"]),
            Entry("spice.clove", ["red", "dessert", "fortified"]),
            Entry("spice.cinnamon", ["red", "white", "dessert", "fortified"]),
            Entry("spice.liquorice", ["red", "fortified"]),
        ]),
        AromaFamily(id: "oakToast", aromas: [
            Entry("oak.vanilla", ["red", "white", "fortified"]),
            Entry("oak.toast", ["red", "white", "sparkling"]),
            Entry("oak.smoke", ["red", "white"]),
            Entry("oak.cedar", ["red"]),
        ]),
        AromaFamily(id: "earthMineral", aromas: [
            Entry("earth.flint", ["white", "sparkling"]),
            Entry("earth.petrol", ["white"]),
            Entry("earth.mushroom", ["red", "white"]),
            Entry("earth.forestFloor", ["red"]),
        ]),
        AromaFamily(id: "animalLeather", aromas: [
            Entry("animal.leather", ["red", "fortified"]),
            Entry("animal.game", ["red"]),
            Entry("animal.tobacco", ["red", "fortified"]),
        ]),
        AromaFamily(id: "pastryButter", aromas: [
            Entry("pastry.brioche", ["white", "sparkling"]),
            Entry("pastry.butter", ["white"]),
            Entry("pastry.hazelnut", ["white", "sparkling", "dessert", "fortified", "orange"]),
        ]),
        AromaFamily(id: "honeyDriedFruit", aromas: [
            Entry("dried.honey", ["white", "sparkling", "dessert", "fortified", "orange"]),
            Entry("dried.raisin", ["dessert", "fortified"]),
            Entry("dried.fig", ["red", "dessert", "fortified"]),
            Entry("dried.walnut", ["white", "dessert", "fortified", "orange"]),
        ]),
    ]

    /// Every aroma id, family by family.
    public static var aromaIDs: [String] { aromaFamilies.flatMap { $0.aromas.map(\.id) } }

    /// The family of an aroma id, nil for an id this version does not know.
    public static func family(ofAroma id: String) -> AromaFamily? {
        aromaFamilies.first { $0.aromas.contains { $0.id == id } }
    }

    public static let structure: [Attribute] = [
        Attribute(id: "sweetness", scale: "sweetness", colors: nil),
        Attribute(id: "acidity", scale: "level", colors: nil),
        Attribute(id: "tannin", scale: "level", colors: ["red", "orange", "fortified"]),
        Attribute(id: "body", scale: "body", colors: nil),
        Attribute(id: "alcohol", scale: "level", colors: nil),
        Attribute(id: "length", scale: "length", colors: nil),
    ]

    /// The words for levels 1 to 5 of each scale (ids of kind `level`).
    public static let levels: [String: [String]] = [
        "level": ["low", "mediumMinus", "medium", "mediumPlus", "high"],
        "sweetness": ["dry", "offDry", "medium", "sweet", "luscious"],
        "body": ["light", "mediumMinus", "medium", "mediumPlus", "full"],
        "length": ["short", "mediumMinus", "medium", "mediumPlus", "long"],
    ]

    /// The word for a level (1 to 5) of an attribute, as an id of kind `level`.
    public static func level(_ value: Int, of attribute: Attribute) -> String? {
        guard let words = levels[attribute.scale], (1...words.count).contains(value) else { return nil }
        return words[value - 1]
    }

    public static let intensities = ["pale", "medium", "deep"]

    public static let hues: [Entry] = [
        Entry("purple", ["red"]),
        Entry("ruby", ["red", "fortified"]),
        Entry("garnet", ["red", "fortified"]),
        Entry("tawny", ["red", "fortified", "dessert"]),
        Entry("lemonGreen", ["white", "sparkling"]),
        Entry("lemon", ["white", "sparkling", "dessert"]),
        Entry("gold", ["white", "sparkling", "dessert", "orange"]),
        Entry("amber", ["white", "dessert", "fortified", "orange"]),
        Entry("pink", ["rose", "sparkling"]),
        Entry("salmon", ["rose", "sparkling"]),
        Entry("onionSkin", ["rose", "sparkling"]),
        Entry("orange", ["rose", "orange"]),
        Entry("brown", ["red", "white", "dessert", "fortified"]),
    ]

    public static let dosages = ["brutNature", "extraBrut", "brut", "extraDry", "sec", "demiSec", "doux"]
    public static let sweetnesses = ["dry", "offDry", "mediumSweet", "sweet"]
    public static let ageings = ["oakBarrel", "newOak", "foudre", "stainlessSteel", "concrete", "amphora", "onLees"]
    public static let farmings = ["organic", "biodynamic", "natural", "sustainable", "conventional"]
    public static let closures = ["naturalCork", "technicalCork", "screwCap", "glass", "synthetic", "crownCap"]
    /// Offered while typing a classification, which stays free text.
    public static let classificationSuggestions = [
        "Grand Cru", "Premier Cru", "Grand Cru Classé", "Premier Grand Cru Classé", "Cru Classé", "Cru Bourgeois",
        "Cru Artisan", "Village", "DOCG", "Riserva", "Reserva", "Gran Reserva", "Grosses Gewächs", "Erste Lage",
    ]

    /// The ids of a kind, in the order shown.
    public static func ids(_ kind: Kind) -> [String] {
        switch kind {
        case .aroma: aromaIDs
        case .aromaFamily: aromaFamilies.map(\.id)
        case .structure: structure.map(\.id)
        case .level: Array(Set(levels.values.joined())).sorted()
        case .intensity: intensities
        case .hue: hues.map(\.id)
        case .dosage: dosages
        case .sweetness: sweetnesses
        case .ageing: ageings
        case .farming: farmings
        case .closure: closures
        }
    }

    private static let labels = LabelBox()

    /// How the app names an id in the person's language, for search; set once at launch.
    /// Without it (or for an id it does not know) the id itself is used.
    public static var labelProvider: @Sendable (Kind, String) -> String? {
        get { labels.provider }
        set { labels.provider = newValue }
    }

    /// The label of an id, or the id itself when none is known (an id from a newer version).
    public static func label(_ kind: Kind, _ id: String) -> String { labelProvider(kind, id) ?? id }
}

/// The label provider, behind a lock: set at launch, read from any thread.
private final class LabelBox: @unchecked Sendable {
    private let lock = NSLock()
    private var value: @Sendable (Vocabulary.Kind, String) -> String? = { _, _ in nil }

    var provider: @Sendable (Vocabulary.Kind, String) -> String? {
        get { lock.lock(); defer { lock.unlock() }; return value }
        set { lock.lock(); value = newValue; lock.unlock() }
    }
}
