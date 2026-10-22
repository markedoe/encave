import Crypto
import Foundation

/// A small fictional cellar for trying the app and for previews.
/// Producers are invented; appellations are real places.
///
/// Its IDs are fixed (`SampleData.id(_:_:)`, the same on every platform), so the app can
/// tell the sample apart from the user's own records and remove it on its own.
public enum SampleData {
    /// Labels are passed in so the app can localize them.
    public struct Labels: Sendable {
        public var mainRack = "Main rack"
        public var wineFridge = "Wine fridge"
        public var bins = "Diamond bins"
        public var deepRack = "Double-depth rack"
        public var honeycomb = "Honeycomb rack"
        public var stairs = "Under the stairs"
        public var largeFridge = "Large wine fridge"
        public var tastingNote = "Silky, red fruit, long finish."
        public var kitchen = "Kitchen"
        public var occasion = "Dinner with friends"
        public var nose = "Cherry, violet, a touch of undergrowth"
        public var palate = "Fine tannins, fresh acidity"
        public var pairing = "Roast duck"
        public var friendsPlace = "At Julie and Marc's"
        public var friendsNote = "Very fresh, perfect with the cheese platter."
        public var restaurantPlace = "Bistro on the harbour, Lisbon"
        public var restaurantNote = "Mineral and salty, great with grilled fish."
        public var tagToBuy = "For summer"
        public var tagGift = "Gift idea"
        public var merchant = "Wine merchant"
        public var tagBirthYear = "Birth year"
        public var occasion2 = "Christmas dinner"
        public init() {}
    }

    /// A wine fridge with two zones: layers of each shelf from the top, and bottles of each
    /// row (layer) from the top. Also the rack editor's preset.
    public static let twoZoneFridgeLayers = [1, 2, 1, 1, 1, 2, 2, 2, 1, 1]
    public static let twoZoneFridgeRows = [6, 5, 6, 6, 6, 6, 4, 5, 4, 5, 4, 5, 6, 6]

    public static func cellar(
        year: Int = CellarYear.current,
        labels: Labels = Labels()
    ) -> Cellar {
        var cellar = Cellar()
        var ids = IDs()
        let main = Rack(id: ids.next(.rack), name: labels.mainRack, rows: 6, columns: 8)
        let small = Rack(id: ids.next(.rack), name: labels.wineFridge, kind: .shelves, rows: 4, columns: 6, depth: 2)
        let bins = Rack(id: ids.next(.rack), name: labels.bins, kind: .bins, rows: 2, columns: 3, binCapacity: 10)
        // One rack of every kind, so the sample shows them all.
        let deep = Rack(id: ids.next(.rack), name: labels.deepRack, rows: 4, columns: 6, depth: 2)
        let honeycomb = Rack(id: ids.next(.rack), name: labels.honeycomb, kind: .staggered, rows: 5, columns: 8)
        let stairs = Rack(id: ids.next(.rack), name: labels.stairs, rows: 5, columns: 6, rowColumns: [2, 3, 4, 5, 6])
        // A fridge with two temperature zones (shelves 1–4, then 5–10), two deep: shelf 2 holds
        // 6 bottles and 5 on top, shelves 6–8 hold 5 and 4 on top, the others one layer of 6.
        let largeFridge = Rack(id: ids.next(.rack), name: labels.largeFridge, kind: .staggered, rows: 14, columns: 6, depth: 2,
                               rowColumns: SampleData.twoZoneFridgeRows, shelfLayers: SampleData.twoZoneFridgeLayers,
                               zoneBreaks: [4])
        for rack in [main, small, bins, deep, honeycomb, stairs, largeFridge] { cellar.addRack(rack) }

        let entries: [(Wine, Int, Rack?)] = [
            (Wine(id: ids.next(.wine), name: "Clos des Tilleuls", producer: "Domaine Arnaud", vintage: year - 9, color: .red,
                  region: "Bordeaux", country: "France", appellation: "Pauillac", grapes: ["Cabernet Sauvignon", "Merlot"],
                  drinkFrom: year - 2, drinkUntil: year + 8), 3, main),
            (Wine(id: ids.next(.wine), name: "Les Pierres Blanches", producer: "Maison Clément", vintage: year - 4, color: .white,
                  region: "Bourgogne", country: "France", appellation: "Meursault", grapes: ["Chardonnay"],
                  drinkFrom: year - 1, drinkUntil: year), 2, main),
            (Wine(id: ids.next(.wine), name: "Cuvée du Lac", producer: "Cave des Coteaux", vintage: year - 2, color: .white,
                  region: "Vaud", country: "Switzerland", grapes: ["Chasselas"],
                  drinkFrom: year - 1, drinkUntil: year + 2), 4, small),
            (Wine(id: ids.next(.wine), name: "Terrasses du Sud", producer: "Domaine Ferrand", vintage: year - 3, color: .red,
                  region: "Rhône", country: "France", appellation: "Gigondas", grapes: ["Grenache", "Syrah"],
                  drinkFrom: year + 1, drinkUntil: year + 10), 6, bins),
            (Wine(id: ids.next(.wine), name: "Rosé de Provence", producer: "Château Lumière", vintage: year - 5, color: .rose,
                  region: "Provence", country: "France", grapes: ["Grenache", "Cinsault"],
                  drinkFrom: year - 4, drinkUntil: year - 2), 1, nil),
            (Wine(id: ids.next(.wine), name: "Brut Réserve", producer: "Maison Aubry", color: .sparkling,
                  region: "Champagne", country: "France", grapes: ["Chardonnay", "Pinot Noir"]), 2, small),
            (Wine(id: ids.next(.wine), name: "Vendanges Tardives", producer: "Domaine Weber", vintage: year - 12, color: .dessert,
                  region: "Alsace", country: "France", grapes: ["Gewurztraminer"],
                  drinkFrom: year - 6, drinkUntil: year + 15), 1, main),
            (Wine(id: ids.next(.wine), name: "Riserva Vigna Alta", producer: "Cascina Ferri", vintage: year - 10, color: .red,
                  region: "Piedmont", country: "Italy", appellation: "Barolo", grapes: ["Nebbiolo"],
                  drinkFrom: year - 2, drinkUntil: year + 12), 8, deep),
            (Wine(id: ids.next(.wine), name: "Savagnin Ouillé", producer: "Domaine des Granges", vintage: year - 5, color: .white,
                  region: "Jura", country: "France", appellation: "Arbois", grapes: ["Savagnin"],
                  drinkFrom: year - 2, drinkUntil: year + 6), 5, honeycomb),
            (Wine(id: ids.next(.wine), name: "Qvevri Amber", producer: "Marani Tsiskari", vintage: year - 3, color: .orange,
                  region: "Kakheti", country: "Georgia", grapes: ["Rkatsiteli"],
                  drinkFrom: year - 1, drinkUntil: year + 4), 3, largeFridge),
            (Wine(id: ids.next(.wine), name: "Vintage Port", producer: "Quinta da Serra", vintage: year - 15, color: .fortified,
                  region: "Douro", country: "Portugal", appellation: "Porto", grapes: ["Touriga Nacional"],
                  drinkFrom: year - 5, drinkUntil: year + 25, tags: [labels.tagBirthYear]), 4, stairs),
        ]

        let day: TimeInterval = 86_400
        let prices: [Decimal] = [38, 54, 16, 24, 14, 36, 29, 62, 27, 22, 48]
        for (index, (wine, quantity, rack)) in entries.enumerated() {
            let slots = rack.map { Array(cellar.freeSlots(in: $0).prefix(quantity)) } ?? []
            let purchase = PurchaseInfo(
                date: .now - Double(30 * (index + 1)) * day, price: prices[index % prices.count],
                currency: cellar.settings.currency, storageNote: rack == nil ? labels.kitchen : nil
            )
            if let added = try? cellar.addBottles(of: wine, quantity: quantity, slots: slots, purchase: purchase,
                                                  addedAt: .now - Double(30 * (index + 1)) * day) {
                ids.rekey(added, in: &cellar)
            }
        }

        // Two bottles kept for an occasion.
        if let barolo = cellar.wines.values.first(where: { $0.name == "Riserva Vigna Alta" }) {
            try? cellar.reserve(Array(cellar.bottles(of: barolo.id).prefix(2).map(\.id)), for: labels.occasion2)
        }

        // Last year's vintage bought en primeur, not delivered yet.
        let primeur = Wine(id: ids.next(.wine), name: "Clos des Tilleuls", producer: "Domaine Arnaud", vintage: year - 1, color: .red,
                           region: "Bordeaux", country: "France", appellation: "Pauillac",
                           grapes: ["Cabernet Sauvignon", "Merlot"], drinkFrom: year + 6, drinkUntil: year + 20)
        if let added = try? cellar.addBottles(of: primeur, quantity: 6, purchase: PurchaseInfo(
            date: .now - 60 * day, price: 41, currency: cellar.settings.currency, place: labels.merchant,
            isOrdered: true, expectedAt: .now + 75 * day)) {
            ids.rekey(added, in: &cellar)
        }

        // A few past tastings for the journal.
        let tasted = Wine(id: ids.next(.wine), name: "Vieilles Vignes", producer: "Domaine Arnaud", vintage: year - 7, color: .red,
                          region: "Bourgogne", country: "France", appellation: "Gevrey-Chambertin", grapes: ["Pinot Noir"],
                          drinkFrom: year - 3, drinkUntil: year + 4)
        if let added = try? cellar.addBottles(of: tasted, quantity: 2) {
            let bottles = ids.rekey(added, in: &cellar)
            try? cellar.drink(bottles[0].id, tasting: Tasting(date: .now - 12 * day, rating: 5,
                note: labels.tastingNote, occasion: labels.occasion, nose: labels.nose,
                palate: labels.palate, foodPairing: labels.pairing))
            try? cellar.drink(bottles[1].id, tasting: Tasting(date: .now - 40 * day, rating: 4))
        }

        // Wines found elsewhere, one of them on the shopping list.
        let atFriends = Wine(id: ids.next(.wine), name: "Coteaux du Layon", producer: "Domaine des Sablons", vintage: year - 6,
                             color: .dessert, region: "Loire", country: "France", grapes: ["Chenin Blanc"])
        cellar.addDiscovery(Discovery(id: ids.next(.discovery), wineID: atFriends.id, date: .now - 9 * day, setting: .friends,
                                      place: labels.friendsPlace, rating: 4, note: labels.friendsNote,
                                      tags: [labels.tagGift], wantsToBuy: true), wine: atFriends)
        let onTrip = Wine(id: ids.next(.wine), name: "Alvarinho Reserva", producer: "Quinta do Mar", vintage: year - 2, color: .white,
                          region: "Vinho Verde", country: "Portugal", grapes: ["Alvarinho"])
        cellar.addDiscovery(Discovery(id: ids.next(.discovery), wineID: onTrip.id, date: .now - 25 * day, setting: .restaurant,
                                      place: labels.restaurantPlace, rating: 5, note: labels.restaurantNote,
                                      tags: [labels.tagToBuy], price: 32, currency: cellar.settings.currency,
                                      wantsToBuy: true), wine: onTrip)
        return cellar
    }

    // MARK: - Fixed IDs

    /// The kinds of records of the sample, each numbered from 0.
    public enum Kind: String, CaseIterable, Sendable {
        case rack, wine, bottle, discovery
    }

    /// The namespace of the sample's name-based IDs.
    public static let namespace = UUID(uuidString: "3B0B9C8E-5E7A-4F0D-9A61-2D4C8E1F7A20")!

    /// The ID of the `index`-th record of a kind in the sample: a version 5 UUID of the
    /// name "rack-0", "wine-3"... in `namespace`. The same in the Android app.
    public static func id(_ kind: Kind, _ index: Int) -> UUID {
        nameBasedUUID(namespace: namespace, name: "\(kind.rawValue)-\(index)")
    }

    /// More than the sample uses of each kind, so a longer sample keeps working.
    static let maximumPerKind = 128

    /// Every ID the sample may use.
    public static let ids: Set<UUID> = Set(Kind.allCases.flatMap { kind in
        (0..<maximumPerKind).map { id(kind, $0) }
    })

    /// A version 5 (SHA-1, name-based) UUID, as in RFC 9562.
    static func nameBasedUUID(namespace: UUID, name: String) -> UUID {
        var bytes = withUnsafeBytes(of: namespace.uuid) { Array($0) }
        bytes.append(contentsOf: Array(name.utf8))
        var hash = Array(Insecure.SHA1.hash(data: bytes).prefix(16))
        hash[6] = (hash[6] & 0x0F) | 0x50
        hash[8] = (hash[8] & 0x3F) | 0x80
        return UUID(uuid: (hash[0], hash[1], hash[2], hash[3], hash[4], hash[5], hash[6], hash[7],
                           hash[8], hash[9], hash[10], hash[11], hash[12], hash[13], hash[14], hash[15]))
    }

    /// Hands out the sample's IDs in order while it is built.
    struct IDs {
        private var counts: [Kind: Int] = [:]

        mutating func next(_ kind: Kind) -> UUID {
            let index = counts[kind, default: 0]
            counts[kind] = index + 1
            return SampleData.id(kind, index)
        }

        /// Gives bottles just added their fixed IDs, in order; returns them as now stored.
        @discardableResult
        mutating func rekey(_ added: [Bottle], in cellar: inout Cellar) -> [Bottle] {
            added.map { bottle in
                var copy = bottle
                copy.id = next(.bottle)
                cellar.bottles[bottle.id] = nil
                cellar.bottles[copy.id] = copy
                return copy
            }
        }
    }
}

public extension Cellar {
    /// Some records of the sample cellar are still here.
    var containsSample: Bool {
        let ids = SampleData.ids
        return wines.keys.contains(where: ids.contains) || bottles.keys.contains(where: ids.contains)
            || racks.contains { ids.contains($0.id) } || discoveries.keys.contains(where: ids.contains)
    }

    /// Nothing but the sample cellar (or what is left of it): joining another device
    /// replaces it rather than putting it together with the real cellar.
    var isOnlySample: Bool {
        let ids = SampleData.ids
        return !isEmpty && wines.keys.allSatisfy(ids.contains) && bottles.keys.allSatisfy(ids.contains)
            && racks.allSatisfy { ids.contains($0.id) } && discoveries.keys.allSatisfy(ids.contains)
    }

    /// The cellar without the sample's records. A sample wine stays while one of the
    /// user's bottles or discoveries refers to it, and a sample rack while it holds one of
    /// the user's bottles. Settings are kept.
    func removingSample() -> Cellar {
        let ids = SampleData.ids
        var result = self
        result.bottles = bottles.filter { !ids.contains($0.key) }
        result.discoveries = discoveries.filter { !ids.contains($0.key) }
        let usedWines = Set(result.bottles.values.map(\.wineID)).union(result.discoveries.values.map(\.wineID))
        result.wines = wines.filter { !ids.contains($0.key) || usedWines.contains($0.key) }
        let usedRacks = Set(result.bottles.values.compactMap(\.slot?.rackID))
        result.racks = racks.filter { !ids.contains($0.id) || usedRacks.contains($0.id) }
        return result
    }
}
