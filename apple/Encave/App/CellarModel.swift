import CaveKit
import Foundation
import Observation

/// Holds the cellar for the UI, persists every change and drives app-wide
/// presentation (sheets, undo banner).
@MainActor
@Observable
final class CellarModel {
    private(set) var cellar: Cellar
    var errorMessage: String?
    /// The sheet shown above everything (add, drink, edit...).
    var sheet: AppSheet?
    /// Short confirmation with an optional Undo, shown at the bottom.
    var toast: Toast?
    /// Bumped on successful actions, drives haptic feedback.
    var successCount = 0
    /// Slot to highlight when jumping to a rack ("Show in rack").
    var highlightedSlot: Slot?
    /// Selected tab, so other screens can jump between sections.
    var selectedTab: AppTab = .cellar
    /// Page shown in Journal; set to Discoveries after remembering a wine.
    var journalPage: JournalView.Page = .tastings

    private let store: any CellarStore
    /// Sync with the user's other devices.
    @ObservationIgnored private(set) var sync: SyncService!
    /// Told about every local change (old and new cellar), to record it for sync.
    @ObservationIgnored var onLocalChange: ((Cellar, Cellar) -> Void)?

    /// `demo`: screen tour and previews, where sync never touches the network or files.
    init(store: any CellarStore, demo: Bool = true) {
        self.store = store
        do {
            cellar = try store.load()
        } catch {
            cellar = Cellar()
            errorMessage = String(localized: "Your cellar could not be loaded.")
        }
        sync = SyncService(model: self, demo: demo)
    }

    /// The app's model, for Siri and Shortcuts (App Intents) while the app runs.
    private(set) static weak var current: CellarModel?

    /// A wine to show, asked by Spotlight, Shortcuts or a notification.
    var openWineRequest: UUID?

    func open(wine id: UUID) {
        guard cellar.wines[id] != nil else { return }
        sheet = nil
        selectedTab = .cellar
        openWineRequest = id
    }

    static func live() -> CellarModel {
        let model = makeLive()
        current = model
        return model
    }

    private static func makeLive() -> CellarModel {
        // Launch test in CI: sample data in memory, never written to disk.
        let arguments = ProcessInfo.processInfo.arguments
        if arguments.contains("--sample-data") {
            let model = preview()
            if let index = arguments.firstIndex(of: "--tab"), arguments.indices.contains(index + 1),
               let tab = AppTab(rawValue: arguments[index + 1]) {
                model.selectedTab = tab
            }
            return model
        }
        if let store = try? JSONFileCellarStore.defaultStore() {
            let model = CellarModel(store: store, demo: false)
            // Photos of deleted wines are kept until the next launch so deleting can be undone.
            if model.errorMessage == nil {
                PhotoStore.removeOrphans(keeping: Set(model.cellar.wines.values.compactMap(\.photoID)))
            }
            return model
        }
        return CellarModel(store: InMemoryCellarStore())
    }

    static func preview() -> CellarModel {
        CellarModel(store: InMemoryCellarStore(SampleData.cellar(labels: .localized)))
    }

    var currentYear: Int { Calendar.current.component(.year, from: .now) }

    /// Applies a change and saves it. On failure nothing changes and the error is shown.
    @discardableResult
    func update(_ change: (inout Cellar) throws -> Void) -> Bool {
        var copy = cellar
        do {
            try change(&copy)
            guard copy != cellar else { return true }
            try store.save(copy)
            let old = cellar
            cellar = copy
            onLocalChange?(old, copy)
            return true
        } catch let error as CellarError {
            errorMessage = error.localizedMessage
        } catch {
            errorMessage = String(localized: "The change could not be saved.")
        }
        return false
    }

    /// Replaces the cellar with the result of a sync. Not recorded as a local change.
    func applySynced(_ synced: Cellar) {
        guard synced != cellar else { return }
        do {
            try store.save(synced)
            cellar = synced
        } catch {
            errorMessage = String(localized: "The change could not be saved.")
        }
    }

    // MARK: - Actions with feedback

    @discardableResult
    func addBottles(of wine: Wine, quantity: Int, slots: [Slot], purchase: PurchaseInfo) -> Bool {
        let ok = update { try $0.addBottles(of: wine, quantity: quantity, slots: slots, purchase: purchase) }
        guard ok else { return false }
        successCount += 1
        showToast(purchase.isOrdered
                  ? String(localized: "On order: \(quantity) × \(wine.displayName)")
                  : String(localized: "Added \(quantity) × \(wine.displayName)"))
        return true
    }

    /// Keeps bottles for an occasion (or frees them with nil), with Undo.
    func reserve(_ bottleIDs: [UUID], for occasion: String?) {
        let previous = bottleIDs.compactMap { cellar.bottles[$0] }
        guard !previous.isEmpty, update({ try $0.reserve(bottleIDs, for: occasion) }) else { return }
        let name = occasion?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        showToast(name.isEmpty ? String(localized: "Reservation removed")
                               : String(localized: "Kept for \(name)")) { [weak self] in
            self?.update { cellar in previous.forEach { cellar.bottles[$0.id]?.reservedFor = $0.reservedFor } }
        }
    }

    /// Ordered bottles that arrived. They join the cellar without a place.
    func receive(_ bottleIDs: [UUID]) {
        let previous = bottleIDs.compactMap { cellar.bottles[$0] }
        guard !previous.isEmpty, update({ try $0.receive(bottleIDs) }) else { return }
        successCount += 1
        showToast(String(localized: "Bottles received: \(previous.count)")) { [weak self] in
            self?.update { cellar in previous.forEach { cellar.bottles[$0.id] = $0 } }
        }
    }

    func drink(_ bottleID: UUID, tasting: Tasting) {
        guard let bottle = cellar.bottles[bottleID], let wine = cellar.wines[bottle.wineID] else { return }
        let previousSlot = bottle.slot
        guard update({ try $0.drink(bottleID, tasting: tasting) }) else { return }
        successCount += 1
        showToast(String(localized: "Enjoy your \(wine.displayName)")) { [weak self] in
            self?.update { $0.undoDrink(bottleID, previousSlot: previousSlot) }
        }
    }

    func markGone(_ bottleID: UUID) {
        guard let bottle = cellar.bottles[bottleID] else { return }
        guard update({ try $0.markGone(bottleID) }) else { return }
        showToast(bottle.status == .ordered
                  ? String(localized: "Order cancelled")
                  : String(localized: "Bottle removed from the cellar")) { [weak self] in
            // Put the record back as it was (an order stays an order), in its
            // place if nothing took it meanwhile.
            self?.update { cellar in
                var restored = bottle
                if let slot = bottle.slot, cellar.bottle(at: slot) != nil { restored.slot = nil }
                cellar.bottles[bottleID] = restored
            }
        }
    }

    func deleteWine(_ wineID: UUID) {
        let snapshot = cellar
        guard let wine = cellar.wines[wineID] else { return }
        update { $0.deleteWine(wineID) }
        showToast(String(localized: "Deleted \(wine.displayName)")) { [weak self] in
            self?.update { $0 = snapshot }
        }
    }

    // MARK: Several wines at once (one Undo for all)

    func place(_ wineIDs: Set<UUID>, inRack rackID: UUID) {
        let snapshot = cellar
        var total = 0
        guard update({ total = try $0.place(wines: wineIDs, in: rackID) }) else { return }
        guard total > 0 else {
            showToast(String(localized: "No free place in this rack for bottles without a place."))
            return
        }
        successCount += 1
        showToast(String(localized: "Bottles placed: \(total)")) { [weak self] in self?.update { $0 = snapshot } }
    }

    func unplace(_ wineIDs: Set<UUID>) {
        let snapshot = cellar
        var moved = 0
        guard update({ moved = $0.unplace(wines: wineIDs) }), moved > 0 else { return }
        showToast(String(localized: "Bottles taken out of their places: \(moved)")) { [weak self] in
            self?.update { $0 = snapshot }
        }
    }

    /// Bottles not found where they should be: they stay in the cellar without a place.
    func takeOut(_ bottleIDs: Set<UUID>) {
        let snapshot = cellar
        guard update({ cellar in bottleIDs.forEach { cellar.bottles[$0]?.slot = nil } }) else { return }
        showToast(String(localized: "Bottles taken out of their places: \(bottleIDs.count)")) { [weak self] in
            self?.update { $0 = snapshot }
        }
    }

    /// Bottles no longer in the cellar (given away, drunk elsewhere, broken).
    func markGone(_ bottleIDs: Set<UUID>) {
        let snapshot = cellar
        guard update({ cellar in try bottleIDs.forEach { try cellar.markGone($0) } }) else { return }
        showToast(String(localized: "Bottles removed from the cellar: \(bottleIDs.count)")) { [weak self] in
            self?.update { $0 = snapshot }
        }
    }

    func deleteWines(_ wineIDs: Set<UUID>) {
        let snapshot = cellar
        guard update({ cellar in wineIDs.forEach { cellar.deleteWine($0) } }) else { return }
        showToast(String(localized: "Wines deleted: \(wineIDs.count)")) { [weak self] in self?.update { $0 = snapshot } }
    }

    func addDiscovery(_ discovery: Discovery, wine: Wine) {
        guard update({ $0.addDiscovery(discovery, wine: wine) }) else { return }
        successCount += 1
        journalPage = .discoveries
        showToast(discovery.wantsToBuy
                  ? String(localized: "Added to your shopping list")
                  : String(localized: "Saved to your discoveries"))
    }

    func deleteDiscovery(_ id: UUID) {
        let snapshot = cellar
        guard update({ $0.deleteDiscovery(id) }) else { return }
        showToast(String(localized: "Discovery deleted")) { [weak self] in
            self?.update { $0 = snapshot }
        }
    }

    /// Opens "Add Bottles" for a wine on the shopping list.
    func buy(_ discovery: Discovery) {
        guard let wine = cellar.wines[discovery.wineID] else { return }
        sheet = .addBottle(AddBottleContext(wine: wine, discoveryID: discovery.id))
    }

    func move(_ bottleID: UUID, to slot: Slot?) {
        if update({ try $0.move(bottleID, to: slot) }) { successCount += 1 }
    }

    func swap(_ a: Slot, _ b: Slot) {
        if update({ try $0.swap(a, b) }) { successCount += 1 }
    }

    func showInRack(_ bottle: Bottle) {
        guard let slot = bottle.slot else { return }
        highlightedSlot = slot
        selectedTab = .racks
    }

    func loadSample() {
        update { $0 = SampleData.cellar(labels: .localized) }
        showToast(String(localized: "Sample cellar loaded"))
    }

    func eraseAll() {
        let snapshot = cellar
        update { $0 = Cellar(settings: snapshot.settings) }
        showToast(String(localized: "All data erased")) { [weak self] in
            self?.update { $0 = snapshot }
        }
    }

    func showToast(_ message: String, undo: (@MainActor () -> Void)? = nil) {
        toast = Toast(message: message, undo: undo)
    }
}

enum AppTab: String, Hashable {
    case cellar, racks, tonight, journal
}

/// Sheets presented at the root so any screen can open them.
enum AppSheet: Identifiable {
    case addBottle(AddBottleContext)
    case drink(bottleID: UUID)
    case editWine(wineID: UUID)
    case editTasting(bottleID: UUID)
    case editRack(rackID: UUID?)
    case moveBottle(bottleID: UUID)
    /// Remember a wine found elsewhere (nil: a new one).
    case discovery(discoveryID: UUID?)
    case settings

    var id: String {
        switch self {
        case .addBottle(let context): "add-\(context.id)"
        case .drink(let id): "drink-\(id)"
        case .editWine(let id): "wine-\(id)"
        case .editTasting(let id): "tasting-\(id)"
        case .editRack(let id): "rack-\(id?.uuidString ?? "new")"
        case .moveBottle(let id): "move-\(id)"
        case .discovery(let id): "discovery-\(id?.uuidString ?? "new")"
        case .settings: "settings"
        }
    }
}

/// Where "Add bottle" was opened from, to prefill the form.
struct AddBottleContext: Identifiable {
    let id = UUID()
    var wine: Wine?
    var slot: Slot?
    /// Set when buying a wine from the shopping list: it leaves the list once added.
    var discoveryID: UUID?
}

struct Toast: Identifiable {
    let id = UUID()
    var message: String
    var undo: (@MainActor () -> Void)?
}

extension CellarError {
    var localizedMessage: String {
        switch self {
        case .slotTaken: String(localized: "That position is already taken.")
        case .slotOutOfRange: String(localized: "That position is outside the rack.")
        case .rackNotEmpty: String(localized: "Move the bottles out of the removed rows or columns first.")
        case .alreadyConsumed: String(localized: "This bottle has already been opened.")
        case .notReceived: String(localized: "This bottle is on order: mark it received first.")
        case .unknownWine, .unknownBottle, .unknownRack, .unknownDiscovery: String(localized: "This item no longer exists.")
        }
    }
}

extension SampleData.Labels {
    static var localized: Self {
        var labels = Self()
        labels.mainRack = String(localized: "Main rack")
        labels.wineFridge = String(localized: "Wine fridge")
        labels.bins = String(localized: "Diamond bins")
        labels.deepRack = String(localized: "Double-depth rack")
        labels.honeycomb = String(localized: "Honeycomb rack")
        labels.stairs = String(localized: "Under the stairs")
        labels.tastingNote = String(localized: "Silky, red fruit, long finish.")
        labels.kitchen = String(localized: "Kitchen")
        labels.occasion = String(localized: "Dinner with friends")
        labels.nose = String(localized: "Cherry, violet, a touch of undergrowth")
        labels.palate = String(localized: "Fine tannins, fresh acidity")
        labels.pairing = String(localized: "Roast duck")
        labels.friendsPlace = String(localized: "At Julie and Marc's")
        labels.friendsNote = String(localized: "Very fresh, perfect with the cheese platter.")
        labels.restaurantPlace = String(localized: "Bistro on the harbour, Lisbon")
        labels.restaurantNote = String(localized: "Mineral and salty, great with grilled fish.")
        labels.tagToBuy = String(localized: "For summer")
        labels.tagGift = String(localized: "Gift idea")
        labels.merchant = String(localized: "Wine merchant")
        labels.tagBirthYear = String(localized: "Birth year")
        labels.occasion2 = String(localized: "Christmas dinner")
        return labels
    }
}
