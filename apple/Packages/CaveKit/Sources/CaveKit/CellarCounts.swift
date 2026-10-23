import Foundation

/// The numbers the app shows about the cellar, counted the same way on every screen:
/// bottles, never the internal wine records (a wine tasted elsewhere, or whose bottles
/// were all drunk, is still a record but holds nothing in the cellar).
public struct CellarCounts: Sendable, Equatable {
    /// Bottles in the cellar (not opened, given away or on order).
    public var bottles: Int
    /// Bottles in the cellar whose wine is still young: to keep.
    public var bottlesToKeep: Int
    /// Bottles in the cellar to drink soon (the last year of their window).
    public var bottlesToDrinkSoon: Int
    /// Wines with at least one bottle in the cellar, when a count of wines is really meant.
    public var winesWithBottles: Int

    public init(_ cellar: Cellar, year: Int) {
        let inCellar = cellar.bottlesInCellar
        bottles = inCellar.count
        winesWithBottles = Set(inCellar.map(\.wineID)).count
        var keep = 0, soon = 0
        for bottle in inCellar {
            guard let wine = cellar.wines[bottle.wineID] else { continue }
            switch Maturity.of(wine, year: year) {
            case .young: keep += 1
            case .drinkSoon: soon += 1
            case .ready, .pastPeak, .unknown: break
            }
        }
        bottlesToKeep = keep
        bottlesToDrinkSoon = soon
    }
}

public extension Cellar {
    func counts(year: Int) -> CellarCounts { CellarCounts(self, year: year) }

    /// Bottles in the cellar, without working out maturities (backups, previous versions).
    var bottlesInCellarCount: Int { bottles.values.reduce(0) { $0 + ($1.status == .inCellar ? 1 : 0) } }

    /// Wines with at least one bottle in the cellar.
    var winesWithBottlesCount: Int {
        Set(bottles.values.lazy.filter { $0.status == .inCellar }.map(\.wineID)).count
    }
}

public extension Array where Element == WineGroup {
    /// The bottles the groups show.
    var bottleCount: Int { reduce(0) { $0 + $1.count } }
}
