import CaveKit
import SwiftUI
import WidgetKit

// Home screen and lock screen widgets, and the Control Center control (iPhone and iPad).
// They draw only the summary the app writes to the App Group container they share
// (WidgetSnapshot, written by WidgetPublisher in the app): no cellar, no keys, no network.

@main
struct EncaveWidgets: WidgetBundle {
    var body: some Widget {
        TonightWidget()
        DrinkSoonWidget()
        CellarWidget()
        AddBottleControl()
    }
}

/// "Tonight": the bottle Tonight suggests today and where it is; on the lock screen too.
struct TonightWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "app.encave.widget.tonight", provider: SnapshotProvider()) { entry in
            TonightWidgetView(entry: entry)
        }
        .configurationDisplayName("Tonight")
        .description("The bottle to open tonight and where it is.")
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryRectangular, .accessoryInline])
    }
}

/// "Drink Soon": the first wines to drink soon; on the lock screen, how many bottles.
struct DrinkSoonWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "app.encave.widget.drinkSoon", provider: SnapshotProvider()) { entry in
            DrinkSoonWidgetView(entry: entry)
        }
        .configurationDisplayName("Drink Soon")
        .description("The wines to drink soon, the most urgent first.")
        .supportedFamilies([.systemMedium, .accessoryCircular])
    }
}

/// "Cellar": bottles in the cellar, to keep and to drink soon.
struct CellarWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "app.encave.widget.cellar", provider: SnapshotProvider()) { entry in
            CellarWidgetView(entry: entry)
        }
        .configurationDisplayName("Cellar")
        .description("The bottles in your cellar, to keep and to drink soon.")
        .supportedFamilies([.systemSmall])
    }
}

/// Control Center, the lock screen and the Action button: Add Bottles, on the label scanner.
struct AddBottleControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "app.encave.control.addBottle") {
            ControlWidgetButton(action: AddBottleIntent()) {
                Label("Add Bottles", systemImage: "wineglass")
            }
        }
        .displayName("Add Bottles")
        .description("Opens Encave on the label scanner to add bottles.")
    }
}

/// What a widget draws from a moment on.
struct SnapshotEntry: TimelineEntry {
    let date: Date
    /// Nil until the app has written its first summary.
    let snapshot: WidgetSnapshot?

    /// Tonight's suggestion on `date`.
    var tonight: WidgetSnapshot.Pick? { snapshot?.suggestion(on: date) }
}

enum SnapshotFile {
    /// The summary the app wrote; nil before the first one, or without the App Group.
    static func read() -> WidgetSnapshot? {
        guard let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: WidgetSnapshot.appGroup)?
            .appendingPathComponent(WidgetSnapshot.fileName) else { return nil }
        return WidgetSnapshot.decode(try? Data(contentsOf: url))
    }

    /// The sample cellar, for the widget gallery before the app wrote a summary.
    static var preview: WidgetSnapshot {
        let cellar = SampleData.cellar(labels: .widgetPreview)
        return WidgetSnapshot(cellar, now: .now, place: { cellar.locationLabel(for: $0) })
    }
}

extension SampleData.Labels {
    /// The names a preview shows, in the person's language.
    static var widgetPreview: Self {
        var labels = Self()
        labels.mainRack = String(localized: "Main rack")
        labels.wineFridge = String(localized: "Cabinet")
        labels.bins = String(localized: "Diamond bins")
        labels.deepRack = String(localized: "Double-depth rack")
        labels.honeycomb = String(localized: "Honeycomb rack")
        labels.stairs = String(localized: "Under the stairs")
        labels.largeFridge = String(localized: "Large cabinet")
        labels.headToTailFridge = String(localized: "Head-to-tail cabinet")
        labels.kitchen = String(localized: "Kitchen")
        return labels
    }
}

/// One timeline for every widget: the summary now, then a new entry at each midnight
/// while Tonight's suggestions last, so the wine changes with the day as in the app.
struct SnapshotProvider: TimelineProvider {
    func placeholder(in context: Context) -> SnapshotEntry {
        SnapshotEntry(date: .now, snapshot: SnapshotFile.preview)
    }

    func getSnapshot(in context: Context, completion: @escaping (SnapshotEntry) -> Void) {
        let saved = SnapshotFile.read()
        completion(SnapshotEntry(date: .now, snapshot: context.isPreview ? saved ?? SnapshotFile.preview : saved))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<SnapshotEntry>) -> Void) {
        let now = Date.now
        let snapshot = SnapshotFile.read()
        let entries = (snapshot?.refreshDates(from: now) ?? [now]).map { SnapshotEntry(date: $0, snapshot: snapshot) }
        // The app redraws the widgets whenever the summary changes; past the last day kept,
        // the file is read again.
        completion(Timeline(entries: entries, policy: .atEnd))
    }
}
