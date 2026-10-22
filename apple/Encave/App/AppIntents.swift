import AppIntents
import CaveKit
import CoreSpotlight
import Foundation

// Siri, Shortcuts and Spotlight: "What should I open tonight?", "Where is my Chablis?",
// wines in Spotlight. Everything is read and indexed on the device.

/// The cellar for an intent: the open app's, or the saved file when Siri runs the
/// app in the background.
@MainActor
enum CellarAccess {
    static func cellar() -> Cellar {
        if let model = CellarModel.current {
            model.reloadIfLoadFailed()
            return model.cellar
        }
        return (try? JSONFileCellarStore.defaultStore().load()) ?? Cellar()
    }

    static var year: Int { CellarYear.current }

    /// "Main rack · B3", or the storage note.
    static func location(of bottle: Bottle, in cellar: Cellar) -> String? {
        // The place as the app shows it ("Main rack · B3, back").
        if let slot = bottle.slot, let rack = cellar.rack(slot.rackID) {
            return "\(rack.name) · \(rack.displayLabel(for: slot))"
        }
        return cellar.locationLabel(for: bottle)
    }
}

/// A wine of the cellar, for Siri, Shortcuts and Spotlight.
struct WineEntity: AppEntity, IndexedEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Wine"
    static let defaultQuery = WineQuery()

    let id: UUID
    let name: String
    /// "3 bottles · Main rack"
    let detail: String

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(name)", subtitle: "\(detail)")
    }

    init(_ group: WineGroup, cellar: Cellar) {
        id = group.wine.id
        name = group.wine.displayName
        let places = Set(group.bottles.compactMap { $0.slot.flatMap { cellar.rack($0.rackID)?.name } }).sorted()
        let count = String(localized: "\(group.count) bottles")
        detail = places.isEmpty ? count : count + " · " + places.joined(separator: ", ")
    }
}

struct WineQuery: EntityStringQuery {
    @MainActor
    func entities(for identifiers: [UUID]) async throws -> [WineEntity] {
        let cellar = CellarAccess.cellar()
        let wanted = Set(identifiers)
        return cellar.groups().filter { wanted.contains($0.wine.id) }.map { WineEntity($0, cellar: cellar) }
    }

    @MainActor
    func entities(matching string: String) async throws -> [WineEntity] {
        let cellar = CellarAccess.cellar()
        return cellar.groups(matching: string).prefix(20).map { WineEntity($0, cellar: cellar) }
    }

    @MainActor
    func suggestedEntities() async throws -> [WineEntity] {
        let cellar = CellarAccess.cellar()
        return cellar.groups().prefix(30).map { WineEntity($0, cellar: cellar) }
    }
}

/// "What should I open tonight?"
struct TonightIntent: AppIntent {
    static let title: LocalizedStringResource = "What to Open Tonight"
    static let description = IntentDescription("Suggests bottles from your cellar that are at their best, the most urgent first.")

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let cellar = CellarAccess.cellar()
        let picks = cellar.groupsByMaturity(year: CellarAccess.year)
            .filter { $0.maturity == .drinkSoon || $0.maturity == .ready }
            .flatMap(\.groups)
            .prefix(3)
        guard !picks.isEmpty else {
            return .result(dialog: "No bottle is at its best right now.")
        }
        let names = ListFormatter.localizedString(byJoining: picks.map { group in
            if let place = group.bottles.first.flatMap({ CellarAccess.location(of: $0, in: cellar) }) {
                return "\(group.wine.displayName) (\(place))"
            }
            return group.wine.displayName
        })
        return .result(dialog: "Tonight, open \(names).")
    }
}

/// "Where is my Chablis?"
struct FindWineIntent: AppIntent {
    static let title: LocalizedStringResource = "Find a Wine"
    static let description = IntentDescription("Says where the bottles of a wine are.")

    @Parameter(title: "Wine")
    var wine: WineEntity

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let cellar = CellarAccess.cellar()
        let places = cellar.bottles(of: wine.id).compactMap { CellarAccess.location(of: $0, in: cellar) }
        guard !places.isEmpty else {
            return .result(dialog: "\(wine.name) has no place in a rack.")
        }
        let placeNames = ListFormatter.localizedString(byJoining: places)
        return .result(dialog: "\(wine.name): \(placeNames).")
    }
}

/// "How many bottles should I drink soon?"
struct DrinkSoonIntent: AppIntent {
    static let title: LocalizedStringResource = "Bottles to Drink Soon"
    static let description = IntentDescription("Counts the bottles in the last year of their drinking window.")

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<Int> & ProvidesDialog {
        let digest = CellarAccess.cellar().drinkingDigest(year: CellarAccess.year)
        return .result(value: digest.drinkSoon, dialog: "Bottles to drink this year: \(digest.drinkSoon).")
    }
}

/// Which bottles "Get Cellar Contents" returns.
enum CellarScopeOption: String, AppEnum {
    case all, toOpen, drinkSoon, ready, young, pastPeak, onOrder, reserved

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Bottles"
    static let caseDisplayRepresentations: [CellarScopeOption: DisplayRepresentation] = [
        .all: "All Bottles",
        .toOpen: "To Open Now",
        .drinkSoon: "To Drink Soon",
        .ready: "Ready to Drink",
        .young: "Too Young",
        .pastPeak: "Past Their Peak",
        .onOrder: "On Order",
        .reserved: "Kept for an Occasion",
    ]

    var scope: CellarReportScope { CellarReportScope(rawValue: rawValue) ?? .all }
}

enum CellarFormatOption: String, AppEnum {
    case text, json

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Format"
    static let caseDisplayRepresentations: [CellarFormatOption: DisplayRepresentation] = [
        .text: "Text, One Line per Wine",
        .json: "JSON",
    ]
}

/// The cellar as text or JSON, for another app on this device, such as a local AI
/// assistant that runs shortcuts. The shortcut gets the text; Encave sends nothing.
/// The returned text uses fixed English words so other tools can rely on it.
struct CellarContentsIntent: AppIntent {
    static let title: LocalizedStringResource = "Get Cellar Contents"
    static let description = IntentDescription("Returns the bottles of your cellar as text or JSON, for another app or an AI assistant on this device. Encave sends nothing.")

    @Parameter(title: "Bottles", default: .all)
    var bottles: CellarScopeOption

    @Parameter(title: "Containing")
    var search: String?

    @Parameter(title: "Format", default: .text)
    var format: CellarFormatOption

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<String> {
        let report = CellarAccess.cellar().report(scope: bottles.scope, search: search ?? "", year: CellarAccess.year)
        let value = try format == .json ? report.json() : report.text
        return .result(value: value)
    }
}

/// One wine with its bottles and past tastings, as text, for another app.
struct WineDetailsIntent: AppIntent {
    static let title: LocalizedStringResource = "Get Wine Details"
    static let description = IntentDescription("Returns a wine of your cellar as text: its details, where its bottles are and your tasting notes.")

    @Parameter(title: "Wine")
    var wine: WineEntity

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<String> {
        let cellar = CellarAccess.cellar()
        guard let record = cellar.wines[wine.id] else { return .result(value: "") }
        let row = cellar.report(scope: .all, year: CellarAccess.year).rows.first { $0.wineID == wine.id }
        var lines = ["Wine: \(record.displayName)"]
        func add(_ title: String, _ value: String?) {
            if let value, !value.isEmpty { lines.append("\(title): \(value)") }
        }
        add("Producer", record.producer)
        add("Style", record.color?.rawValue)
        add("Origin", [record.appellation, record.region, record.country].compactMap { $0 }.joined(separator: ", "))
        add("Grapes", record.grapes.joined(separator: ", "))
        if record.drinkFrom != nil || record.drinkUntil != nil {
            add("Drinking window", [record.drinkFrom, record.drinkUntil].map { $0.map(String.init) ?? "?" }.joined(separator: "-"))
        }
        add("Maturity", row?.maturity)
        add("Bottles in the cellar", row.map { String($0.bottles) } ?? "0")
        add("Places", row?.places.joined(separator: "; "))
        add("Kept for", row?.keptFor.joined(separator: ", "))
        add("Tags", (record.tags ?? []).joined(separator: ", "))
        add("Notes", record.notes)
        let tastings = cellar.consumedBottles.filter { $0.wineID == wine.id }.compactMap(\.tasting)
        for tasting in tastings.prefix(20) {
            let parts = [tasting.rating.map { "\($0)/5" }, tasting.occasion, tasting.note, tasting.nose, tasting.palate,
                         tasting.foodPairing.map { "with \($0)" }]
            add("Tasted \(tasting.date.formatted(Date.ISO8601FormatStyle(timeZone: .current).year().month().day()))", parts.compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: "; "))
        }
        return .result(value: lines.joined(separator: "\n"))
    }
}

/// Opens the app on Add Bottles.
struct AddBottleIntent: AppIntent {
    static let title: LocalizedStringResource = "Add Bottles"
    static let description = IntentDescription("Opens Encave to add bottles, by scanning the label or typing.")
    static let openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        CellarModel.current?.selectedTab = .cellar
        CellarModel.current?.sheet = .addBottle(AddBottleContext())
        return .result()
    }
}

/// Opens a wine's page (from Spotlight or Shortcuts).
struct OpenWineIntent: OpenIntent {
    static let title: LocalizedStringResource = "Open Wine"

    @Parameter(title: "Wine")
    var target: WineEntity

    @MainActor
    func perform() async throws -> some IntentResult {
        CellarModel.current?.open(wine: target.id)
        return .result()
    }
}

struct EncaveShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: TonightIntent(), phrases: [
            "What should I open tonight in \(.applicationName)",
            "What to drink tonight with \(.applicationName)",
        ], shortTitle: "Tonight", systemImageName: "moon.stars")
        AppShortcut(intent: FindWineIntent(), phrases: [
            "Where is \(\.$wine) in \(.applicationName)",
            "Find \(\.$wine) in \(.applicationName)",
        ], shortTitle: "Find a Wine", systemImageName: "magnifyingglass")
        AppShortcut(intent: DrinkSoonIntent(), phrases: [
            "What should I drink soon in \(.applicationName)",
        ], shortTitle: "Drink Soon", systemImageName: "hourglass")
        AppShortcut(intent: AddBottleIntent(), phrases: [
            "Add a bottle to \(.applicationName)",
            "Scan a wine label with \(.applicationName)",
        ], shortTitle: "Add Bottles", systemImageName: "plus")
    }
}

/// Keeps Spotlight's on-device index of wines in step with the cellar.
enum SpotlightIndex {
    @MainActor
    static func update(_ cellar: Cellar) async {
        let entities = cellar.groups().map { WineEntity($0, cellar: cellar) }
        let index = CSSearchableIndex.default()
        try? await index.deleteAppEntities(ofType: WineEntity.self)
        try? await index.indexAppEntities(entities)
        EncaveShortcuts.updateAppShortcutParameters()
    }

    static func clear() async {
        try? await CSSearchableIndex.default().deleteAppEntities(ofType: WineEntity.self)
    }
}
