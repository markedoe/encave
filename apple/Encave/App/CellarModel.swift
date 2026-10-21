import CaveKit
import Foundation
import Observation

/// Holds the cellar for the UI, persists every change and drives app-wide
/// presentation (sheets, undo banner).
@MainActor
@Observable
final class CellarModel {
    private(set) var cellar = Cellar()
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
    /// What the Journal shows; back to everything when a new entry would be hidden.
    var journalFilter: DiaryFilter = .all
    /// The Journal's tag chip; cleared, like the filter, when a new entry would be hidden.
    var journalTag: String?

    @ObservationIgnored private var store: any CellarStore = InMemoryCellarStore()
    /// Sync with the user's other devices. Replaced when the cellar loads late (`reloadIfLoadFailed`).
    var sync: SyncService {
        _ = syncVersion
        return syncService
    }
    @ObservationIgnored private var syncService: SyncService!
    private var syncVersion = 0
    /// Told about every local change (old and new cellar), to record it for sync.
    @ObservationIgnored var onLocalChange: ((Cellar, Cellar) -> Void)?

    /// The cellar file could not be read at launch: where it was kept (see `unreadableFiles`).
    @ObservationIgnored private(set) var unreadableFile: URL?
    /// The cellar file could not be loaded at all (for example before the first unlock
    /// after a restart, when iOS may launch the app in the background): the store to try
    /// again. Until then the cellar is empty, in memory, and sync and reminders stay off.
    @ObservationIgnored private var storeToRetry: JSONFileCellarStore?
    var loadFailed: Bool { storeToRetry != nil }

    /// Previous versions, Back Up Now and the weekly archive.
    @ObservationIgnored let backups: BackupCenter

    /// `demo`: screen tour and previews, where sync never touches the network or files.
    /// `versions`: earlier versions of the cellar when the store keeps none (previews).
    init(store: any CellarStore, demo: Bool = true, versions: (any CellarVersionSource)? = nil) {
        let folder: (any CellarVersionSource)? = (store as? JSONFileCellarStore)?.versions.map { $0 as any CellarVersionSource }
        backups = BackupCenter(versions: folder ?? versions ?? InMemoryCellarVersions([]), demo: demo)
        load(store, demo: demo)
    }

    private func load(_ store: any CellarStore, demo: Bool) {
        do {
            if let file = store as? JSONFileCellarStore {
                // A file that cannot be read is moved aside first, so it is never saved over.
                let result = try file.loadSettingAsideUnreadable()
                cellar = result.cellar
                unreadableFile = result.setAside
            } else {
                cellar = try store.load()
            }
        } catch {
            // Tried again and still unreadable: everything stays as it is.
            guard storeToRetry == nil else { return }
            // Not even moved aside: nothing is saved, and sync stays off, until it loads.
            cellar = Cellar()
            self.store = InMemoryCellarStore()
            storeToRetry = store as? JSONFileCellarStore
            errorMessage = String(localized: "Your cellar could not be loaded. Nothing is saved until Encave is opened again.")
            syncService = SyncService(model: self, demo: true)
            syncVersion += 1
            return
        }
        self.store = store
        storeToRetry = nil
        errorMessage = nil
        if let fileName = unreadableFile?.lastPathComponent {
            errorMessage = String(localized: "Your cellar file could not be read. It was kept as “\(fileName)” and Encave started with an empty cellar. Devices that sync with this one bring their copy back. To keep the file elsewhere, share it from Settings › Your data.")
        }
        syncService = SyncService(model: self, demo: demo)
        syncVersion += 1
    }

    /// Loads the cellar again if it could not be loaded at launch, now that the device may
    /// be unlocked; then sync starts as after a normal launch. Changes made meanwhile were
    /// never saved, as the alert said.
    func reloadIfLoadFailed() {
        guard let file = storeToRetry else { return }
        load(file, demo: false)
        guard !loadFailed else { return }
        // An Undo would apply to the cellar now loaded.
        toast = nil
        #if os(iOS)
        sync.scheduleBackgroundSync()
        #endif
    }

    /// The cellar file could not be read at launch and earlier versions are kept: one can be
    /// restored (offered until one is restored, or the offer is taken up).
    var offersRestoreAfterUnreadable: Bool {
        restoreOffered && unreadableFile != nil && !backups.versions.versions().isEmpty
    }

    private var restoreOffered = true

    /// Opens the preview of the newest previous version (after a cellar file could not be read).
    func openLatestVersion() {
        restoreOffered = false
        Task {
            guard let source = await backups.latestVersionSource() else { return }
            sheet = .restore(source)
        }
    }

    /// Restores a backup or an earlier version, with Undo. Photos and documents of an
    /// archive are copied first. Replace is a change made here like any other: sync takes
    /// it to the other devices (what is not in the backup is deleted there too).
    @discardableResult
    func restore(_ source: RestoreSource, mode: RestoreMode) async -> Bool {
        let files = source.files
        if !files.isEmpty { await Task.detached(priority: .userInitiated) { BackupCenter.install(files) }.value }
        let snapshot = cellar
        var merged = cellar
        let summary = merged.merge(source.cellar)
        let restored = cellar.restoring(source.cellar, mode: mode)
        guard update({ $0 = restored }) else { return false }
        restoreOffered = false
        if let folder = source.workingFolder { try? FileManager.default.removeItem(at: folder) }
        successCount += 1
        let message: String
        switch mode {
        case .replace:
            message = String(localized: "Cellar restored from the backup")
        case .addMissing:
            let discoveriesAdded = merged.discoveries.count - snapshot.discoveries.count
            message = SettingsView.importMessage(summary, discoveriesAdded: discoveriesAdded)
        }
        if restored == snapshot {
            showToast(message)
        } else {
            showToast(message) { [weak self] in self?.update { $0 = snapshot } }
        }
        return true
    }

    /// Cellar files set aside because they could not be read, to share or delete in Settings.
    var unreadableFiles: [URL] {
        _ = unreadableFilesVersion
        return (store as? JSONFileCellarStore)?.unreadableFiles() ?? []
    }

    private var unreadableFilesVersion = 0

    func deleteUnreadableFile(_ url: URL) {
        guard unreadableFiles.contains(url) else { return }
        try? FileManager.default.removeItem(at: url)
        unreadableFilesVersion += 1
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
            // Photo and document files nothing refers to are removed after a grace period
            // (PhotoStore), and those of the previous versions are kept too; not while a
            // cellar file that could not be read is kept, as its files may be needed.
            if model.errorMessage == nil, store.unreadableFiles().isEmpty {
                let refs = model.cellar.fileRefs, versions = store.versions
                Task.detached(priority: .background) {
                    let kept = refs.union(versions?.fileRefs() ?? [])
                    PhotoStore.removeOrphans(keeping: Set(kept.filter { $0.kind == .photo }.map(\.id)))
                    DocumentStore.removeOrphans(keeping: Set(kept.filter { $0.kind == .document }.map(\.id)))
                }
            }
            DocumentStore.removeTemporaryCopies()
            BackupCenter.removeTemporaryFiles()
            return model
        }
        return CellarModel(store: InMemoryCellarStore())
    }

    static func preview() -> CellarModel {
        let sample = SampleData.cellar(labels: .localized)
        return CellarModel(store: InMemoryCellarStore(sample), versions: DemoVersions.make(from: sample))
    }

    var currentYear: Int { CellarYear.current }

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
            if !undoing { dropStaleUndo() }
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
            dropStaleUndo()
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

    @discardableResult
    func drink(_ bottleID: UUID, tasting: Tasting) -> Bool {
        guard let bottle = cellar.bottles[bottleID], let wine = cellar.wines[bottle.wineID] else {
            errorMessage = CellarError.unknownBottle.localizedMessage
            return false
        }
        let previousSlot = bottle.slot
        guard update({ try $0.drink(bottleID, tasting: tasting) }) else { return false }
        if !journalFilter.showsTastings { journalFilter = .all }
        // A tag only picks wines tasted elsewhere.
        journalTag = nil
        successCount += 1
        showToast(String(localized: "Enjoy your \(wine.displayName)")) { [weak self] in
            self?.update { $0.undoDrink(bottleID, previousSlot: previousSlot) }
        }
        return true
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

    /// Deletes a rack, with Undo. Its bottles stay in the cellar without a place.
    func deleteRack(_ rackID: UUID) {
        let snapshot = cellar
        guard let rack = cellar.rack(rackID), update({ $0.removeRack(rackID) }) else { return }
        let rackName = rack.name
        showToast(String(localized: "Deleted \(rackName)")) { [weak self] in
            self?.update { $0 = snapshot }
        }
    }

    /// A bottle logged as opened by mistake goes back to the cellar, without its tasting, with Undo.
    func putBack(_ bottleID: UUID) {
        guard let bottle = cellar.bottles[bottleID], bottle.status != .inCellar,
              update({ $0.undoDrink(bottleID, previousSlot: nil) }) else { return }
        showToast(String(localized: "Bottle put back in the cellar")) { [weak self] in
            self?.update { cellar in
                // As it was, unless it changed meanwhile.
                guard cellar.bottles[bottleID]?.status == .inCellar else { return }
                cellar.bottles[bottleID] = bottle
            }
        }
    }

    func addDiscovery(_ discovery: Discovery, wine: Wine) {
        guard update({ $0.addDiscovery(discovery, wine: wine) }) else { return }
        successCount += 1
        if journalFilter == .cellar || (journalFilter == .toBuy && !discovery.wantsToBuy) { journalFilter = .all }
        if let tag = journalTag, !discovery.tags.contains(where: { $0.caseInsensitiveCompare(tag) == .orderedSame }) {
            journalTag = nil
        }
        showToast(discovery.wantsToBuy
                  ? String(localized: "Added to your shopping list")
                  : String(localized: "Saved to your journal"))
    }

    func deleteDiscovery(_ id: UUID) {
        let snapshot = cellar
        guard update({ $0.deleteDiscovery(id) }) else { return }
        showToast(String(localized: "Deleted from your journal")) { [weak self] in
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

    /// Windows in the foreground: sync pauses only when the last one goes to the background.
    @ObservationIgnored private var activeScenes = 0

    func sceneDidBecomeActive() {
        activeScenes += 1
        // The weekly archive, when it is due.
        let snapshot = cellar
        Task { await backups.runAutomaticIfDue(cellar: snapshot) }
    }

    func sceneDidEnterBackground() {
        activeScenes = max(0, activeScenes - 1)
        guard activeScenes == 0 else { return }
        sync.pause()
        #if os(iOS)
        sync.scheduleBackgroundSync()
        #endif
    }

    func showInRack(_ bottle: Bottle) {
        guard let slot = bottle.slot else { return }
        highlightedSlot = slot
        selectedTab = .racks
    }

    /// The sample cellar replaces everything, so it is offered only for an empty cellar
    /// that no other device syncs with.
    var canLoadSample: Bool { cellar.isEmpty && sync.otherDevices.isEmpty }

    func loadSample() {
        guard canLoadSample else { return }
        let snapshot = cellar
        var sample = SampleData.cellar(labels: .localized)
        sample.settings = snapshot.settings
        guard update({ $0 = sample }) else { return }
        showToast(String(localized: "Sample cellar loaded")) { [weak self] in
            self?.update { $0 = snapshot }
        }
    }

    /// Erases the cellar but keeps the settings (currency, saved searches). Synced
    /// like any change: the other devices of the cellar erase it too.
    func eraseAll() {
        let snapshot = cellar
        guard update({ $0 = Cellar(settings: snapshot.settings) }) else { return }
        showToast(String(localized: "All data erased")) { [weak self] in
            self?.update { $0 = snapshot }
        }
    }

    /// The AI answered after the wine was saved: fills the fields still as they were
    /// at saving (the user may have edited others since), with Undo.
    func completeWine(_ id: UUID, from before: Wine, to after: Wine) {
        guard let live = cellar.wines[id] else { return }
        var updated = live
        func take<Value: Equatable>(_ field: WritableKeyPath<Wine, Value>) {
            if live[keyPath: field] == before[keyPath: field] { updated[keyPath: field] = after[keyPath: field] }
        }
        take(\.name); take(\.producer); take(\.vintage); take(\.color); take(\.appellation)
        take(\.region); take(\.country); take(\.grapes); take(\.drinkFrom); take(\.drinkUntil)
        guard updated != live, update({ try $0.updateWine(updated) }) else { return }
        let completed = updated
        showToast(String(localized: "Details completed by AI: \(completed.displayName)")) { [weak self] in
            self?.update { cellar in
                guard var wine = cellar.wines[id] else { return }
                func restore<Value: Equatable>(_ field: WritableKeyPath<Wine, Value>) {
                    if wine[keyPath: field] == completed[keyPath: field] { wine[keyPath: field] = live[keyPath: field] }
                }
                restore(\.name); restore(\.producer); restore(\.vintage); restore(\.color); restore(\.appellation)
                restore(\.region); restore(\.country); restore(\.grapes); restore(\.drinkFrom); restore(\.drinkUntil)
                try cellar.updateWine(wine)
            }
        }
    }

    func showToast(_ message: String, undo: (@MainActor () -> Void)? = nil) {
        var toast = Toast(message: message)
        if let undo {
            let id = toast.id
            toast.undo = ToastUndo { [weak self] in
                self?.undoing = true
                undo()
                self?.undoing = false
                if self?.toast?.id == id { self?.toast = nil }
            }
        }
        self.toast = toast
    }

    /// True while a banner's Undo runs, so its own change does not cancel it.
    @ObservationIgnored private var undoing = false

    /// Some undos put back a whole earlier cellar: once anything else has changed
    /// (here or by sync), the banner and its Undo go away so nothing newer is lost.
    private func dropStaleUndo() {
        guard let undo = toast?.undo else { return }
        undo.cancel()
        toast = nil
    }
}

enum AppTab: String, Hashable {
    case cellar, racks, tonight, journal
}

/// Sheets presented at the root so any screen can open them.
enum AppSheet: Identifiable {
    case addBottle(AddBottleContext)
    /// Open a bottle (nil: choose it first, from the Journal).
    case drink(bottleID: UUID?)
    /// Edit a wine, scrolled to one of its fields when given.
    case editWine(wineID: UUID, field: WineFormField? = nil)
    case editTasting(bottleID: UUID)
    case editRack(rackID: UUID?)
    case moveBottle(bottleID: UUID)
    /// Choose the picture shown for a wine.
    case wineImage(wineID: UUID)
    /// Remember a wine found elsewhere (nil: a new one).
    case discovery(discoveryID: UUID?)
    /// The documents of one bottle; `importing` opens the file picker right away.
    case bottleDocuments(bottleID: UUID, importing: Bool)
    /// A backup or an earlier version, previewed before restoring it.
    case restore(RestoreSource)
    case settings

    var id: String {
        switch self {
        case .addBottle(let context): "add-\(context.id)"
        case .drink(let id): "drink-\(id?.uuidString ?? "choose")"
        case .editWine(let id, _): "wine-\(id)"
        case .editTasting(let id): "tasting-\(id)"
        case .editRack(let id): "rack-\(id?.uuidString ?? "new")"
        case .moveBottle(let id): "move-\(id)"
        case .wineImage(let id): "image-\(id)"
        case .discovery(let id): "discovery-\(id?.uuidString ?? "new")"
        case .bottleDocuments(let id, _): "documents-\(id)"
        case .restore(let source): "restore-\(source.id)"
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
    var undo: ToastUndo?
}

/// The Undo of a banner. It runs at most once, whether from the banner's button
/// or from the window's undo manager (⌘Z, Edit ▸ Undo, shake to undo).
@MainActor
final class ToastUndo: NSObject {
    private var action: (@MainActor () -> Void)?

    init(_ action: @escaping @MainActor () -> Void) {
        self.action = action
    }

    @objc func run() {
        let action = self.action
        self.action = nil
        action?()
    }

    func cancel() {
        action = nil
    }
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
        labels.largeFridge = String(localized: "Large wine fridge")
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
