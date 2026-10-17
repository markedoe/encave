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
        if let model = CellarModel.current { return model.cellar }
        return (try? JSONFileCellarStore.defaultStore().load()) ?? Cellar()
    }

    static var year: Int { Calendar.current.component(.year, from: .now) }

    /// "Main rack · B3", or the storage note.
    static func location(of bottle: Bottle, in cellar: Cellar) -> String? {
        cellar.locationLabel(for: bottle)
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
