import Foundation

/// What the Cellar screen shows above its list (figures and filter chips), worked out once
/// for a version of the cellar and a year, so the app can keep it instead of recomputing it
/// each time a view is drawn.
public struct CellarOverview: Sendable, Equatable {
    public var year: Int
    public var statistics: CellarStatistics
    /// Bottles, bottles to keep and to drink soon, as the figures above the list show them.
    public var counts: CellarCounts
    /// Grapes, occasions and tags of the bottles in the cellar, as the filter chips offer them.
    public var grapes: [String]
    public var reservations: [String]
    public var tags: [String]

    public init(_ cellar: Cellar, year: Int, now: Date = .now) {
        self.year = year
        statistics = cellar.statistics(year: year, now: now)
        counts = cellar.counts(year: year)
        grapes = cellar.wineGrapes
        reservations = cellar.reservations
        tags = cellar.wineTags
    }
}
