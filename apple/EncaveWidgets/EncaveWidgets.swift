import CaveKit
import SwiftUI
import WidgetKit

/// Home screen and lock screen widgets, and the Control Center control (iPhone, iPad).
/// They read the summary the app writes to the App Group container after each save;
/// they never read the cellar itself.
@main
struct EncaveWidgetsBundle: WidgetBundle {
    var body: some Widget {
        TonightWidget()
        CellarWidget()
        AddBottlesControl()
    }
}

struct SnapshotEntry: TimelineEntry {
    let date: Date
    /// nil until the app has written a summary (or without an App Group).
    let snapshot: WidgetSnapshot?
}

struct SnapshotProvider: TimelineProvider {
    func placeholder(in context: Context) -> SnapshotEntry {
        SnapshotEntry(date: .now, snapshot: .preview)
    }

    func getSnapshot(in context: Context, completion: @escaping (SnapshotEntry) -> Void) {
        let saved = WidgetSnapshotFile.read()
        // The widget gallery shows the sample cellar until there are bottles.
        let shown = context.isPreview && (saved?.isEmpty ?? true) ? WidgetSnapshot.preview : saved
        completion(SnapshotEntry(date: .now, snapshot: shown))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<SnapshotEntry>) -> Void) {
        let entry = SnapshotEntry(date: .now, snapshot: WidgetSnapshotFile.read())
        // The app reloads the widgets when the cellar changes. Read again tomorrow
        // anyway, in case a new year changed what is ready.
        let tomorrow = Calendar.current.startOfDay(for: .now).addingTimeInterval(26 * 3600)
        completion(Timeline(entries: [entry], policy: .after(tomorrow)))
    }
}

extension WidgetSnapshot {
    /// The sample cellar, for the widget gallery and placeholders.
    static var preview: WidgetSnapshot {
        var labels = SampleData.Labels()
        labels.mainRack = String(localized: "Main rack")
        labels.wineFridge = String(localized: "Wine fridge")
        labels.bins = String(localized: "Diamond bins")
        labels.deepRack = String(localized: "Double-depth rack")
        labels.honeycomb = String(localized: "Honeycomb rack")
        labels.stairs = String(localized: "Under the stairs")
        labels.kitchen = String(localized: "Kitchen")
        let year = Calendar.current.component(.year, from: .now)
        return WidgetSnapshot(cellar: SampleData.cellar(year: year, labels: labels), year: year)
    }
}

/// Nothing in the cellar yet: an invitation to add bottles, which opens Add Bottles.
struct EmptyCellarView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Image(systemName: "wineglass")
                .font(.title2)
                .foregroundStyle(Color.accentColor)
                .accessibilityHidden(true)
            Spacer(minLength: 0)
            Text("Add bottles in Encave")
                .font(.headline)
                .lineLimit(3)
                .minimumScaleFactor(0.8)
            Text("Scan a label or type a name.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(2)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
        .widgetURL(EncaveLink.addBottles.url)
    }
}
