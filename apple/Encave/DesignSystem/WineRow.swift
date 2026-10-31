import CaveKit
import SwiftUI

/// List row for a wine with bottles in the cellar: "Clos des Tilleuls 2017 ×3".
struct WineRow: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.dynamicTypeSize) private var typeSize
    let group: WineGroup

    private var subtitle: String { group.wine.styleLine }

    /// The bottles as their places read, so "A1 and 6 more" names the same place every time.
    private var placed: [Bottle] { model.cellar.inPlaceOrder(group.bottles) }

    /// Where the bottles are, "Not in storage" counted as a place of its own, so a wine
    /// with bottles to put away never reads as all stored.
    private var location: String? {
        // Bottles on order are not in the cellar yet: when they arrive is said instead.
        isOrder ? nil : Self.places(placed.map { model.placeLabel(for: $0) })
    }

    /// The symbol of the first bottle's place (the stored ones come first).
    private var locationIcon: String {
        placed.first.map(model.locationIcon(for:)) ?? BottlePlace.notInStorageIcon
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
        // At accessibility sizes the text gets the whole width: no thumbnail, the count under it.
        let large = typeSize.isAccessibilitySize
        HStack(spacing: 14) {
            if !large {
                WineThumbnail(wine: group.wine)
            }
            VStack(alignment: .leading, spacing: 3) {
                (Text(group.wine.name).font(.body.weight(.semibold))
                    + Text(verbatim: group.wine.vintage.map { " \($0)" } ?? "").foregroundStyle(.secondary))
                    .lineLimit(large ? nil : 2)
                if !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(large ? nil : 2)
                }
                let meta = large ? AnyLayout(VStackLayout(alignment: .leading, spacing: 2)) : AnyLayout(HStackLayout(spacing: 10))
                meta {
                    if isOrder {
                        ArrivalLabel(date: group.bottles.first?.expectedAt)
                    }
                    if maturity != .unknown {
                        // Kept on one line: the location gives way first.
                        MaturityLabel(maturity: maturity, short: true)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .fixedSize()
                    }
                    if let location {
                        Label(location, systemImage: locationIcon)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .layoutPriority(-1)
                    }
                }
                .font(.caption)
                .labelStyle(CompactLabelStyle())
                if large {
                    CountBadge(count: group.count)
                }
            }
            if !large {
                Spacer(minLength: 8)
                CountBadge(count: group.count)
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// The wine's picture in a row (its cover, else its label photo, in the studio look when
/// it is on), or its bottle end when it has none. The row says the wine in words.
/// The same in rows, cards and sheets (as on Android); `width` is 34 in rows, larger in cards.
struct WineThumbnail: View {
    let wine: Wine?
    var width: CGFloat = 34

    var body: some View {
        if let wine, wine.hasShownPhoto {
            WineImage(wine: wine, maxPixelSize: width > 40 ? 300 : 200, inset: 2)
                .frame(width: width, height: width * 46 / 34)
                .clipShape(RoundedRectangle(cornerRadius: width > 40 ? 10 : 7, style: .continuous))
                .accessibilityHidden(true)
        } else {
            BottleEnd(color: wine?.color, size: width)
                .accessibilityHidden(true)
        }
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
