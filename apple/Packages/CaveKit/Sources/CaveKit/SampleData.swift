import Foundation

/// A small fictional cellar for trying the app and for previews.
/// Producers are invented; appellations are real places.
public enum SampleData {
    /// Labels are passed in so the app can localize them.
    public struct Labels: Sendable {
        public var mainRack = "Main rack"
        public var wineFridge = "Wine fridge"
        public var bins = "Diamond bins"
        public var deepRack = "Double-depth rack"
        public var honeycomb = "Honeycomb rack"
        public var stairs = "Under the stairs"
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
        public init() {}
    }

    public static func cellar(
        year: Int = Calendar.current.component(.year, from: .now),
        labels: Labels = Labels()
    ) -> Cellar {
        var cellar = Cellar()
        let main = Rack(name: labels.mainRack, rows: 6, columns: 8)
        let small = Rack(name: labels.wineFridge, kind: .shelves, rows: 4, columns: 6, depth: 2)
        let bins = Rack(name: labels.bins, kind: .bins, rows: 2, columns: 3, binCapacity: 10)
        // One rack of every kind, so the sample shows them all.
        let deep = Rack(name: labels.deepRack, rows: 4, columns: 6, depth: 2)
        let honeycomb = Rack(name: labels.honeycomb, kind: .staggered, rows: 5, columns: 8)
        let stairs = Rack(name: labels.stairs, rows: 5, columns: 6, rowColumns: [2, 3, 4, 5, 6])
        for rack in [main, small, bins, deep, honeycomb, stairs] { cellar.addRack(rack) }

        let entries: [(Wine, Int, Rack?)] = [
            (Wine(name: "Clos des Tilleuls", producer: "Domaine Arnaud", vintage: year - 9, color: .red,
                  region: "Bordeaux", country: "France", appellation: "Pauillac", grapes: ["Cabernet Sauvignon", "Merlot"],
                  drinkFrom: year - 2, drinkUntil: year + 8), 3, main),
            (Wine(name: "Les Pierres Blanches", producer: "Maison Clément", vintage: year - 4, color: .white,
                  region: "Bourgogne", country: "France", appellation: "Meursault", grapes: ["Chardonnay"],
                  drinkFrom: year - 1, drinkUntil: year), 2, main),
            (Wine(name: "Cuvée du Lac", producer: "Cave des Coteaux", vintage: year - 2, color: .white,
                  region: "Vaud", country: "Switzerland", grapes: ["Chasselas"],
                  drinkFrom: year - 1, drinkUntil: year + 2), 4, small),
            (Wine(name: "Terrasses du Sud", producer: "Domaine Ferrand", vintage: year - 3, color: .red,
                  region: "Rhône", country: "France", appellation: "Gigondas", grapes: ["Grenache", "Syrah"],
                  drinkFrom: year + 1, drinkUntil: year + 10), 6, bins),
            (Wine(name: "Rosé de Provence", producer: "Château Lumière", vintage: year - 5, color: .rose,
                  region: "Provence", country: "France", grapes: ["Grenache", "Cinsault"],
                  drinkFrom: year - 4, drinkUntil: year - 2), 1, nil),
            (Wine(name: "Brut Réserve", producer: "Maison Aubry", color: .sparkling,
                  region: "Champagne", country: "France", grapes: ["Chardonnay", "Pinot Noir"]), 2, small),
            (Wine(name: "Vendanges Tardives", producer: "Domaine Weber", vintage: year - 12, color: .dessert,
                  region: "Alsace", country: "France", grapes: ["Gewurztraminer"],
                  drinkFrom: year - 6, drinkUntil: year + 15), 1, main),
            (Wine(name: "Riserva Vigna Alta", producer: "Cascina Ferri", vintage: year - 10, color: .red,
                  region: "Piedmont", country: "Italy", appellation: "Barolo", grapes: ["Nebbiolo"],
                  drinkFrom: year - 2, drinkUntil: year + 12), 8, deep),
            (Wine(name: "Savagnin Ouillé", producer: "Domaine des Granges", vintage: year - 5, color: .white,
                  region: "Jura", country: "France", appellation: "Arbois", grapes: ["Savagnin"],
                  drinkFrom: year - 2, drinkUntil: year + 6), 5, honeycomb),
            (Wine(name: "Qvevri Amber", producer: "Marani Tsiskari", vintage: year - 3, color: .orange,
                  region: "Kakheti", country: "Georgia", grapes: ["Rkatsiteli"],
                  drinkFrom: year - 1, drinkUntil: year + 4), 3, honeycomb),
            (Wine(name: "Vintage Port", producer: "Quinta da Serra", vintage: year - 15, color: .fortified,
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
            _ = try? cellar.addBottles(of: wine, quantity: quantity, slots: slots, purchase: purchase,
                                       addedAt: .now - Double(30 * (index + 1)) * day)
        }

        // Last year's vintage bought en primeur, not delivered yet.
        let primeur = Wine(name: "Clos des Tilleuls", producer: "Domaine Arnaud", vintage: year - 1, color: .red,
                           region: "Bordeaux", country: "France", appellation: "Pauillac",
                           grapes: ["Cabernet Sauvignon", "Merlot"], drinkFrom: year + 6, drinkUntil: year + 20)
        _ = try? cellar.addBottles(of: primeur, quantity: 6, purchase: PurchaseInfo(
            date: .now - 60 * day, price: 41, currency: cellar.settings.currency, place: labels.merchant,
            isOrdered: true, expectedAt: .now + 75 * day))

        // A few past tastings for the journal.
        let tasted = Wine(name: "Vieilles Vignes", producer: "Domaine Arnaud", vintage: year - 7, color: .red,
                          region: "Bourgogne", country: "France", appellation: "Gevrey-Chambertin", grapes: ["Pinot Noir"],
                          drinkFrom: year - 3, drinkUntil: year + 4)
        if let bottles = try? cellar.addBottles(of: tasted, quantity: 2) {
            try? cellar.drink(bottles[0].id, tasting: Tasting(date: .now - 12 * day, rating: 5,
                note: labels.tastingNote, occasion: labels.occasion, nose: labels.nose,
                palate: labels.palate, foodPairing: labels.pairing))
            try? cellar.drink(bottles[1].id, tasting: Tasting(date: .now - 40 * day, rating: 4))
        }

        // Wines found elsewhere, one of them on the shopping list.
        let atFriends = Wine(name: "Coteaux du Layon", producer: "Domaine des Sablons", vintage: year - 6,
                             color: .dessert, region: "Loire", country: "France", grapes: ["Chenin Blanc"])
        cellar.addDiscovery(Discovery(wineID: atFriends.id, date: .now - 9 * day, setting: .friends,
                                      place: labels.friendsPlace, rating: 4, note: labels.friendsNote,
                                      tags: [labels.tagGift], wantsToBuy: true), wine: atFriends)
        let onTrip = Wine(name: "Alvarinho Reserva", producer: "Quinta do Mar", vintage: year - 2, color: .white,
                          region: "Vinho Verde", country: "Portugal", grapes: ["Alvarinho"])
        cellar.addDiscovery(Discovery(wineID: onTrip.id, date: .now - 25 * day, setting: .restaurant,
                                      place: labels.restaurantPlace, rating: 5, note: labels.restaurantNote,
                                      tags: [labels.tagToBuy], price: 32, currency: cellar.settings.currency,
                                      wantsToBuy: true), wine: onTrip)
        return cellar
    }
}
