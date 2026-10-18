import Foundation

/// Which bottles a cellar report covers.
public enum CellarReportScope: String, CaseIterable, Sendable {
    /// Every bottle in the cellar (bottles on order are not in the cellar yet).
    case all
    /// Ready or to drink soon and not kept for an occasion: what Tonight suggests.
    case toOpen
    case drinkSoon, ready, young, pastPeak
    /// Bought, not delivered yet.
    case onOrder
    /// Kept for an occasion.
    case reserved
}

/// A plain description of the cellar for another app on the same device, such as
/// a local AI assistant reached through Shortcuts. It is built on the device and
/// handed only to the shortcut that asked for it; Encave sends nothing.
///
/// The text form is meant to be read by a language model: one line per wine,
/// fields separated by " | ", stable English words for the maturity. The JSON
/// form has the same fields with fixed keys.
public struct CellarReport: Sendable, Equatable {
    public struct Row: Codable, Equatable, Sendable {
        public var wineID: UUID
        public var name: String
        public var vintage: Int?
        public var producer: String?
        /// red, white, rose, sparkling, dessert, fortified, orange.
        public var style: String?
        public var appellation: String?
        public var region: String?
        public var country: String?
        public var grapes: [String]
        public var drinkFrom: Int?
        public var drinkUntil: Int?
        /// drink soon, ready, too young, past its peak, window unknown.
        public var maturity: String
        public var bottles: Int
        /// Rack and position, or the storage note, of each placed bottle.
        public var places: [String]
        public var tags: [String]
        /// Occasions the bottles are kept for.
        public var keptFor: [String]
        /// Expected delivery days of bottles on order (yyyy-MM-dd).
        public var expected: [String]
    }

    public var year: Int
    public var scope: CellarReportScope
    public var rows: [Row]

    public var bottleCount: Int { rows.reduce(0) { $0 + $1.bottles } }

    /// Header, column names, then one numbered line per wine.
    public var text: String {
        let header = "Encave cellar, \(year), \(scope.summary): \(rows.count) wine\(rows.count == 1 ? "" : "s"), "
            + "\(bottleCount) bottle\(bottleCount == 1 ? "" : "s")."
        guard !rows.isEmpty else { return header }
        let columns = "Columns: wine | producer | style | origin | grapes | window | maturity | bottles | places | tags | kept for"
            + (scope == .onOrder ? " | expected" : "")
        let lines = rows.enumerated().map { index, row in
            let origin = [row.appellation, row.region, row.country].compactMap { $0 }.joined(separator: ", ")
            let window: String? = switch (row.drinkFrom, row.drinkUntil) {
            case let (from?, until?): "\(from)-\(until)"
            case let (from?, nil): "from \(from)"
            case let (nil, until?): "until \(until)"
            case (nil, nil): nil
            }
            var fields: [String] = [
                row.vintage.map { "\(row.name) \($0)" } ?? row.name,
                row.producer ?? "", row.style ?? "", origin, row.grapes.joined(separator: ", "),
                window ?? "", row.maturity, "\(row.bottles)", row.places.joined(separator: "; "),
                row.tags.joined(separator: ", "), row.keptFor.joined(separator: ", "),
            ]
            if scope == .onOrder { fields.append(row.expected.joined(separator: ", ")) }
            return "\(index + 1). " + fields.map(Self.clean).joined(separator: " | ")
        }
        return ([header, columns] + lines).joined(separator: "\n")
    }

    /// The rows as a JSON array, with sorted keys.
    public func json() throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        return String(decoding: try encoder.encode(rows), as: UTF8.self)
    }

    /// Keeps a field on one line and free of the separator.
    private static func clean(_ field: String) -> String {
        field.replacingOccurrences(of: "|", with: "/")
            .components(separatedBy: .newlines).joined(separator: " ")
    }
}

private extension CellarReportScope {
    var summary: String {
        switch self {
        case .all: "all bottles"
        case .toOpen: "bottles to open now"
        case .drinkSoon: "bottles to drink soon"
        case .ready: "bottles ready to drink"
        case .young: "bottles too young"
        case .pastPeak: "bottles past their peak"
        case .onOrder: "bottles on order"
        case .reserved: "bottles kept for an occasion"
        }
    }
}

public extension Cellar {
    /// The wines in `scope` whose name, producer, region, grape or tag contains
    /// `search` (all of them when it is empty), at most `limit`.
    func report(scope: CellarReportScope, search: String = "", year: Int, limit: Int = 500) -> CellarReport {
        let needle = search.trimmingCharacters(in: .whitespacesAndNewlines)
        func matching(_ groups: [WineGroup]) -> [WineGroup] {
            needle.isEmpty ? groups : groups.filter { $0.wine.matches(needle) }
        }
        let groups: [WineGroup] = switch scope {
        case .all:
            self.groups(filter: CellarFilter(query: needle), sort: .name, year: year)
        case .toOpen:
            matching(groupsByMaturity(year: year).filter { $0.maturity == .drinkSoon || $0.maturity == .ready }.flatMap(\.groups))
        case .drinkSoon:
            self.groups(filter: CellarFilter(query: needle, maturities: [.drinkSoon]), sort: .readiness, year: year)
        case .ready:
            self.groups(filter: CellarFilter(query: needle, maturities: [.ready]), sort: .readiness, year: year)
        case .young:
            self.groups(filter: CellarFilter(query: needle, maturities: [.young]), sort: .readiness, year: year)
        case .pastPeak:
            self.groups(filter: CellarFilter(query: needle, maturities: [.pastPeak]), sort: .readiness, year: year)
        case .onOrder:
            matching(orderGroups())
        case .reserved:
            self.groups(filter: CellarFilter(query: needle), sort: .name, year: year).compactMap { group in
                let kept = group.bottles.filter { $0.reservedFor != nil }
                return kept.isEmpty ? nil : WineGroup(wine: group.wine, bottles: kept)
            }
        }
        let day = DateFormatter()
        day.calendar = Calendar(identifier: .gregorian)
        day.locale = Locale(identifier: "en_US_POSIX")
        day.dateFormat = "yyyy-MM-dd"
        let rows = groups.prefix(max(0, limit)).map { group in
            let wine = group.wine
            let maturity: String = switch Maturity.of(wine, year: year) {
            case .drinkSoon: "drink soon"
            case .ready: "ready"
            case .young: "too young"
            case .pastPeak: "past its peak"
            case .unknown: "window unknown"
            }
            return CellarReport.Row(
                wineID: wine.id, name: wine.name, vintage: wine.vintage, producer: wine.producer,
                style: wine.color?.rawValue, appellation: wine.appellation, region: wine.region, country: wine.country,
                grapes: wine.grapes, drinkFrom: wine.drinkFrom, drinkUntil: wine.drinkUntil, maturity: maturity,
                bottles: group.count, places: group.bottles.compactMap { locationLabel(for: $0) },
                tags: wine.tags ?? [],
                keptFor: Array(Set(group.bottles.compactMap(\.reservedFor))).sorted(),
                expected: group.bottles.compactMap { $0.expectedAt.map(day.string(from:)) })
        }
        return CellarReport(year: year, scope: scope, rows: Array(rows))
    }
}
