import Foundation
import Testing
@testable import CaveKit

@Suite struct CellarOverviewTests {
    @Test func matchesWhatTheCellarComputes() {
        let cellar = SampleData.cellar(year: 2026)
        let now = Date(timeIntervalSince1970: 1_780_000_000)
        let overview = CellarOverview(cellar, year: 2026, now: now)
        #expect(overview.year == 2026)
        #expect(overview.statistics == cellar.statistics(year: 2026, now: now))
        #expect(overview.grapes == cellar.wineGrapes)
        #expect(overview.reservations == cellar.reservations)
        #expect(overview.tags == cellar.wineTags)
        #expect(!overview.grapes.isEmpty)
    }

    @Test func dependsOnTheYear() {
        let cellar = SampleData.cellar(year: 2026)
        let now = Date(timeIntervalSince1970: 1_780_000_000)
        let later = CellarOverview(cellar, year: 2040, now: now)
        #expect(later.statistics.byMaturity == cellar.statistics(year: 2040, now: now).byMaturity)
        #expect(later != CellarOverview(cellar, year: 2026, now: now))
    }
}
