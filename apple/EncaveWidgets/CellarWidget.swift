import CaveKit
import SwiftUI
import WidgetKit

/// "Cellar": how many bottles, and how many to drink soon. Home screen and lock screen.
struct CellarWidget: Widget {
    static let kind = "app.encave.widgets.cellar"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: Self.kind, provider: SnapshotProvider()) { entry in
            CellarWidgetView(snapshot: entry.snapshot)
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("Cellar")
        .description("How many bottles you have, and how many to drink soon.")
        .supportedFamilies([.systemSmall, .accessoryCircular, .accessoryRectangular, .accessoryInline])
    }
}

struct CellarWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let snapshot: WidgetSnapshot?

    var body: some View {
        if let snapshot, !snapshot.isEmpty {
            content(snapshot)
                .widgetURL(EncaveLink.tonight.url)
        } else {
            empty
        }
    }

    @ViewBuilder
    private func content(_ snapshot: WidgetSnapshot) -> some View {
        switch family {
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                VStack(spacing: 0) {
                    HStack(spacing: 2) {
                        Image(systemName: "wineglass")
                            .widgetAccentable()
                        Text(verbatim: "\(snapshot.bottles)")
                    }
                    .font(.headline)
                    HStack(spacing: 2) {
                        Image(systemName: Maturity.drinkSoon.systemImage)
                        Text(verbatim: "\(snapshot.drinkSoon)")
                    }
                    .font(.caption2)
                }
                .minimumScaleFactor(0.5)
                .lineLimit(1)
                .padding(4)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(summary(snapshot))
        case .accessoryRectangular:
            VStack(alignment: .leading, spacing: 1) {
                Label("Encave", systemImage: "wineglass")
                    .font(.headline)
                    .widgetAccentable()
                Text("\(snapshot.bottles) bottles in the cellar")
                Label {
                    Text("Drink soon: \(snapshot.drinkSoon)")
                } icon: {
                    Image(systemName: Maturity.drinkSoon.systemImage)
                }
            }
            .font(.caption)
            .lineLimit(1)
            .minimumScaleFactor(0.8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(summary(snapshot))
        case .accessoryInline:
            Label {
                Text("\(snapshot.bottles) bottles") + Text(verbatim: " · ") + Text("Drink soon: \(snapshot.drinkSoon)")
            } icon: {
                Image(systemName: "wineglass")
            }
        default:
            VStack(alignment: .leading, spacing: 4) {
                Label("Cellar", systemImage: "wineglass")
                    .font(.headline)
                    .foregroundStyle(Color.accentColor)
                    .lineLimit(1)
                Spacer(minLength: 0)
                Text("\(snapshot.bottles) bottles")
                    .font(.title2.weight(.bold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                Group {
                    countLine(.drinkSoon, Text("Drink soon: \(snapshot.drinkSoon)"))
                    countLine(.ready, Text("Ready: \(snapshot.ready)"))
                    if snapshot.onOrder > 0 {
                        Label {
                            Text("On order: \(snapshot.onOrder)")
                        } icon: {
                            Image(systemName: "shippingbox")
                        }
                        .foregroundStyle(.secondary)
                    }
                }
                .font(.caption)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .labelStyle(TightLabelStyle())
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
        }
    }

    /// A count with its maturity symbol and color; the words carry the meaning.
    private func countLine(_ maturity: Maturity, _ text: Text) -> some View {
        Label {
            text
        } icon: {
            Image(systemName: maturity.systemImage)
                .foregroundStyle(maturity.tint)
        }
    }

    /// "42 bottles in the cellar, Drink soon: 3" for VoiceOver.
    private func summary(_ snapshot: WidgetSnapshot) -> Text {
        Text("\(snapshot.bottles) bottles in the cellar") + Text(verbatim: ", ") + Text("Drink soon: \(snapshot.drinkSoon)")
    }

    @ViewBuilder
    private var empty: some View {
        switch family {
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                Image(systemName: "plus")
                    .font(.title3)
                    .widgetAccentable()
            }
            .accessibilityLabel(Text("Add bottles in Encave"))
            .widgetURL(EncaveLink.addBottles.url)
        case .accessoryRectangular:
            Label("Add bottles in Encave", systemImage: "wineglass")
                .font(.caption)
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .widgetURL(EncaveLink.addBottles.url)
        case .accessoryInline:
            Label("Add bottles in Encave", systemImage: "wineglass")
                .widgetURL(EncaveLink.addBottles.url)
        default:
            EmptyCellarView()
        }
    }
}
