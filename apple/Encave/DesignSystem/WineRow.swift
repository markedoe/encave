import CaveKit
import SwiftUI

/// List row for a wine with bottles in the cellar: "Clos des Tilleuls 2017 ×3".
struct WineRow: View {
    @Environment(CellarModel.self) private var model
    let group: WineGroup

    private var subtitle: String { group.wine.styleLine }

    private var location: String? {
        Self.places(group.bottles.compactMap { model.locationLabel(for: $0) })
    }

    /// The symbol of the first placed bottle's rack.
    private var locationIcon: String {
        group.bottles.first { $0.slot != nil }.map(model.locationIcon(for:)) ?? RackKind.grid.systemImage
    }

    /// "A1", or "A1 and 2 more" when the bottles are in several places (each place counted once).
    static func places(_ labels: [String]) -> String? {
        var distinct: [String] = []
        for label in labels where !distinct.contains(label) {
            distinct.append(label)
        }
        guard let first = distinct.first else { return nil }
        guard distinct.count > 1 else { return first }
        let othersLabel = String(localized: "\(distinct.count - 1) more")
        return String(localized: "\(first) and \(othersLabel)")
    }

    /// Bottles on order: when they should arrive, instead of maturity and place.
    private var isOrder: Bool { group.bottles.first?.status == .ordered }

    var body: some View {
        let maturity: Maturity = isOrder ? .unknown : Maturity.of(group.wine, year: model.currentYear)
        HStack(spacing: 14) {
            if group.wine.hasShownPhoto {
                WineImage(wine: group.wine, maxPixelSize: 200, inset: 2)
                    .frame(width: 34, height: 46)
                    .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
                    .accessibilityHidden(true)
            } else {
                BottleEnd(color: group.wine.color, size: 34)
            }
            VStack(alignment: .leading, spacing: 3) {
                (Text(group.wine.name).font(.body.weight(.semibold))
                    + Text(verbatim: group.wine.vintage.map { " \($0)" } ?? "").foregroundStyle(.secondary))
                    .lineLimit(2)
                if !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                HStack(spacing: 10) {
                    if isOrder {
                        ArrivalLabel(date: group.bottles.first?.expectedAt)
                    }
                    if maturity != .unknown {
                        MaturityLabel(maturity: maturity, short: true)
                            .foregroundStyle(.secondary)
                    }
                    if let location {
                        Label(location, systemImage: locationIcon)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
                .font(.caption)
                .labelStyle(CompactLabelStyle())
            }
            Spacer(minLength: 8)
            CountBadge(count: group.count)
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

extension Wine {
    /// "Red · Domaine X · Pauillac": the style in words, not only by the bottle's color.
    var styleLine: String {
        [color?.name, producer, appellation ?? region].compactMap { $0 }.joined(separator: " · ")
    }
}

/// "×3" in a soft capsule.
struct CountBadge: View {
    let count: Int

    var body: some View {
        Text(verbatim: "×\(count)")
            .font(.subheadline.weight(.semibold).monospacedDigit())
            .padding(.horizontal, 9)
            .padding(.vertical, 4)
            .background(Color.secondary.opacity(0.12), in: Capsule())
            .accessibilityLabel(Text("\(count) bottles"))
    }
}

/// Icon and title close together, for dense metadata lines.
struct CompactLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 3) {
            configuration.icon.imageScale(.small)
            configuration.title
        }
    }
}

/// "Expected 12 Nov", "Late: expected 3 Oct" or "Delivery date unknown".
/// Lateness is said in words and with its own symbol, not only in color.
struct ArrivalLabel: View {
    let date: Date?

    var body: some View {
        if let date {
            let sameYear = Calendar.current.isDate(date, equalTo: .now, toGranularity: .year)
            let dayLabel = sameYear ? date.formatted(.dateTime.day().month()) : date.formatted(.dateTime.day().month().year())
            if Calendar.current.startOfDay(for: date) < Calendar.current.startOfDay(for: .now) {
                Label("Late: expected \(dayLabel)", systemImage: "exclamationmark.triangle")
                    .foregroundStyle(.orange)
            } else {
                Label("Expected \(dayLabel)", systemImage: "shippingbox")
                    .foregroundStyle(.secondary)
            }
        } else {
            Label("Delivery date unknown", systemImage: "shippingbox")
                .foregroundStyle(.secondary)
        }
    }
}
