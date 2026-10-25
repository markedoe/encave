import AppIntents
import CaveKit
import CoreSpotlight
import Foundation
import SwiftUI

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

    /// "Main rack · B3", "Cabinet · Shelf 2, position 3", or the storage note.
    static func location(of bottle: Bottle, in cellar: Cellar) -> String? {
        // The position as the app shows it ("Main rack · B3, back").
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
            return .result(dialog: "\(wine.name) has no position in your storage.")
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
        let cellar = CellarAccess.cellar()
        let report = cellar.report(scope: bottles.scope, search: search ?? "", year: CellarAccess.year,
                                   location: { CellarAccess.location(of: $0, in: cellar) })
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
        let row = cellar.report(scope: .all, year: CellarAccess.year, location: { CellarAccess.location(of: $0, in: cellar) })
            .rows.first { $0.wineID == wine.id }
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
        add("Positions", row?.places.joined(separator: "; "))
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

/// "Ask my cellar": a question about an evening, answered on this device by Apple
/// Intelligence with bottles the user has and where they are. Nothing is sent anywhere.
struct AskCellarIntent: AppIntent {
    static let title: LocalizedStringResource = "Ask My Cellar"
    static let description = IntentDescription("Answers a question such as “dinner for 6 with a tagine” with bottles from your cellar and where they are. Answered on this device: nothing is sent anywhere.")

    @Parameter(title: "Question", requestValueDialog: "What would you like to ask your cellar?")
    var question: String

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<[WineEntity]> & ProvidesDialog & ShowsSnippetView {
        let text = question.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { throw $question.needsValueError() }
        let cellar = CellarAccess.cellar()
        var spokenLabel: String
        var items: [AskCellarSnippet.Item] = []
        do {
            let answer = try await CellarAssistant.ask(text, cellar: cellar, year: CellarAccess.year) {
                CellarAccess.location(of: $0, in: cellar)
            }
            let groups = cellar.groups()
            items = answer.wineIDs.compactMap { id in
                guard let group = groups.first(where: { $0.wine.id == id }) else { return nil }
                let place = group.bottles.bottleToOpen.flatMap { CellarAccess.location(of: $0, in: cellar) }
                return AskCellarSnippet.Item(entity: WineEntity(group, cellar: cellar), place: place)
            }
            spokenLabel = answer.text
            let placeLabels = items.compactMap { item in item.place.map { "\(item.entity.name) (\($0))" } }
            if !placeLabels.isEmpty {
                let placesLabel = ListFormatter.localizedString(byJoining: placeLabels)
                spokenLabel += "\n" + String(localized: "Where they are: \(placesLabel).")
            }
            if answer.considered < answer.total {
                let considered = answer.considered, total = answer.total
                spokenLabel += "\n" + String(localized: "Based on \(considered) of \(total) wines: the closest matches and the most urgent.")
            }
        } catch CellarAssistant.Failure.unavailable {
            spokenLabel = String(localized: "Ask My Cellar needs Apple Intelligence, which is not available on this device right now. To ask an assistant app instead, make a shortcut with Prepare an Evening Request: Encave’s Help shows how.")
        } catch CellarAssistant.Failure.refused {
            spokenLabel = String(localized: "This question cannot be answered. Try asking about a dish or an occasion.")
        } catch CellarAssistant.Failure.tooLarge {
            spokenLabel = String(localized: "Your cellar or the question is too large for the model on this device. Try a shorter question.")
        } catch CellarAssistant.Failure.unsupportedLanguage {
            spokenLabel = String(localized: "Apple Intelligence does not answer in the language of this device yet.")
        } catch {
            spokenLabel = String(localized: "No answer this time. Try again.")
        }
        return .result(value: items.map(\.entity), dialog: "\(spokenLabel)", view: AskCellarSnippet(items: items))
    }
}

/// The suggested wines under Siri's answer, each opening its page in Encave.
struct AskCellarSnippet: View {
    struct Item {
        var entity: WineEntity
        var place: String?
    }

    var items: [Item]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(items, id: \.entity.id) { item in
                Button(intent: OpenWineIntent(item.entity)) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.entity.name).font(.headline)
                        if let place = item.place {
                            Text(place).font(.subheadline).foregroundStyle(.secondary)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(.plain)
                .accessibilityHint(Text("Opens this wine in Encave."))
            }
        }
        .padding(items.isEmpty ? 0 : 12)
    }
}

/// Which language the request is written in: the app's.
private var appLanguageCode: String? {
    Bundle.main.preferredLocalizations.first
}

/// A request for an evening's selection, as text for an assistant app the user picks
/// in their own shortcut (for example Claude's Ask Claude): the instruction, then the
/// bottles with their positions, without prices, notes or places of purchase. The
/// shortcut gets the text, like Get Cellar Contents; Encave sends nothing.
struct EveningRequestIntent: AppIntent {
    static let title: LocalizedStringResource = "Prepare an Evening Request"
    static let description = IntentDescription("Returns a request for an assistant app, in your language: choose bottles from your cellar for an evening, with the order of service, why and the temperatures, followed by your bottles and their positions, without prices, notes or places of purchase. Encave sends nothing: your shortcut passes the text to the app you choose.")

    @Parameter(title: "Occasion", requestValueDialog: "What is the evening? Dishes, guests…")
    var occasion: String?

    @Parameter(title: "Number of Bottles")
    var bottles: Int?

    @Parameter(title: "Only Bottles Ready to Drink", default: true)
    var onlyReady: Bool

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<String> {
        let cellar = CellarAccess.cellar()
        let request = cellar.eveningRequest(occasion: occasion ?? "", bottlesWanted: bottles, onlyReady: onlyReady,
                                            language: EveningRequest.Language(code: appLanguageCode),
                                            year: CellarAccess.year) { CellarAccess.location(of: $0, in: cellar) }
        return .result(value: request.text)
    }
}

/// Opens a wine's page (from Spotlight or Shortcuts).
struct OpenWineIntent: OpenIntent {
    static let title: LocalizedStringResource = "Open Wine"

    @Parameter(title: "Wine")
    var target: WineEntity

    init() {}

    init(_ wine: WineEntity) {
        target = wine
    }

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
        AppShortcut(intent: AskCellarIntent(), phrases: [
            "Ask my cellar in \(.applicationName)",
            "Ask my cellar with \(.applicationName)",
        ], shortTitle: "Ask My Cellar", systemImageName: "sparkles")
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

/// Keeps Spotlight's on-device index of wines in step with the cellar. A while after the
/// last change (or when the app goes to the background), only the wines whose indexed
/// content changed are indexed again, and removed wines are deleted from the index. The
/// first time after launch the index is rebuilt from scratch.
@MainActor
final class SpotlightIndex {
    private static let delay: Duration = .seconds(10)

    /// What the index holds, as far as this launch knows.
    private var snapshot = SearchIndexSnapshot()
    /// The cellar to index once the changes pause.
    private var pending: Cellar?
    private var debounce: Task<Void, Never>?
    /// The indexing in progress: the next one waits for it.
    private var running: Task<Void, Never>?

    nonisolated init() {}

    /// The cellar changed: it is indexed after a pause in the changes.
    func schedule(_ cellar: Cellar) {
        pending = cellar
        debounce?.cancel()
        debounce = Task { [weak self] in
            try? await Task.sleep(for: Self.delay)
            guard !Task.isCancelled else { return }
            self?.flush()
        }
    }

    /// Indexes the pending change now.
    func flush() {
        debounce?.cancel()
        debounce = nil
        guard let cellar = pending else { return }
        pending = nil
        let previous = running
        running = Task { [weak self] in
            await previous?.value
            await self?.index(cellar)
        }
    }

    private func index(_ cellar: Cellar) async {
        let entities = cellar.groups().map { WineEntity($0, cellar: cellar) }
        var content: [UUID: SearchIndexSnapshot.Entry] = [:]
        for entity in entities {
            content[entity.id] = SearchIndexSnapshot.Entry(name: entity.name, detail: entity.detail)
        }
        let changes = snapshot.changes(to: content)
        guard !changes.isEmpty else { return }
        let index = CSSearchableIndex.default()
        do {
            if changes.isFullReindex {
                try await index.deleteAppEntities(ofType: WineEntity.self)
            } else if !changes.toRemove.isEmpty {
                try await index.deleteAppEntities(identifiedBy: changes.toRemove, ofType: WineEntity.self)
            }
            let wanted = Set(changes.toIndex)
            let changed = entities.filter { wanted.contains($0.id) }
            if !changed.isEmpty { try await index.indexAppEntities(changed) }
            snapshot.record(content)
        } catch {
            // Rebuilt from scratch the next time.
            snapshot.invalidate()
        }
        // Siri phrases with a wine ("Where is Chablis in Encave?").
        if changes.namesChanged { EncaveShortcuts.updateAppShortcutParameters() }
    }
}
