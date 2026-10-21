import Foundation

/// A detail that several wines can share, to list the wines of the cellar that have it
/// (tapping the producer, a grape or the vintage on a wine's page).
public enum WineAttribute: Hashable, Sendable {
    case producer(String)
    case region(String)
    case appellation(String)
    case country(String)
    /// One grape of the wine's grapes.
    case grape(String)
    case vintage(Int)
    case color(WineColor)
    case tag(String)
}

public extension Wine {
    /// Whether the wine has this detail. Text ignores case and surrounding spaces;
    /// a blank value matches nothing.
    func has(_ attribute: WineAttribute) -> Bool {
        switch attribute {
        case .producer(let value): WineAttribute.same(producer, value)
        case .region(let value): WineAttribute.same(region, value)
        case .appellation(let value): WineAttribute.same(appellation, value)
        case .country(let value): WineAttribute.same(country, value)
        case .grape(let value): grapes.contains { WineAttribute.same($0, value) }
        case .tag(let value): (tags ?? []).contains { WineAttribute.same($0, value) }
        case .vintage(let year): vintage == year
        case .color(let style): color == style
        }
    }
}

extension WineAttribute {
    static func same(_ a: String?, _ b: String) -> Bool {
        guard let a else { return false }
        let left = a.trimmingCharacters(in: .whitespacesAndNewlines)
        let right = b.trimmingCharacters(in: .whitespacesAndNewlines)
        return !right.isEmpty && left.caseInsensitiveCompare(right) == .orderedSame
    }
}

public extension Cellar {
    /// The wines that share a detail, by name: those with bottles in the cellar, and
    /// those with bottles on order only. Each group holds the wine's bottles in the
    /// cellar, or its bottles on order when it has none in the cellar yet, so a list
    /// row shows either where they are or when they arrive.
    func wines(sharing attribute: WineAttribute) -> [WineGroup] {
        var inCellar: [UUID: [Bottle]] = [:]
        var onOrder: [UUID: [Bottle]] = [:]
        for bottle in bottles.values {
            switch bottle.status {
            case .inCellar: inCellar[bottle.wineID, default: []].append(bottle)
            case .ordered: onOrder[bottle.wineID, default: []].append(bottle)
            case .consumed, .gone: break
            }
        }
        return wines.values
            .filter { $0.has(attribute) }
            .compactMap { wine -> WineGroup? in
                if let kept = inCellar[wine.id] {
                    return WineGroup(wine: wine, bottles: kept.sorted { $0.addedAt < $1.addedAt })
                }
                if let ordered = onOrder[wine.id] {
                    let sorted = ordered.sorted { ($0.expectedAt ?? .distantFuture, $0.addedAt) < ($1.expectedAt ?? .distantFuture, $1.addedAt) }
                    return WineGroup(wine: wine, bottles: sorted)
                }
                return nil
            }
            .sorted { a, b in
                if Cellar.nameOrder(a.wine, b.wine) { return true }
                if Cellar.nameOrder(b.wine, a.wine) { return false }
                return a.wine.id.uuidString < b.wine.id.uuidString
            }
    }
}
