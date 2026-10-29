import CaveKit
import Foundation

/// The labels of the shared vocabulary (`fixtures/tasting-vocabulary.json`): each id of the
/// guided tasting sheet and of the wine details, in the app's language. Records store the
/// ids; an id this version does not know has no label here and is shown as it is.
///
/// One function per kind, each case on one line (`case` with the id, then the localized label):
/// apple/tools/l10n.py reads this file to check that every id of the fixture has an English
/// and a French label. The same English word can need two French words ("Medium" is
/// "Moyen" for a level, "Moyenne" for an intensity): such keys carry a "[case]" suffix,
/// with their English and French in the script's VARIANTS.
enum VocabularyLabels {
    /// The label of an id, nil for an id this version does not know.
    static func label(_ kind: Vocabulary.Kind, _ id: String) -> String? {
        switch kind {
        case .aroma: aroma(id)
        case .aromaFamily: aromaFamily(id)
        case .structure: structure(id)
        case .level: level(id)
        case .intensity: intensity(id)
        case .hue: hue(id)
        case .dosage: dosage(id)
        case .sweetness: sweetness(id)
        case .ageing: ageing(id)
        case .farming: farming(id)
        case .closure: closure(id)
        }
    }

    /// The label of an id, or the id itself when it is unknown (from a newer version).
    static func text(_ kind: Vocabulary.Kind, _ id: String) -> String { label(kind, id) ?? id }

    /// Lets CaveKit search the vocabulary by its labels in the person's language. Called
    /// once at launch, before anything is searched.
    static func install() {
        Vocabulary.labelProvider = { kind, id in VocabularyLabels.label(kind, id) }
    }

    private static func aroma(_ id: String) -> String? {
        switch id {
        case "fruit.strawberry": String(localized: "Strawberry")
        case "fruit.raspberry": String(localized: "Raspberry")
        case "fruit.redCherry": String(localized: "Red cherry")
        case "fruit.redcurrant": String(localized: "Redcurrant")
        case "fruit.blackberry": String(localized: "Blackberry")
        case "fruit.blackcurrant": String(localized: "Blackcurrant")
        case "fruit.blackCherry": String(localized: "Black cherry")
        case "fruit.plum": String(localized: "Plum")
        case "fruit.lemon": String(localized: "Lemon")
        case "fruit.grapefruit": String(localized: "Grapefruit")
        case "fruit.orangePeel": String(localized: "Orange peel")
        case "fruit.greenApple": String(localized: "Green apple")
        case "fruit.pear": String(localized: "Pear")
        case "fruit.peach": String(localized: "Peach")
        case "fruit.apricot": String(localized: "Apricot")
        case "fruit.pineapple": String(localized: "Pineapple")
        case "fruit.passionFruit": String(localized: "Passion fruit")
        case "fruit.lychee": String(localized: "Lychee")
        case "floral.rose": String(localized: "Rose")
        case "floral.violet": String(localized: "Violet")
        case "floral.acacia": String(localized: "Acacia")
        case "floral.elderflower": String(localized: "Elderflower")
        case "herbal.cutGrass": String(localized: "Cut grass")
        case "herbal.greenPepper": String(localized: "Green pepper")
        case "herbal.mint": String(localized: "Mint")
        case "herbal.garrigue": String(localized: "Garrigue")
        case "spice.blackPepper": String(localized: "Black pepper")
        case "spice.clove": String(localized: "Clove")
        case "spice.cinnamon": String(localized: "Cinnamon")
        case "spice.liquorice": String(localized: "Liquorice")
        case "oak.vanilla": String(localized: "Vanilla")
        case "oak.toast": String(localized: "Toast")
        case "oak.smoke": String(localized: "Smoke")
        case "oak.cedar": String(localized: "Cedar")
        case "earth.flint": String(localized: "Flint")
        case "earth.petrol": String(localized: "Petrol")
        case "earth.mushroom": String(localized: "Mushroom")
        case "earth.forestFloor": String(localized: "Forest floor")
        case "animal.leather": String(localized: "Leather")
        case "animal.game": String(localized: "Game")
        case "animal.tobacco": String(localized: "Tobacco")
        case "pastry.brioche": String(localized: "Brioche")
        case "pastry.butter": String(localized: "Butter")
        case "pastry.hazelnut": String(localized: "Hazelnut")
        case "dried.honey": String(localized: "Honey")
        case "dried.raisin": String(localized: "Raisin")
        case "dried.fig": String(localized: "Dried fig")
        case "dried.walnut": String(localized: "Walnut")
        default: nil
        }
    }

    private static func aromaFamily(_ id: String) -> String? {
        switch id {
        case "redFruit": String(localized: "Red fruit")
        case "blackFruit": String(localized: "Black fruit")
        case "citrus": String(localized: "Citrus")
        case "orchardFruit": String(localized: "Orchard fruit")
        case "stoneTropicalFruit": String(localized: "Stone and tropical fruit")
        case "floral": String(localized: "Floral")
        case "herbal": String(localized: "Herbal")
        case "spice": String(localized: "Spice")
        case "oakToast": String(localized: "Oak and toast")
        case "earthMineral": String(localized: "Earth and mineral")
        case "animalLeather": String(localized: "Animal and leather")
        case "pastryButter": String(localized: "Pastry and butter")
        case "honeyDriedFruit": String(localized: "Honey and dried fruit")
        default: nil
        }
    }

    private static func structure(_ id: String) -> String? {
        switch id {
        case "sweetness": String(localized: "Sweetness")
        case "acidity": String(localized: "Acidity")
        case "tannin": String(localized: "Tannin")
        case "body": String(localized: "Body")
        case "alcohol": String(localized: "Alcohol")
        case "length": String(localized: "Length")
        default: nil
        }
    }

    /// The word for a level of a structure attribute: acidity and length are feminine in
    /// French ("Acidité : Moyenne+"), as on Android.
    static func levelText(_ id: String, of attribute: String) -> String {
        guard attribute == "acidity" || attribute == "length" else { return text(.level, id) }
        switch id {
        case "mediumMinus": return String(localized: "Medium− [feminine]")
        case "medium": return String(localized: "Medium [feminine]")
        case "mediumPlus": return String(localized: "Medium+ [feminine]")
        case "high": return String(localized: "High [feminine]")
        case "short": return String(localized: "Short [feminine]")
        case "long": return String(localized: "Long [feminine]")
        default: return text(.level, id)
        }
    }

    private static func level(_ id: String) -> String? {
        switch id {
        case "low": String(localized: "Low")
        case "mediumMinus": String(localized: "Medium−")
        case "medium": String(localized: "Medium")
        case "mediumPlus": String(localized: "Medium+")
        case "high": String(localized: "High")
        case "dry": String(localized: "Dry")
        case "offDry": String(localized: "Off-dry")
        case "sweet": String(localized: "Sweet")
        case "luscious": String(localized: "Luscious")
        case "light": String(localized: "Light")
        case "full": String(localized: "Full")
        case "short": String(localized: "Short")
        case "long": String(localized: "Long")
        default: nil
        }
    }

    private static func intensity(_ id: String) -> String? {
        switch id {
        case "pale": String(localized: "Pale")
        case "medium": String(localized: "Medium [intensity]")
        case "deep": String(localized: "Deep")
        default: nil
        }
    }

    private static func hue(_ id: String) -> String? {
        switch id {
        case "purple": String(localized: "Purple")
        case "ruby": String(localized: "Ruby")
        case "garnet": String(localized: "Garnet")
        case "tawny": String(localized: "Tawny")
        case "lemonGreen": String(localized: "Lemon-green")
        case "lemon": String(localized: "Lemon [hue]")
        case "gold": String(localized: "Gold")
        case "amber": String(localized: "Amber")
        case "pink": String(localized: "Pink")
        case "salmon": String(localized: "Salmon")
        case "onionSkin": String(localized: "Onion skin")
        case "orange": String(localized: "Orange [hue]")
        case "brown": String(localized: "Brown")
        default: nil
        }
    }

    private static func dosage(_ id: String) -> String? {
        switch id {
        case "brutNature": String(localized: "Brut Nature")
        case "extraBrut": String(localized: "Extra Brut")
        case "brut": String(localized: "Brut")
        case "extraDry": String(localized: "Extra Dry")
        case "sec": String(localized: "Sec")
        case "demiSec": String(localized: "Demi-Sec")
        case "doux": String(localized: "Doux")
        default: nil
        }
    }

    private static func sweetness(_ id: String) -> String? {
        switch id {
        case "dry": String(localized: "Dry")
        case "offDry": String(localized: "Off-dry")
        case "mediumSweet": String(localized: "Medium-sweet")
        case "sweet": String(localized: "Sweet")
        default: nil
        }
    }

    private static func ageing(_ id: String) -> String? {
        switch id {
        case "oakBarrel": String(localized: "Oak barrel")
        case "newOak": String(localized: "New oak")
        case "foudre": String(localized: "Foudre")
        case "stainlessSteel": String(localized: "Stainless steel")
        case "concrete": String(localized: "Concrete")
        case "amphora": String(localized: "Amphora")
        case "onLees": String(localized: "On lees")
        default: nil
        }
    }

    private static func farming(_ id: String) -> String? {
        switch id {
        case "organic": String(localized: "Organic")
        case "biodynamic": String(localized: "Biodynamic")
        case "natural": String(localized: "Natural")
        case "sustainable": String(localized: "Sustainable")
        case "conventional": String(localized: "Conventional")
        default: nil
        }
    }

    private static func closure(_ id: String) -> String? {
        switch id {
        case "naturalCork": String(localized: "Natural cork")
        case "technicalCork": String(localized: "Technical cork")
        case "screwCap": String(localized: "Screw cap")
        case "glass": String(localized: "Glass stopper")
        case "synthetic": String(localized: "Synthetic cork")
        case "crownCap": String(localized: "Crown cap")
        default: nil
        }
    }
}
