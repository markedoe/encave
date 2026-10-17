import Foundation

/// A small fictional cellar for trying the app and for previews.
/// Producers are invented; appellations are real places.
public enum SampleData {
    /// Labels are passed in so the app can localize them.
    public struct Labels: Sendable {
        public var mainRack = "Main rack"
        public var wineFridge = "Wine fridge"
        public var tastingNote = "Silky, red fruit, long finish."
        public var kitchen = "Kitchen"
        public var occasion = "Dinner with friends"
        public var nose = "Cherry, violet, a touch of undergrowth"
        public var palate = "Fine tannins, fresh acidity"
        public var pairing = "Roast duck"
        public init() {}
    }

    public static func cellar(
        year: Int = Calendar.current.component(.year, from: .now),
        labels: Labels = Labels()
    ) -> Cellar {
        var cellar = Cellar()
        let main = Rack(name: labels.mainRack, rows: 6, columns: 8)
        let small = Rack(name: labels.wineFridge, rows: 4, columns: 6, layout: .diamond)
        cellar.addRack(main)
        cellar.addRack(small)

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
                  drinkFrom: year + 1, drinkUntil: year + 10), 6, main),
            (Wine(name: "Rosé de Provence", producer: "Château Lumière", vintage: year - 5, color: .rose,
                  region: "Provence", country: "France", grapes: ["Grenache", "Cinsault"],
                  drinkFrom: year - 4, drinkUntil: year - 2), 1, nil),
            (Wine(name: "Brut Réserve", producer: "Maison Aubry", color: .sparkling,
                  region: "Champagne", country: "France", grapes: ["Chardonnay", "Pinot Noir"]), 2, small),
            (Wine(name: "Vendanges Tardives", producer: "Domaine Weber", vintage: year - 12, color: .dessert,
                  region: "Alsace", country: "France", grapes: ["Gewurztraminer"],
                  drinkFrom: year - 6, drinkUntil: year + 15), 1, main),
        ]

        let day: TimeInterval = 86_400
        let prices: [Decimal] = [38, 54, 16, 24, 14, 36, 29]
        for (index, (wine, quantity, rack)) in entries.enumerated() {
            let slots = rack.map { Array(cellar.freeSlots(in: $0).prefix(quantity)) } ?? []
            let purchase = PurchaseInfo(
                date: .now - Double(30 * (index + 1)) * day, price: prices[index % prices.count],
                currency: cellar.settings.currency, storageNote: rack == nil ? labels.kitchen : nil
            )
            _ = try? cellar.addBottles(of: wine, quantity: quantity, slots: slots, purchase: purchase,
                                       addedAt: .now - Double(30 * (index + 1)) * day)
        }

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
        return cellar
    }
}
