import Foundation
import Testing
@testable import CaveKit

@Suite struct TonightTests {
    @Test func candidatesRankDrinkSoonThenReadyByEndOfWindowBottlesAndName() throws {
        var cellar = Cellar()
        try cellar.addBottles(of: Wine(name: "Ready late", drinkFrom: 2020, drinkUntil: 2035))
        try cellar.addBottles(of: Wine(name: "Ready early B", drinkFrom: 2020, drinkUntil: 2028))
        try cellar.addBottles(of: Wine(name: "Ready early A", drinkFrom: 2020, drinkUntil: 2028))
        try cellar.addBottles(of: Wine(name: "Ready early C", drinkFrom: 2020, drinkUntil: 2028), quantity: 3)
        try cellar.addBottles(of: Wine(name: "Soon", drinkFrom: 2020, drinkUntil: 2026))
        try cellar.addBottles(of: Wine(name: "Young", drinkFrom: 2030, drinkUntil: 2040))
        try cellar.addBottles(of: Wine(name: "Past", drinkFrom: 2010, drinkUntil: 2015))
        let kept = try cellar.addBottles(of: Wine(name: "Kept", drinkFrom: 2020, drinkUntil: 2026))
        try cellar.reserve(kept.map(\.id), for: "Birthday")
        #expect(cellar.tonightCandidates(year: 2026).map(\.wine.name)
            == ["Soon", "Ready early C", "Ready early A", "Ready early B", "Ready late"])
        #expect(cellar.reservedBottleCount == 1)
    }

    /// The vectors shared with the Android core, in `fixtures/tonight-vectors.json`.
    struct Vectors: Decodable {
        struct Rank: Decodable { var wineId: String; var day: String; var rank: String }
        struct Order: Decodable { var day: String; var wineIds: [String]; var order: [String] }
        struct PickWine: Decodable { var id: String; var from: Int?; var until: Int?; var free: Int?; var reserved: Int? }
        struct Pick: Decodable { var about: String; var day: String; var kept: String?; var wines: [PickWine]; var order: [String] }
        var ranks: [Rank]
        var orders: [Order]
        var picks: [Pick]
    }

    static func vectors() throws -> Vectors {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent() // CaveKitTests
            .deletingLastPathComponent().deletingLastPathComponent() // CaveKit
            .deletingLastPathComponent().deletingLastPathComponent() // apple
            .deletingLastPathComponent().appendingPathComponent("fixtures/tonight-vectors.json")
        return try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: file))
    }

    @Test func ranksAndOrdersMatchTheSharedVectors() throws {
        let vectors = try Self.vectors()
        #expect(!vectors.ranks.isEmpty && !vectors.orders.isEmpty)
        for vector in vectors.ranks {
            let id = try #require(UUID(uuidString: vector.wineId))
            #expect(String(Cellar.tonightRank(wineID: id, day: vector.day), radix: 16).leftPadded(16) == vector.rank)
        }
        for vector in vectors.orders {
            let sorted = vector.wineIds.sorted {
                Cellar.tonightRank(wineID: UUID(uuidString: $0)!, day: vector.day)
                    < Cellar.tonightRank(wineID: UUID(uuidString: $1)!, day: vector.day)
            }
            #expect(sorted == vector.order)
        }
    }

    /// Tonight's whole order in a cellar, from `picks` in the shared vectors (the Android core reads them too).
    @Test func picksMatchTheSharedVectors() throws {
        let vectors = try Self.vectors()
        #expect(!vectors.picks.isEmpty)
        for vector in vectors.picks {
            var cellar = Cellar()
            #expect(!vector.wines.isEmpty)
            for item in vector.wines {
                let id = try #require(UUID(uuidString: item.id))
                let wine = Wine(id: id, name: item.id, drinkFrom: item.from ?? 2020, drinkUntil: item.until)
                let reserved = item.reserved ?? 0
                let added = try cellar.addBottles(of: wine, quantity: (item.free ?? 1) + reserved)
                if reserved > 0 { try cellar.reserve(added.suffix(reserved).map(\.id), for: "Birthday") }
            }
            var kept: UUID?
            if let text = vector.kept { kept = try #require(UUID(uuidString: text)) }
            let picks = cellar.tonightPicks(year: 2026, day: vector.day, keeping: kept)
            #expect(picks.map { $0.wine.id.uuidString.lowercased() } == vector.order.map { $0.lowercased() }, "\(vector.about)")
        }
    }

    @Test func todaysPickStaysWhenOtherWinesComeAndGo() throws {
        var cellar = Cellar()
        for index in 1...6 {
            try cellar.addBottles(of: Wine(name: "Wine \(index)", drinkFrom: 2020, drinkUntil: 2030))
        }
        let day = "2026-10-22"
        let picks = cellar.tonightPicks(year: 2026, day: day)
        #expect(picks.count == 6)
        let first = try #require(picks.first)
        // Another wine drunk: the same first pick.
        let other = try #require(picks.last?.bottles.first)
        try cellar.drink(other.id, tasting: Tasting(rating: 7))
        #expect(cellar.tonightPicks(year: 2026, day: day).first?.wine.id == first.wine.id)
        // Wines added: the pick kept by the app stays first, whatever their rank.
        for index in 1...6 {
            try cellar.addBottles(of: Wine(name: "New \(index)", drinkFrom: 2020, drinkUntil: 2030))
        }
        #expect(cellar.tonightPicks(year: 2026, day: day, keeping: first.wine.id).first?.wine.id == first.wine.id)
        #expect(cellar.tonightPicks(year: 2026, day: day, keeping: first.wine.id).count == 11)
        // Kept but drunk: the best ranked candidate instead.
        try cellar.drink(try #require(first.bottles.first).id, tasting: Tasting(rating: 7))
        #expect(cellar.tonightPicks(year: 2026, day: day, keeping: first.wine.id).first?.wine.id
            == cellar.tonightPicks(year: 2026, day: day).first?.wine.id)
        // Another day, another order; the same set of wines.
        let tomorrow = cellar.tonightPicks(year: 2026, day: "2026-10-23")
        #expect(Set(tomorrow.map(\.wine.id)) == Set(cellar.tonightPicks(year: 2026, day: day).map(\.wine.id)))
    }

    @Test func picksPutDrinkSoonFirstThenReadyEachInTheDaysOrder() throws {
        var cellar = Cellar()
        for index in 1...4 {
            try cellar.addBottles(of: Wine(name: "Soon \(index)", drinkFrom: 2020, drinkUntil: 2026))
            try cellar.addBottles(of: Wine(name: "Ready \(index)", drinkFrom: 2020, drinkUntil: 2030))
        }
        for day in ["2026-03-01", "2026-03-02", "2026-03-03"] {
            let picks = cellar.tonightPicks(year: 2026, day: day)
            #expect(picks.prefix(4).allSatisfy { $0.wine.name.hasPrefix("Soon") })
            #expect(picks.suffix(4).allSatisfy { $0.wine.name.hasPrefix("Ready") })
            for half in [Array(picks.prefix(4)), Array(picks.suffix(4))] {
                let ranks = half.map { Cellar.tonightRank(wineID: $0.wine.id, day: day) }
                #expect(ranks == ranks.sorted())
            }
            // The pick kept by the app stays first, even a ready one.
            let ready = try #require(picks.last)
            #expect(cellar.tonightPicks(year: 2026, day: day, keeping: ready.wine.id).map(\.wine.id)
                == [ready.wine.id] + picks.dropLast().map(\.wine.id))
        }
    }

    @Test func picksEdgeCases() throws {
        var cellar = Cellar()
        let day = "2026-01-01"
        #expect(cellar.tonightPicks(year: 2026, day: day).isEmpty)
        // A kept id that is not a candidate (young, unknown) changes nothing.
        try cellar.addBottles(of: Wine(name: "Ready", drinkFrom: 2020, drinkUntil: 2030))
        let young = try cellar.addBottles(of: Wine(name: "Young", drinkFrom: 2030, drinkUntil: 2040))
        #expect(cellar.tonightPicks(year: 2026, day: day, keeping: young[0].wineID).map(\.wine.name) == ["Ready"])
        #expect(cellar.tonightPicks(year: 2026, day: day, keeping: UUID()).map(\.wine.name) == ["Ready"])
        // Kept for an occasion: never suggested.
        let kept = try cellar.addBottles(of: Wine(name: "Kept", drinkFrom: 2020, drinkUntil: 2030))
        try cellar.reserve(kept.map(\.id), for: "Birthday")
        #expect(cellar.tonightPicks(year: 2026, day: day, keeping: kept[0].wineID).map(\.wine.name) == ["Ready"])
    }

    @Test func rankIgnoresTheCaseOfTheID() throws {
        let id = try #require(UUID(uuidString: "F47AC10B-58CC-4372-A567-0E02B2C3D479"))
        #expect(Cellar.tonightRank(wineID: id, day: "2026-10-22") == Cellar.tonightRank(wineID: UUID(uuidString: "f47ac10b-58cc-4372-a567-0e02b2c3d479")!, day: "2026-10-22"))
        #expect(Cellar.tonightRank(wineID: id, day: "2026-10-22") != Cellar.tonightRank(wineID: id, day: "2026-10-23"))
    }

    @Test func dayIsTheGregorianDateInTheUserTimeZone() {
        var parts = DateComponents()
        parts.year = 2026; parts.month = 3; parts.day = 7; parts.hour = 23; parts.minute = 59
        let date = CellarYear.calendar.date(from: parts)!
        #expect(Cellar.tonightDay(date) == "2026-03-07")
        // Padded, and 29 February.
        parts.year = 2028; parts.month = 2; parts.day = 29; parts.hour = 0; parts.minute = 0
        #expect(Cellar.tonightDay(CellarYear.calendar.date(from: parts)!) == "2028-02-29")
    }

    @Test func suggestionIsTheDaysFirstPickAndNilWhenNoneIsReady() throws {
        var cellar = Cellar()
        #expect(cellar.tonightSuggestion(year: 2026, day: "2026-01-01") == nil)
        try cellar.addBottles(of: Wine(name: "Young", drinkFrom: 2030, drinkUntil: 2040))
        #expect(cellar.tonightSuggestion(year: 2026, day: "2026-01-01") == nil)
        try cellar.addBottles(of: Wine(name: "Soon", drinkFrom: 2020, drinkUntil: 2026))
        try cellar.addBottles(of: Wine(name: "Ready", drinkFrom: 2020, drinkUntil: 2030))
        for day in ["2026-01-01", "2026-01-02", "2026-01-03"] {
            let picks = cellar.tonightPicks(year: 2026, day: day)
            // The wine to drink soon comes before the ready one, whatever their rank that day.
            #expect(picks.map(\.wine.name) == ["Soon", "Ready"])
            #expect(cellar.tonightSuggestion(year: 2026, day: day)?.wine.name == "Soon")
            // The wine kept for the day stays the suggestion.
            let other = try #require(picks.last)
            #expect(cellar.tonightSuggestion(year: 2026, day: day, keeping: other.wine.id)?.wine.id == other.wine.id)
        }
    }

    @Test func nextToBeReadyIsTheYoungWineWhoseWindowOpensFirst() throws {
        var cellar = Cellar()
        try cellar.addBottles(of: Wine(name: "Later", drinkFrom: 2032, drinkUntil: 2040))
        try cellar.addBottles(of: Wine(name: "Sooner", drinkFrom: 2029, drinkUntil: 2040))
        #expect(cellar.tonightCandidates(year: 2026).isEmpty)
        #expect(cellar.nextToBeReady(year: 2026)?.wine.name == "Sooner")
    }

    @Test func drinkChoicesListEachPlaceOnceFreeFirst() throws {
        var cellar = Cellar()
        let wine = Wine(name: "Same place")
        let cellarBottles = try cellar.addBottles(of: wine, quantity: 3, purchase: PurchaseInfo(storageNote: "Cave"))
        try cellar.reserve([cellarBottles[0].id], for: "Wedding")
        let choices = cellar.bottles(of: wine.id).drinkChoices
        #expect(choices.count == 2)
        #expect(choices.first?.reservedFor == nil)
        #expect(choices.last?.reservedFor == "Wedding")
        #expect(cellar.bottles(of: wine.id).bottleToOpen?.reservedFor == nil)
    }

    @Test func occasionsMatchInAnyLanguageIgnoringCaseAndAccents() {
        #expect(Occasion.matching("dîner à la maison") == .dinnerAtHome)
        #expect(Occasion.matching(" DINER A LA MAISON ") == .dinnerAtHome)
        #expect(Occasion.matching("With Friends") == .withFriends)
        #expect(Occasion.matching("fete") == .celebration)
        #expect(Occasion.matching("Paul's 40th") == nil)
        #expect(Occasion.matching("") == nil)
    }

    @Test func yearInWineCountsAChipTheSameInEveryLanguage() throws {
        var cellar = Cellar()
        let bottles = try cellar.addBottles(of: Wine(name: "A"), quantity: 3)
        let date = CellarYear.calendar.date(from: DateComponents(year: 2026, month: 3, day: 1))!
        try cellar.drink(bottles[0].id, tasting: Tasting(date: date, occasion: "Entre amis"))
        try cellar.drink(bottles[1].id, tasting: Tasting(date: date, occasion: "with friends"))
        try cellar.drink(bottles[2].id, tasting: Tasting(date: date, occasion: "Celebration"))
        #expect(cellar.yearInWine(2026).topOccasion == "With friends")
    }
}

@Suite struct AssistantRelevanceTests {
    @Test func theWinesClosestToTheQuestionAreKeptFirst() throws {
        var cellar = Cellar()
        try cellar.addBottles(of: Wine(name: "Urgent", color: .red, region: "Rhône", drinkFrom: 2020, drinkUntil: 2026))
        try cellar.addBottles(of: Wine(name: "Clos", producer: "Domaine X", color: .red, region: "Bourgogne",
                                       grapes: ["Pinot Noir"], drinkFrom: 2030, drinkUntil: 2040))
        try cellar.addBottles(of: Wine(name: "Bulles", color: .sparkling, region: "Champagne", drinkFrom: 2024, drinkUntil: 2030))
        let burgundy = cellar.assistantCatalog(year: 2026, limit: 1, question: "Un vin de Bourgogne pour ce soir ?")
        #expect(burgundy.entries.first?.line.hasPrefix("1. Clos") == true)
        #expect(burgundy.total == 3)
        #expect(burgundy.omitted == 2)
        let pinot = cellar.assistantCatalog(year: 2026, limit: 1, question: "Something with pinot noir")
        #expect(pinot.entries.first?.line.hasPrefix("1. Clos") == true)
        // A dish brings the wines that suit it.
        let oysters = cellar.assistantCatalog(year: 2026, limit: 1, question: "Des huîtres ce soir")
        #expect(oysters.entries.first?.line.hasPrefix("1. Bulles") == true)
        // Without a word that tells wines apart, the most urgent first.
        #expect(cellar.assistantCatalog(year: 2026, limit: 1, question: "What should I open?").entries.first?.line.hasPrefix("1. Urgent") == true)
    }
}

private extension String {
    func leftPadded(_ length: Int) -> String { String(repeating: "0", count: max(0, length - count)) + self }
}
