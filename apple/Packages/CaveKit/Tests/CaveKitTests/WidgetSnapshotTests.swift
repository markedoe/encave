import Foundation
import Testing
@testable import CaveKit

@Suite struct WidgetSnapshotTests {
    /// Noon on a day of 2026 in the user's time zone.
    static func noon(_ month: Int, _ day: Int, year: Int = 2026) -> Date {
        CellarYear.calendar.date(from: DateComponents(year: year, month: month, day: day, hour: 12))!
    }

    @Test func emptyCellarIsEmpty() throws {
        let snapshot = WidgetSnapshot(Cellar(), now: Self.noon(3, 1))
        #expect(snapshot.isEmpty)
        #expect(snapshot.year == 2026)
        #expect(snapshot.bottles == 0)
        #expect(snapshot.suggestions.isEmpty)
        #expect(snapshot.drinkSoonPicks.isEmpty)
        #expect(snapshot.suggestion(on: Self.noon(3, 1)) == nil)
    }

    @Test func countsMatchTheCellarScreen() throws {
        var cellar = Cellar()
        try cellar.addBottles(of: Wine(name: "Soon", drinkFrom: 2020, drinkUntil: 2026), quantity: 2)
        try cellar.addBottles(of: Wine(name: "Ready", drinkFrom: 2020, drinkUntil: 2030), quantity: 3)
        try cellar.addBottles(of: Wine(name: "Young", drinkFrom: 2030, drinkUntil: 2040), quantity: 4)
        try cellar.addBottles(of: Wine(name: "No window"))
        let snapshot = WidgetSnapshot(cellar, now: Self.noon(3, 1))
        let counts = cellar.counts(year: 2026)
        #expect(snapshot.bottles == counts.bottles)
        #expect(snapshot.bottles == 10)
        #expect(snapshot.wines == 4)
        #expect(snapshot.toKeep == counts.bottlesToKeep)
        #expect(snapshot.toKeep == 4)
        #expect(snapshot.drinkSoon == counts.bottlesToDrinkSoon)
        #expect(snapshot.drinkSoon == 2)
        #expect(snapshot.ready == 3)
        #expect(!snapshot.isEmpty)
    }

    @Test func suggestionsAreTonightsCardForEachDay() throws {
        var cellar = Cellar()
        for index in 0..<6 {
            try cellar.addBottles(of: Wine(name: "Ready \(index)", drinkFrom: 2020, drinkUntil: 2030))
        }
        try cellar.addBottles(of: Wine(name: "Young", drinkFrom: 2030, drinkUntil: 2040))
        let now = Self.noon(3, 1)
        let snapshot = WidgetSnapshot(cellar, now: now)
        #expect(snapshot.suggestions.count == WidgetSnapshot.suggestionDays)
        for offset in 0..<WidgetSnapshot.suggestionDays {
            let date = CellarYear.calendar.date(byAdding: .day, value: offset, to: now)!
            let day = Cellar.tonightDay(date)
            #expect(snapshot.suggestions[offset].day == day)
            #expect(snapshot.suggestion(on: date)?.wineID == cellar.tonightSuggestion(year: 2026, day: day)?.wine.id)
        }
    }

    @Test func keptWineStaysTodaysSuggestionOnly() throws {
        var cellar = Cellar()
        try cellar.addBottles(of: Wine(name: "A", drinkFrom: 2020, drinkUntil: 2030))
        try cellar.addBottles(of: Wine(name: "B", drinkFrom: 2020, drinkUntil: 2030))
        let now = Self.noon(3, 1)
        let day = Cellar.tonightDay(now)
        let first = try #require(cellar.tonightSuggestion(year: 2026, day: day))
        let other = try #require(cellar.groups().first { $0.wine.id != first.wine.id })
        let snapshot = WidgetSnapshot(cellar, now: now, keeping: other.wine.id)
        #expect(snapshot.suggestion(on: now)?.wineID == other.wine.id)
        let tomorrow = CellarYear.calendar.date(byAdding: .day, value: 1, to: now)!
        #expect(snapshot.suggestion(on: tomorrow)?.wineID
            == cellar.tonightSuggestion(year: 2026, day: Cellar.tonightDay(tomorrow))?.wine.id)
    }

    @Test func pickHasNameProducerVintagePlaceAndFreeBottles() throws {
        var cellar = Cellar()
        let rack = Rack(name: "Main rack", rows: 2, columns: 2)
        cellar.addRack(rack)
        let wine = Wine(name: "Chablis", producer: "Domaine X", vintage: 2020, color: .white, drinkFrom: 2020, drinkUntil: 2026)
        let bottles = try cellar.addBottles(of: wine, quantity: 3, slots: [Slot(rackID: rack.id, row: 1, column: 1)])
        try cellar.reserve([bottles[2].id], for: "Birthday")
        let snapshot = WidgetSnapshot(cellar, now: Self.noon(3, 1), place: { cellar.locationLabel(for: $0) })
        let pick = try #require(snapshot.suggestion(on: Self.noon(3, 1)))
        #expect(pick.name == "Chablis")
        #expect(pick.displayName == "Chablis 2020")
        #expect(pick.producer == "Domaine X")
        #expect(pick.vintage == 2020)
        #expect(pick.color == .white)
        #expect(pick.maturity == .drinkSoon)
        // The bottle kept for an occasion is never suggested.
        #expect(pick.bottles == 2)
        #expect(pick.place?.hasPrefix("Main rack · ") == true)
        #expect(snapshot.drinkSoonPicks.map(\.wineID) == [wine.id])
    }

    @Test func drinkSoonListsTheFirstThreeByEndOfWindow() throws {
        var cellar = Cellar()
        try cellar.addBottles(of: Wine(name: "Ready", drinkFrom: 2020, drinkUntil: 2030))
        for name in ["D", "C", "B", "A"] {
            try cellar.addBottles(of: Wine(name: name, drinkFrom: 2020, drinkUntil: 2026))
        }
        try cellar.addBottles(of: Wine(name: "Two", drinkFrom: 2020, drinkUntil: 2026), quantity: 2)
        let snapshot = WidgetSnapshot(cellar, now: Self.noon(3, 1))
        // More bottles first, then the name, as Tonight orders them.
        #expect(snapshot.drinkSoonPicks.map(\.name) == ["Two", "A", "B"])
        #expect(snapshot.drinkSoon == 6)
    }

    @Test func pastTheLastDayKeepsTheLastSuggestion() throws {
        var cellar = Cellar()
        try cellar.addBottles(of: Wine(name: "A", drinkFrom: 2020, drinkUntil: 2030))
        let snapshot = WidgetSnapshot(cellar, now: Self.noon(3, 1))
        #expect(snapshot.suggestion(on: Self.noon(5, 1))?.name == "A")
        #expect(snapshot.suggestion(on: Self.noon(2, 1)) == nil)
    }

    @Test func refreshDatesAreEachMidnightAfterNow() throws {
        var cellar = Cellar()
        try cellar.addBottles(of: Wine(name: "A", drinkFrom: 2020, drinkUntil: 2030))
        let now = Self.noon(3, 1)
        let snapshot = WidgetSnapshot(cellar, now: now)
        let dates = snapshot.refreshDates(from: now)
        #expect(dates.first == now)
        #expect(dates.count == WidgetSnapshot.suggestionDays)
        let calendar = CellarYear.calendar
        #expect(dates[1] == calendar.date(from: DateComponents(year: 2026, month: 3, day: 2)))
        #expect(dates.dropFirst().allSatisfy { calendar.startOfDay(for: $0) == $0 })
    }

    @Test func encodingIsStableAndRoundTrips() throws {
        var cellar = Cellar()
        try cellar.addBottles(of: Wine(name: "A", vintage: 2019, drinkFrom: 2020, drinkUntil: 2026))
        let now = Self.noon(3, 1)
        let snapshot = WidgetSnapshot(cellar, now: now)
        let data = try snapshot.encoded()
        #expect(try WidgetSnapshot(cellar, now: now).encoded() == data)
        #expect(WidgetSnapshot.decode(data) == snapshot)
    }

    @Test func unreadableOrNewerSnapshotIsIgnored() throws {
        #expect(WidgetSnapshot.decode(nil) == nil)
        #expect(WidgetSnapshot.decode(Data("not json".utf8)) == nil)
        var newer = WidgetSnapshot(year: 2026)
        newer.version = WidgetSnapshot.currentVersion + 1
        #expect(WidgetSnapshot.decode(try newer.encoded()) == nil)
    }

    @Test func linksRoundTrip() throws {
        let id = UUID()
        for link in [WidgetLink.wine(id), .tonight, .cellar, .addBottle] {
            #expect(WidgetLink(url: link.url) == link)
        }
        #expect(WidgetLink.wine(id).url.absoluteString == "encave://wine/\(id.uuidString.lowercased())")
        #expect(WidgetLink(url: URL(string: "encave://wine/not-an-id")!) == nil)
        #expect(WidgetLink(url: URL(string: "encave://pair?code=123456")!) == nil)
        #expect(WidgetLink(url: URL(string: "https://tonight")!) == nil)
    }
}
