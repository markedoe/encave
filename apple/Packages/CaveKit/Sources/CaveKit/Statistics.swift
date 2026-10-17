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
            topGrapes: slices(winesInCellar.flatMap(\.grapes), limit: 8)
        )
    }
}
