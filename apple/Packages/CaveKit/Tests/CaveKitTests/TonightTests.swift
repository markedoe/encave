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

    @Test func startIndexChangesDailyAndStaysInRange() {
        #expect(Cellar.tonightStartIndex(dayOfYear: 1, count: 3) == 0)
        #expect(Cellar.tonightStartIndex(dayOfYear: 2, count: 3) == 1)
        #expect(Cellar.tonightStartIndex(dayOfYear: 4, count: 3) == 0)
        #expect(Cellar.tonightStartIndex(dayOfYear: 200, count: 0) == 0)
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
