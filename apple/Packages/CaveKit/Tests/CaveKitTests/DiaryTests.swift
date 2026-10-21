import Foundation
import Testing
@testable import CaveKit

@Suite struct DiaryTests {
    private let day: TimeInterval = 86_400
    private let start = Date(timeIntervalSinceReferenceDate: 800_000_000)

    /// Two opened bottles (days 1 and 4), two wines tasted elsewhere (days 2 and 3, one to buy).
    private func cellar() throws -> Cellar {
        var cellar = Cellar()
        let red = Wine(name: "Clos Rouge", producer: "Domaine A", vintage: 2015, color: .red)
        let bottles = try cellar.addBottles(of: red, quantity: 3)
        try cellar.drink(bottles[0].id, tasting: Tasting(date: start + day, rating: 4, note: "Cherry and smoke"))
        try cellar.drink(bottles[1].id, tasting: Tasting(date: start + 4 * day, occasion: "Birthday"))
        let white = Wine(name: "Alvarinho", vintage: 2023, color: .white)
        cellar.addDiscovery(Discovery(wineID: white.id, date: start + 2 * day, setting: .restaurant,
                                      place: "Lisbon", tags: ["Summer"], wantsToBuy: true), wine: white)
        let rose = Wine(name: "Tavel", color: .rose)
        cellar.addDiscovery(Discovery(wineID: rose.id, date: start + 3 * day, setting: .friends, note: "With paella"),
                            wine: rose)
        return cellar
    }

    private func names(_ entries: [DiaryEntry], in cellar: Cellar) -> [String] {
        entries.map { cellar.wines[$0.wineID]?.name ?? "?" }
    }

    @Test func mixesBothKindsNewestFirst() throws {
        let cellar = try cellar()
        let entries = cellar.diary()
        #expect(names(entries, in: cellar) == ["Clos Rouge", "Tavel", "Alvarinho", "Clos Rouge"])
        #expect(entries.map(\.date) == entries.map(\.date).sorted(by: >))
        // The bottle still in the cellar is not in the diary.
        #expect(entries.count == 4)
    }

    @Test func eachFilterShowsItsKind() throws {
        let cellar = try cellar()
        #expect(cellar.diary(filter: .cellar).allSatisfy { if case .tasting = $0 { true } else { false } })
        #expect(cellar.diary(filter: .cellar).count == 2)
        #expect(names(cellar.diary(filter: .elsewhere), in: cellar) == ["Tavel", "Alvarinho"])
        #expect(names(cellar.diary(filter: .toBuy), in: cellar) == ["Alvarinho"])
    }

    @Test func searchLooksInTastingsAndPlaces() throws {
        let cellar = try cellar()
        #expect(names(cellar.diary(query: "smoke"), in: cellar) == ["Clos Rouge"])
        #expect(names(cellar.diary(query: "birthday"), in: cellar) == ["Clos Rouge"])
        #expect(names(cellar.diary(query: "lisbon"), in: cellar) == ["Alvarinho"])
        #expect(names(cellar.diary(query: "paëlla"), in: cellar) == ["Tavel"])
        #expect(names(cellar.diary(query: "domaine a"), in: cellar) == ["Clos Rouge", "Clos Rouge"])
        #expect(cellar.diary(filter: .cellar, query: "lisbon").isEmpty)
    }

    @Test func aTagKeepsOnlyWinesTastedElsewhereWithIt() throws {
        let cellar = try cellar()
        #expect(names(cellar.diary(tag: "summer"), in: cellar) == ["Alvarinho"])
        #expect(cellar.diary(filter: .cellar, tag: "summer").isEmpty)
    }

    @Test func sameDateIsOrderedByID() throws {
        var cellar = Cellar()
        let wine = Wine(name: "Gamay")
        let bottle = try #require(try cellar.addBottles(of: wine, quantity: 1).first)
        try cellar.drink(bottle.id, tasting: Tasting(date: start))
        let discovery = cellar.addDiscovery(Discovery(wineID: wine.id, date: start), wine: wine)
        let expected = [bottle.id, discovery.id].sorted { $0.uuidString < $1.uuidString }
        #expect(cellar.diary().map(\.id) == expected)
    }

    @Test func anOpenedBottleWithoutTastingUsesTheDayItWasAdded() throws {
        var cellar = Cellar()
        let wine = Wine(name: "Gamay")
        let bottle = Bottle(wineID: wine.id, status: .consumed, addedAt: start)
        cellar.wines[wine.id] = wine
        cellar.bottles[bottle.id] = bottle
        #expect(cellar.diary().map(\.date) == [start])
    }
}
