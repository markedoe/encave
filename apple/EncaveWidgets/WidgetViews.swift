import CaveKit
import SwiftUI
import WidgetKit

// Colors come from the extension's asset catalog (design/tokens.json, written by
// design/generate_apple_assets.py); maturity always shows its symbol and words, never
// its color alone. Wine names and places are privacy sensitive: hidden on a locked
// device when the person turns that on.

/// Tonight: the suggested bottle (small, medium), or one line of it (lock screen).
struct TonightWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: SnapshotEntry

    var body: some View {
        content
            .widgetURL(link.url)
            .containerBackground(.background, for: .widget)
    }

    /// The suggested wine, Tonight when there is none, Add Bottles in an empty cellar.
    private var link: WidgetLink {
        if let pick = entry.tonight { return .wine(pick.wineID) }
        return entry.snapshot?.isEmpty == true ? .addBottle : .tonight
    }

    @ViewBuilder
    private var content: some View {
        switch family {
        case .accessoryInline:
            if let pick = entry.tonight {
                Label {
                    Text(pick.displayName).privacySensitive()
                } icon: {
                    Image(systemName: "moon.stars")
                }
            } else {
                Label("Tonight", systemImage: "moon.stars")
            }
        case .accessoryRectangular:
            VStack(alignment: .leading, spacing: 1) {
                Label("Tonight", systemImage: "moon.stars")
                    .font(.headline)
                    .widgetAccentable()
                if let pick = entry.tonight {
                    Text(pick.displayName)
                        .privacySensitive()
                    if let place = pick.place {
                        Text(place)
                            .foregroundStyle(.secondary)
                            .privacySensitive()
                    }
                } else {
                    message.foregroundStyle(.secondary)
                }
            }
            .lineLimit(1)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
        default:
            VStack(alignment: .leading, spacing: 4) {
                WidgetHeader(title: "Tonight", systemImage: "moon.stars")
                Spacer(minLength: 0)
                if let pick = entry.tonight {
                    PickSummary(pick: pick, wide: family == .systemMedium)
                } else {
                    WidgetMessage(text: message)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
    }

    private var message: Text {
        guard let snapshot = entry.snapshot else { return Text("Open Encave to show your cellar here.") }
        return snapshot.isEmpty ? Text("Add bottles to see what to open tonight.") : Text("No bottle is at its best right now.")
    }
}

/// The wine to open: name and vintage, producer, maturity in words and symbol, where it is
/// and, when wide, how many bottles. Read as one element by VoiceOver.
struct PickSummary: View {
    let pick: WidgetSnapshot.Pick
    var wide = false

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(pick.displayName)
                .font(.headline)
                .lineLimit(wide ? 2 : 3)
                .minimumScaleFactor(0.8)
                .privacySensitive()
            if let producer = pick.producer, !producer.isEmpty {
                Text(producer)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .privacySensitive()
            }
            HStack(spacing: 8) {
                MaturityLabel(maturity: pick.maturity, short: true)
                if wide {
                    Text("\(pick.bottles) bottles")
                        .foregroundStyle(.secondary)
                }
            }
            .font(.caption2)
            .lineLimit(1)
            if let place = pick.place {
                Label(place, systemImage: "mappin.and.ellipse")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(wide ? 1 : 2)
                    .privacySensitive()
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// Drink Soon: the first three wines to drink soon (medium), or how many bottles (lock screen).
struct DrinkSoonWidgetView: View {
    @Environment(\.widgetFamily) private var family
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let entry: SnapshotEntry

    var body: some View {
        content
            .widgetURL((entry.snapshot?.isEmpty == true ? WidgetLink.addBottle : .tonight).url)
            .containerBackground(.background, for: .widget)
    }

    @ViewBuilder
    private var content: some View {
        let count = entry.snapshot?.drinkSoon ?? 0
        switch family {
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                VStack(spacing: 0) {
                    Image(systemName: Maturity.drinkSoon.systemImage)
                        .font(.caption)
                        .widgetAccentable()
                    Text(count, format: .number)
                        .font(.title3.weight(.semibold).monospacedDigit())
                        .minimumScaleFactor(0.5)
                        .lineLimit(1)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text("\(count) bottles to drink soon"))
        default:
            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .firstTextBaseline) {
                    WidgetHeader(title: "Drink Soon", systemImage: Maturity.drinkSoon.systemImage)
                    Spacer(minLength: 4)
                    if count > 0 {
                        Text("\(count) bottles")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
                if let snapshot = entry.snapshot, !snapshot.drinkSoonPicks.isEmpty {
                    ForEach(snapshot.drinkSoonPicks.prefix(rows)) { pick in
                        Link(destination: WidgetLink.wine(pick.wineID).url) {
                            DrinkSoonRow(pick: pick)
                        }
                    }
                    Spacer(minLength: 0)
                } else {
                    Spacer(minLength: 0)
                    WidgetMessage(text: message)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
    }

    /// Fewer rows with larger text, so none is cut.
    private var rows: Int {
        if dynamicTypeSize.isAccessibilitySize { return 1 }
        return dynamicTypeSize > .large ? 2 : WidgetSnapshot.drinkSoonLimit
    }

    private var message: Text {
        guard let snapshot = entry.snapshot else { return Text("Open Encave to show your cellar here.") }
        return snapshot.isEmpty ? Text("Add bottles to see what to open tonight.") : Text("No bottle to drink soon.")
    }
}

/// A wine to drink soon: name and vintage, where it is, and its free bottles.
struct DrinkSoonRow: View {
    let pick: WidgetSnapshot.Pick

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            VStack(alignment: .leading, spacing: 0) {
                Text(pick.displayName)
                    .font(.subheadline.weight(.semibold))
                    .privacySensitive()
                if let detail = pick.place ?? pick.producer {
                    Text(detail)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .privacySensitive()
                }
            }
            .lineLimit(1)
            Spacer(minLength: 4)
            Text("\(pick.bottles) bottles")
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
        .accessibilityElement(children: .combine)
    }
}

/// Cellar: bottles in the cellar, to keep and to drink soon.
struct CellarWidgetView: View {
    let entry: SnapshotEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            WidgetHeader(title: "Cellar", systemImage: "wineglass")
            Spacer(minLength: 0)
            if let snapshot = entry.snapshot, !snapshot.isEmpty {
                Text("\(snapshot.bottles) bottles")
                    .font(.title3.weight(.bold))
                    .lineLimit(2)
                    .minimumScaleFactor(0.7)
                CountLine(maturity: .young, text: Text("\(snapshot.toKeep) to keep"))
                CountLine(maturity: .drinkSoon, text: Text("\(snapshot.drinkSoon) to drink soon"))
            } else {
                WidgetMessage(text: entry.snapshot == nil ? Text("Open Encave to show your cellar here.") : Text("Add bottles to see what to open tonight."))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .widgetURL((entry.snapshot?.isEmpty == true ? WidgetLink.addBottle : .cellar).url)
        .containerBackground(.background, for: .widget)
    }
}

/// A count with its maturity symbol in the maturity's color and the words in the text color.
struct CountLine: View {
    let maturity: Maturity
    let text: Text

    var body: some View {
        Label {
            text
        } icon: {
            Image(systemName: maturity.systemImage).foregroundStyle(maturity.tint)
        }
        .font(.caption)
        .lineLimit(1)
        .minimumScaleFactor(0.8)
    }
}

/// The widget's name and symbol, in the accent color.
struct WidgetHeader: View {
    let title: LocalizedStringKey
    let systemImage: String

    var body: some View {
        Label(title, systemImage: systemImage)
            .font(.caption.weight(.semibold))
            .foregroundStyle(Color.accentColor)
            .lineLimit(1)
            .widgetAccentable()
            .accessibilityAddTraits(.isHeader)
    }
}

/// A sentence in place of the content: nothing ready, an empty cellar.
struct WidgetMessage: View {
    let text: Text

    var body: some View {
        text
            .font(.caption)
            .foregroundStyle(.secondary)
            .lineLimit(4)
            .minimumScaleFactor(0.8)
    }
}
