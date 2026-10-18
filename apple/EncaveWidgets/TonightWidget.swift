import CaveKit
import SwiftUI
import WidgetKit

/// "Tonight": the bottles to open now, the most urgent first. A row opens its wine.
struct TonightWidget: Widget {
    static let kind = "app.encave.widgets.tonight"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: Self.kind, provider: SnapshotProvider()) { entry in
            TonightWidgetView(snapshot: entry.snapshot)
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("Tonight")
        .description("The bottles to open now, the most urgent first.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

struct TonightWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let snapshot: WidgetSnapshot?

    private var rowLimit: Int {
        switch family {
        case .systemSmall: 2
        case .systemMedium: 3
        default: WidgetSnapshot.pickLimit
        }
    }

    var body: some View {
        if let snapshot, !snapshot.isEmpty {
            VStack(alignment: .leading, spacing: family == .systemSmall ? 6 : 8) {
                header
                if snapshot.tonight.isEmpty {
                    Spacer(minLength: 0)
                    Text("No bottle is at its best right now.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(4)
                        .minimumScaleFactor(0.8)
                    Spacer(minLength: 0)
                } else {
                    rows(Array(snapshot.tonight.prefix(rowLimit)))
                    Spacer(minLength: 0)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            // The small widget is one tap target: the first bottle, or the Tonight section.
            .widgetURL(family == .systemSmall
                       ? (snapshot.tonight.first.map { EncaveLink.wine($0.wineID).url } ?? EncaveLink.tonight.url)
                       : EncaveLink.tonight.url)
        } else {
            EmptyCellarView()
        }
    }

    private var header: some View {
        Label("Tonight", systemImage: "moon.stars")
            .font(.headline)
            .foregroundStyle(Color.accentColor)
            .lineLimit(1)
            .accessibilityAddTraits(.isHeader)
    }

    @ViewBuilder
    private func rows(_ picks: [WidgetSnapshot.Pick]) -> some View {
        if family == .systemSmall {
            ForEach(picks) { pick in
                PickRow(pick: pick, compact: true)
            }
        } else {
            ForEach(picks) { pick in
                Link(destination: EncaveLink.wine(pick.wineID).url) {
                    PickRow(pick: pick, compact: false)
                }
                if pick.id != picks.last?.id {
                    Divider()
                }
            }
        }
    }
}

/// A wine to open: name and vintage, maturity (symbol and words, never color
/// alone) and where the bottle is.
struct PickRow: View {
    let pick: WidgetSnapshot.Pick
    /// Small widget: no place and no count.
    let compact: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                (Text(verbatim: pick.name).fontWeight(.semibold)
                    + Text(verbatim: pick.vintage.map { " \($0)" } ?? "").foregroundStyle(.secondary))
                    .font(compact ? .footnote : .subheadline)
                    .lineLimit(1)
                if !compact {
                    Spacer(minLength: 4)
                    Text(verbatim: "×\(pick.bottles)")
                        .font(.caption.weight(.semibold).monospacedDigit())
                        .foregroundStyle(.secondary)
                        .accessibilityLabel(Text("\(pick.bottles) bottles"))
                }
            }
            HStack(spacing: 8) {
                Label(pick.maturity.shortTitle, systemImage: pick.maturity.systemImage)
                    .foregroundStyle(pick.maturity.tint)
                    .lineLimit(1)
                if !compact, let place = pick.place {
                    Label(place, systemImage: "square.grid.3x3")
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            .font(.caption2)
            .labelStyle(TightLabelStyle())
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

/// Icon and title close together, for the dense lines of a widget.
struct TightLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 3) {
            configuration.icon.imageScale(.small)
            configuration.title
        }
    }
}
