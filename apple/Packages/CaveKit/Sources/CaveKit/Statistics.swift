import Foundation

/// Figures for the Insights screen.
public struct CellarStatistics: Sendable, Equatable {
    public struct Slice: Sendable, Equatable, Identifiable {
        public var label: String
        public var count: Int
        public var id: String { label }
    }

    public struct ColorSlice: Sendable, Equatable, Identifiable {
        public var color: WineColor?
        public var count: Int
        public var id: String { color?.rawValue ?? "none" }
    }

    public struct MonthCount: Sendable, Equatable, Identifiable {
        public var month: Date
        public var count: Int
        public var id: Date { month }
    }

    public var bottleCount: Int
    public var wineCount: Int
    public var consumedCount: Int
    public var averageRating: Double?
    /// Sum of purchase prices of bottles in the cellar, per currency.
    public var valueByCurrency: [String: Decimal]
    public var byColor: [ColorSlice]
    public var byCountry: [Slice]
    public var byRegion: [Slice]
    /// Decades of vintage ("2010s"), oldest first; non-vintage last.
    public var byVintageDecade: [Slice]
    public var byMaturity: [Maturity: Int]
    /// Bottles opened per month over the last 12 months, oldest first.
    public var consumptionByMonth: [MonthCount]
    public var topGrapes: [Slice]
    /// Sum of the current prices of bottles in the cellar, per currency, for the wines
    /// that have one; `pricedBottleCount` says how many bottles that covers.
    public var estimatedValueByCurrency: [String: Decimal] = [:]
    public var pricedBottleCount = 0
}

public extension Cellar {
    func statistics(year: Int, now: Date = .now, calendar: Calendar = .current) -> CellarStatistics {
        let inCellar = bottlesInCellar
        let winesInCellar = inCellar.compactMap { wines[$0.wineID] }

        func slices(_ values: [String?], limit: Int? = nil) -> [CellarStatistics.Slice] {
            let counts = Dictionary(grouping: values.compactMap { $0 }.filter { !$0.isEmpty }, by: { $0 }).mapValues(\.count)
            let sorted = counts.map { CellarStatistics.Slice(label: $0.key, count: $0.value) }
                .sorted { $0.count != $1.count ? $0.count > $1.count : $0.label < $1.label }
            return limit.map { Array(sorted.prefix($0)) } ?? sorted
        }

        var value: [String: Decimal] = [:]
        for bottle in inCellar {
            guard let price = bottle.purchasePrice else { continue }
            value[bottle.currency ?? settings.currency, default: 0] += price
        }
        var estimated: [String: Decimal] = [:]
        var priced = 0
        for bottle in inCellar {
            guard let wine = wines[bottle.wineID], let price = wine.currentPrice else { continue }
            estimated[wine.currentPriceCurrency ?? settings.currency, default: 0] += price
            priced += 1
        }

        let colorCounts = Dictionary(grouping: winesInCellar, by: \.color).mapValues(\.count)
        let byColor = colorCounts.map { CellarStatistics.ColorSlice(color: $0.key, count: $0.value) }
            .sorted { $0.count > $1.count }

        let decades = winesInCellar.map { wine -> String in
            guard let vintage = wine.vintage else { return "NV" }
            return "\(vintage / 10 * 10)s"
        }
        let byDecade = Dictionary(grouping: decades, by: { $0 }).mapValues(\.count)
            .map { CellarStatistics.Slice(label: $0.key, count: $0.value) }
            .sorted { a, b in
                if a.label == "NV" { return false }
                if b.label == "NV" { return true }
                return a.label < b.label
            }

        var byMaturity: [Maturity: Int] = [:]
        for wine in winesInCellar { byMaturity[Maturity.of(wine, year: year), default: 0] += 1 }

        let consumed = consumedBottles
        let ratings = consumed.compactMap { $0.tasting?.rating }
        let startOfMonth = calendar.date(from: calendar.dateComponents([.year, .month], from: now)) ?? now
        let months = (0..<12).reversed().compactMap { calendar.date(byAdding: .month, value: -$0, to: startOfMonth) }
        let monthly = months.map { month -> CellarStatistics.MonthCount in
            let next = calendar.date(byAdding: .month, value: 1, to: month) ?? month
            let count = consumed.filter { ($0.tasting?.date).map { $0 >= month && $0 < next } ?? false }.count
            return CellarStatistics.MonthCount(month: month, count: count)
        }

        return CellarStatistics(
            bottleCount: inCellar.count,
            wineCount: Set(inCellar.map(\.wineID)).count,
            consumedCount: consumed.count,
            averageRating: ratings.isEmpty ? nil : Double(ratings.reduce(0, +)) / Double(ratings.count),
            valueByCurrency: value,
            byColor: byColor,
            byCountry: slices(winesInCellar.map(\.country)),
            byRegion: slices(winesInCellar.map(\.region), limit: 8),
            byVintageDecade: byDecade,
            byMaturity: byMaturity,
            consumptionByMonth: monthly,
            topGrapes: slices(winesInCellar.flatMap(\.grapes), limit: 8),
            estimatedValueByCurrency: estimated,
            pricedBottleCount: priced
        )
    }
}

/// "Your year in wine": what was opened in a year, from the journal.
public struct YearInWine: Sendable, Equatable {
    public var year: Int
    public var bottlesOpened: Int
    public var differentWines: Int
    /// Best rated bottle of the year (highest rating, then the latest).
    public var favorite: (name: String, rating: Int)?
    public var topRegion: String?
    public var topColor: WineColor?
    public var topOccasion: String?
    /// Bottles discovered elsewhere that year.
    public var discoveries: Int
    /// Month with the most bottles opened (1–12).
    public var busiestMonth: Int?

    public var isEmpty: Bool { bottlesOpened == 0 && discoveries == 0 }

    public static func == (a: YearInWine, b: YearInWine) -> Bool {
        a.year == b.year && a.bottlesOpened == b.bottlesOpened && a.differentWines == b.differentWines
            && a.favorite?.name == b.favorite?.name && a.favorite?.rating == b.favorite?.rating
            && a.topRegion == b.topRegion && a.topColor == b.topColor && a.topOccasion == b.topOccasion
            && a.discoveries == b.discoveries && a.busiestMonth == b.busiestMonth
    }
}

public extension Cellar {
    func yearInWine(_ year: Int, calendar: Calendar = .current) -> YearInWine {
        let opened = consumedBottles.filter { bottle in
            guard let date = bottle.tasting?.date else { return false }
            return calendar.component(.year, from: date) == year
        }
        let openedWines = opened.compactMap { wines[$0.wineID] }

        func top<T: Hashable>(_ values: [T]) -> T? {
            Dictionary(grouping: values, by: { $0 }).max { a, b in
                a.value.count != b.value.count ? a.value.count < b.value.count : "\(a.key)" > "\(b.key)"
            }?.key
        }

        let favorite = opened
            .compactMap { bottle -> (Bottle, Int)? in bottle.tasting?.rating.map { (bottle, $0) } }
            .max { a, b in
                a.1 != b.1 ? a.1 < b.1 : (a.0.tasting?.date ?? .distantPast) < (b.0.tasting?.date ?? .distantPast)
            }
            .flatMap { pair in wines[pair.0.wineID].map { (name: $0.displayName, rating: pair.1) } }
        let months = opened.compactMap { $0.tasting?.date }.map { calendar.component(.month, from: $0) }
        let found = discoveries.values.filter { calendar.component(.year, from: $0.date) == year }

        return YearInWine(
            year: year,
            bottlesOpened: opened.count,
            differentWines: Set(opened.map(\.wineID)).count,
            favorite: favorite,
            topRegion: top(openedWines.compactMap { $0.region ?? $0.country }),
            topColor: top(openedWines.compactMap(\.color)),
            topOccasion: top(opened.compactMap { $0.tasting?.occasion }.filter { !$0.isEmpty }),
            discoveries: found.count,
            busiestMonth: top(months)
        )
    }
}
