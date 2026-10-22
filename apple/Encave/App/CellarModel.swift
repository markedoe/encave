import CaveKit
import Foundation
import Observation
#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

/// Holds the cellar for the UI, persists every change and drives app-wide
/// presentation (sheets, undo banner).
@MainActor
@Observable
final class CellarModel {
    private(set) var cellar = Cellar() {
        didSet {
            cachedBottlesInCellar = nil
            cachedOverview = nil
        }
    }
    var errorMessage: String?
    /// Short confirmation with an optional Undo, shown at the bottom.
    var toast: Toast?
    /// Bumped on successful actions, drives haptic feedback.
    var successCount = 0
    /// Bumped when something is switched on or off (the shopping list), for a lighter haptic.
    var selectionCount = 0
    /// Slot to highlight when jumping to a rack ("Show in rack").
    var highlightedSlot: Slot?
    /// What the Journal shows; back to everything when a new entry would be hidden.
    var journalFilter: DiaryFilter = .all
    /// The Journal's tag chip; cleared, like the filter, when a new entry would be hidden.
    var journalTag: String?
    /// The Cellar's search, filters and sort, shared by its list and its table.
    var cellarFilter = CellarFilter()
    var cellarSort: CellarSort = .name

    @ObservationIgnored private var store: any CellarStore = InMemoryCellarStore()
    /// Saves off the main thread, coalescing changes made during a write.
    @ObservationIgnored private var writer: CellarWriter?
    /// Number of the newest change given to `writer`.
    @ObservationIgnored private var generation = 0
    /// Sync with the user's other devices. Replaced when the cellar loads late (`reloadIfLoadFailed`).
    var sync: SyncService {
        _ = syncVersion
        return syncService
    }
    @ObservationIgnored private var syncService: SyncService!
    private var syncVersion = 0
    /// Told about every local change (old and new cellar), to record it for sync.
    @ObservationIgnored var onLocalChange: ((Cellar, Cellar) -> Void)?
    /// Told when a failed write took the cellar back (shown, then saved), to record it again.
    @ObservationIgnored var onSaveReverted: ((Cellar, Cellar) -> Void)?

    /// The cellar file could not be read at launch: where it was kept (see `unreadableFiles`).
    @ObservationIgnored private(set) var unreadableFile: URL?
    /// The cellar file could not be loaded at all (for example before the first unlock
    /// after a restart, when iOS may launch the app in the background): the store to try
    /// again. Until then the cellar is empty, in memory, and sync and reminders stay off.
    @ObservationIgnored private var storeToRetry: JSONFileCellarStore?
    var loadFailed: Bool { storeToRetry != nil }

    /// Previous versions, Back Up Now and the weekly archive.
    @ObservationIgnored let backups: BackupCenter
    /// Spotlight's on-device index of the wines.
    @ObservationIgnored private let spotlight = SpotlightIndex()

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
            startWriter()
            syncService = SyncService(model: self, demo: true)
            syncVersion += 1
            return
        }
        self.store = store
        storeToRetry = nil
        errorMessage = nil
        startWriter()
        if let fileName = unreadableFile?.lastPathComponent {
            errorMessage = String(localized: "Your cellar file could not be read. It was kept as “\(fileName)” and Encave started with an empty cellar. Devices that sync with this one bring their copy back. To keep the file elsewhere, share it from Settings › Cellar files that could not be read.")
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
        indexInSpotlight()
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

    // MARK: Windows

    /// The router of the window used last (each window has its own, see `SceneRouter`).
    @ObservationIgnored private weak var activeRouter: SceneRouter?
    /// Asked while no window was open (Mac), or before the first one appeared at launch:
    /// done by the next window that becomes active.
    @ObservationIgnored private var pendingAction: SceneAction?

    /// Shows something in the window used last, or in the next one to open. For requests
    /// from outside a window (Spotlight, Shortcuts, notifications, the menu bar); screens
    /// use their own window's router.
    func request(_ action: SceneAction) {
        if let activeRouter {
            activeRouter.perform(action)
        } else {
            pendingAction = action
        }
    }

    /// A window came to the front: requests go to it from now on.
    func sceneBecameActive(_ router: SceneRouter) {
        activeRouter = router
        if let action = pendingAction {
            pendingAction = nil
            router.perform(action)
        }
    }

    /// A window closed.
    func sceneClosed(_ router: SceneRouter) {
        if activeRouter === router { activeRouter = nil }
    }

    /// The sheet of the window used last. Screens use their window's router instead.
    var sheet: AppSheet? {
        get { activeRouter?.sheet }
        set {
            if let newValue { request(.present(newValue)) } else { activeRouter?.sheet = nil }
        }
    }

    /// The section of the window used last. Screens use their window's router instead.
    var selectedTab: AppTab {
        get { activeRouter?.selectedTab ?? .cellar }
        set { request(.show(newValue)) }
    }

    /// Asked by Spotlight, Shortcuts, a notification or the menu bar: shown in the window
    /// used last once its sheet, if any, is closed (a form is never closed for it).
    func open(wine id: UUID) {
        guard cellar.wines[id] != nil else { return }
        request(.openWine(id))
    }

    static func live() -> CellarModel {
        let model = makeLive()
        current = model
        model.indexInSpotlight()
        #if os(iOS)
        let quitting = UIApplication.willTerminateNotification
        #elseif os(macOS)
        let quitting = NSApplication.willTerminateNotification
        #endif
        _ = NotificationCenter.default.addObserver(forName: quitting, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { model.flushBeforeExit() }
        }
        return model
    }

    private static func makeLive() -> CellarModel {
        // Launch test in CI: sample data in memory, never written to disk.
        let arguments = ProcessInfo.processInfo.arguments
        if arguments.contains("--sample-data") {
            let model = preview(labelPhotos: true)
            if let index = arguments.firstIndex(of: "--tab"), arguments.indices.contains(index + 1),
               let tab = AppTab(rawValue: arguments[index + 1]) {
                model.request(.show(tab))
            }
            #if os(macOS)
            MacScreenshotMode.startIfAsked()
            #endif
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

    /// The sample cellar in memory; with `labelPhotos` (the screen tour), its wines
    /// get label photos drawn on the device and kept in memory.
    static func preview(labelPhotos: Bool = false) -> CellarModel {
        var sample = SampleData.cellar(labels: .localized)
        if labelPhotos { SamplePhotos.attach(to: &sample) }
        return CellarModel(store: InMemoryCellarStore(sample), versions: DemoVersions.make(from: sample))
    }

    var currentYear: Int { CellarYear.current }

    @ObservationIgnored private var cachedBottlesInCellar: [Bottle]?
    @ObservationIgnored private var cachedOverview: CellarOverview?

    /// The cellar's bottles in the cellar, kept until the cellar changes (views read it often).
    var bottlesInCellar: [Bottle] {
        let cellar = self.cellar
        if let cachedBottlesInCellar { return cachedBottlesInCellar }
        let bottles = cellar.bottlesInCellar
        cachedBottlesInCellar = bottles
        return bottles
    }

    /// Figures and filter chip values of the Cellar screen, kept until the cellar or the
    /// year changes. Its month by month figures are those of when it was worked out: the
    /// Insights screen computes its own.
    var overview: CellarOverview {
        let cellar = self.cellar
        let year = currentYear
        if let cachedOverview, cachedOverview.year == year { return cachedOverview }
        let overview = CellarOverview(cellar, year: year)
        cachedOverview = overview
        return overview
    }

    /// Applies a change and saves it. The change is checked here; an invalid one changes
    /// nothing and the error is shown. The file is written in the background (`save`).
    @discardableResult
    func update(_ change: (inout Cellar) throws -> Void) -> Bool {
        var copy = cellar
        do {
            try change(&copy)
            guard copy != cellar else { return true }
            save(copy)
            let old = cellar
            cellar = copy
            if !undoing { dropStaleUndo() }
            onLocalChange?(old, copy)
            indexInSpotlight()
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
        save(synced)
        cellar = synced
        dropStaleUndo()
        indexInSpotlight()
    }

    // MARK: Saving

    /// A writer for the store, starting from the cellar just loaded.
    private func startWriter() {
        generation += 1
        writer = CellarWriter(store: store, saved: cellar, generation: generation) { [weak self] lastSaved, _ in
            await self?.saveFailed(lastSaved: lastSaved) ?? .max
        }
    }

    /// Queues the cellar to be written; the newest one wins if several wait.
    private func save(_ cellar: Cellar) {
        generation += 1
        let number = generation
        guard let writer else { return }
        Task { await writer.submit(cellar, generation: number) }
    }

    /// A write failed: the cellar goes back to what the file holds, and the error is shown.
    /// Returns the newest change number, so changes sent before this are not written.
    private func saveFailed(lastSaved: Cellar) -> Int {
        errorMessage = String(localized: "Your last changes could not be saved, so your cellar is shown as it was last saved. Check the free space on this device.")
        if cellar != lastSaved {
            let shown = cellar
            cellar = lastSaved
            // Sync recorded the changes as they were made: it records them again as saved.
            onSaveReverted?(shown, lastSaved)
            // Every banner's Undo was about changes now gone.
            toast?.undo?.cancel()
            toast = nil
        }
        return generation
    }

    /// Writes what is still waiting, when the app goes to the background
    /// (on iPhone and iPad, iOS is asked for the time it takes).
    func flushSaves() {
        guard let writer else { return }
        let latest = cellar, number = generation
        #if os(iOS)
        let task = BackgroundTask(name: "Save cellar")
        Task {
            await writer.submit(latest, generation: number)
            await writer.flush()
            task.end()
        }
        #else
        Task {
            await writer.submit(latest, generation: number)
            await writer.flush()
        }
        #endif
    }

    /// The app quits: waits a moment for the last write, as nothing runs afterwards.
    func flushBeforeExit() {
        guard let writer else { return }
        let latest = cellar, number = generation
        let done = DispatchSemaphore(value: 0)
        Task.detached {
            await writer.submit(latest, generation: number)
            await writer.flush()
            done.signal()
        }
        _ = done.wait(timeout: .now() + 3)
    }

    // MARK: - Actions with feedback

    /// Adds bottles, with Undo. `discoveryID`: a wine of the shopping list, marked bought
    /// in the same change (so the Undo takes both back).
    @discardableResult
    func addBottles(of wine: Wine, quantity: Int, slots: [Slot], purchase: PurchaseInfo, discoveryID: UUID? = nil) -> Bool {
        let previousWine = cellar.wines[wine.id]
        let previousDiscovery = discoveryID.flatMap { cellar.discoveries[$0] }
        var added: [Bottle] = []
        let ok = update { cellar in
            added = try cellar.addBottles(of: wine, quantity: quantity, slots: slots, purchase: purchase)
            if let discoveryID, cellar.discoveries[discoveryID] != nil { try cellar.markBought(discoveryID) }
        }
        guard ok else { return false }
        successCount += 1
        let bottleIDs = added.map(\.id)
        let revert: (inout Cellar) -> Void = { cellar in
            // A new wine left without bottles goes with them; a wine that was there
            // gets back its record as it was (barcodes, photo, documents added here).
            bottleIDs.forEach { cellar.deleteBottle($0) }
            if let previousWine { cellar.wines[previousWine.id] = previousWine }
            if let previousDiscovery { cellar.discoveries[previousDiscovery.id] = previousDiscovery }
        }
        showToast(purchase.isOrdered
                  ? String(localized: "On order: \(quantity) × \(wine.displayName)")
                  : String(localized: "Added \(quantity) × \(wine.displayName)")) { [weak self] in
            self?.update(revert)
        }
        if let toast {
            lastAdd = AddBanner(toastID: toast.id, wineID: wine.id, quantity: quantity, isOrdered: purchase.isOrdered, revert: revert)
        }
        return true
    }

    /// The banner of the last add, so the AI completing that wine while it is still shown
    /// takes its place with one Undo for both, instead of taking the add's Undo away.
    private struct AddBanner {
        var toastID: UUID
        var wineID: UUID
        var quantity: Int
        var isOrdered: Bool
        var revert: (inout Cellar) -> Void
    }

    @ObservationIgnored private var lastAdd: AddBanner?

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
                  : String(localized: "Bottle removed from the cellar"), lasting: .afterChanges) { [weak self] in
            // Put the record back as it was (an order stays an order), in its
            // place if nothing took it meanwhile.
            self?.update { cellar in
                var restored = bottle
                if let slot = bottle.slot, cellar.bottle(at: slot) != nil { restored.slot = nil }
                cellar.bottles[bottleID] = restored
            }
        }
    }

    /// Deletes a wine with its bottles, tastings and wines tasted elsewhere. The Undo stays
    /// longer, and after changes from sync: it puts back only these records.
    func deleteWine(_ wineID: UUID) {
        guard let wine = cellar.wines[wineID] else { return }
        let removed = records(ofWines: [wineID])
        guard update({ $0.deleteWine(wineID) }) else { return }
        showToast(String(localized: "Deleted \(wine.displayName)"), lasting: .long) { [weak self] in
            self?.update { _ = $0.merge(removed) }
        }
    }

    /// A bottle entered by mistake: its record goes (and the wine with its last one), with Undo.
    func deleteBottle(_ bottleID: UUID) {
        guard let bottle = cellar.bottles[bottleID] else { return }
        var removed = Cellar()
        removed.wines[bottle.wineID] = cellar.wines[bottle.wineID]
        removed.bottles[bottleID] = bottle
        guard update({ $0.deleteBottle(bottleID) }) else { return }
        showToast(String(localized: "Bottle deleted"), lasting: .afterChanges) { [weak self] in
            self?.update { _ = $0.merge(removed) }
        }
    }

    /// The records deleted with these wines, to put back with `merge`.
    private func records(ofWines wineIDs: Set<UUID>) -> Cellar {
        var removed = Cellar()
        removed.wines = cellar.wines.filter { wineIDs.contains($0.key) }
        removed.bottles = cellar.bottles.filter { wineIDs.contains($0.value.wineID) }
        removed.discoveries = cellar.discoveries.filter { wineIDs.contains($0.value.wineID) }
        return removed
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

    /// Bottles no longer in the cellar (given away, sold, broken).
    func markGone(_ bottleIDs: Set<UUID>) {
        let previous = bottleIDs.compactMap { cellar.bottles[$0] }
        guard update({ cellar in try bottleIDs.forEach { try cellar.markGone($0) } }) else { return }
        showToast(String(localized: "Bottles removed from the cellar: \(bottleIDs.count)"),
                  lasting: bottleIDs.count > 1 ? .long : .afterChanges) { [weak self] in
            // The records as they were, in their places if nothing took them meanwhile.
            self?.update { cellar in
                for bottle in previous.sorted(by: { ($0.slot == nil ? 0 : 1) < ($1.slot == nil ? 0 : 1) }) {
                    var restored = bottle
                    if let slot = bottle.slot, let occupant = cellar.bottle(at: slot), occupant.id != bottle.id { restored.slot = nil }
                    cellar.bottles[bottle.id] = restored
                }
            }
        }
    }

    func deleteWines(_ wineIDs: Set<UUID>) {
        let removed = records(ofWines: wineIDs)
        guard update({ cellar in wineIDs.forEach { cellar.deleteWine($0) } }) else { return }
        showToast(String(localized: "Wines deleted: \(wineIDs.count)"), lasting: .long) { [weak self] in
            self?.update { _ = $0.merge(removed) }
        }
    }

    /// Deletes a rack, with Undo. Its bottles stay in the cellar without a place.
    func deleteRack(_ rackID: UUID) {
        guard let index = cellar.racks.firstIndex(where: { $0.id == rackID }) else { return }
        let rack = cellar.racks[index]
        let placed = bottlesInCellar.filter { $0.slot?.rackID == rackID }
        guard update({ $0.removeRack(rackID) }) else { return }
        let rackName = rack.name
        // Only its own records come back, so the Undo can stay after other changes.
        showToast(String(localized: "Deleted \(rackName)"), lasting: .long) { [weak self] in
            self?.update { cellar in
                guard cellar.rack(rackID) == nil else { return }
                cellar.addRack(rack)
                let last = cellar.racks.count - 1
                if index < last { cellar.moveRacks(fromOffsets: IndexSet(integer: last), toOffset: index) }
                CellarModel.putBack(placed, in: &cellar)
            }
        }
    }

    /// Bottles back in the places they had, when they are in the cellar without a place
    /// and the place is free (the Undo of a change to a rack).
    private static func putBack(_ bottles: [Bottle], in cellar: inout Cellar) {
        for bottle in bottles {
            guard let slot = bottle.slot, let now = cellar.bottles[bottle.id], now.status == .inCellar,
                  now.slot == nil, cellar.bottle(at: slot) == nil, cellar.rack(slot.rackID)?.contains(slot) == true
            else { continue }
            cellar.bottles[bottle.id]?.slot = slot
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

    /// Puts a wine tasted elsewhere on the shopping list or takes it off, with Undo.
    func toggleToBuy(_ id: UUID) {
        guard let before = cellar.discoveries[id] else { return }
        guard update({ cellar in
            cellar.discoveries[id]?.wantsToBuy.toggle()
            if cellar.discoveries[id]?.wantsToBuy == true { cellar.discoveries[id]?.boughtAt = nil }
        }) else { return }
        selectionCount += 1
        showToast(before.wantsToBuy
                  ? String(localized: "Removed from your shopping list")
                  : String(localized: "Added to your shopping list")) { [weak self] in
            self?.update { cellar in
                guard cellar.discoveries[id] != nil else { return }
                cellar.discoveries[id]?.wantsToBuy = before.wantsToBuy
                cellar.discoveries[id]?.boughtAt = before.boughtAt
            }
        }
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

    /// Moves a bottle to a place, with Undo back to where it was.
    func move(_ bottleID: UUID, to slot: Slot) {
        guard let before = cellar.bottles[bottleID], update({ try $0.move(bottleID, to: slot) }) else { return }
        successCount += 1
        guard let rack = cellar.rack(slot.rackID) else { return }
        let rackName = rack.name
        let place = rack.displayLabel(for: slot)
        showToast(String(localized: "Moved to \(rackName) · \(place)"), lasting: .afterChanges) { [weak self] in self?.moveBack(before) }
    }

    /// Takes a bottle out of the racks, noting where it is now, with Undo back to its place.
    func takeOutOfRack(_ bottleID: UUID, note: String?) {
        guard let before = cellar.bottles[bottleID] else { return }
        let ok = update { cellar in
            try cellar.move(bottleID, to: nil)
            if var updated = cellar.bottles[bottleID] {
                updated.storageNote = note
                try cellar.updateBottle(updated)
            }
        }
        guard ok, before.slot != nil else { return }
        successCount += 1
        showToast(String(localized: "Bottle taken out of the rack"), lasting: .afterChanges) { [weak self] in self?.moveBack(before) }
    }

    /// Puts a bottle back in the place and with the note it had (the Undo of a move).
    private func moveBack(_ before: Bottle) {
        update { cellar in
            guard var bottle = cellar.bottles[before.id], bottle.status == .inCellar else { return }
            bottle.slot = before.slot
            bottle.storageNote = before.storageNote
            try cellar.updateBottle(bottle)
        }
    }

    /// Swaps the bottles of two places, with Undo (swapping them again).
    func swap(_ a: Slot, _ b: Slot) {
        guard update({ try $0.swap(a, b) }) else { return }
        successCount += 1
        showToast(String(localized: "Bottles swapped"), lasting: .afterChanges) { [weak self] in
            self?.update { try $0.swap(a, b) }
        }
    }

    /// Saves an edited rack, moving its bottles by `rowMap`. Bottles whose place is gone
    /// are taken out (the person agreed first), with a longer Undo.
    @discardableResult
    func updateRack(_ rack: Rack, rowMap: [Int: Int?], takingOut count: Int) -> Bool {
        let old = cellar.rack(rack.id)
        let inRack = bottlesInCellar.filter { $0.slot?.rackID == rack.id }
        guard update({ try $0.updateRack(rack, rowMap: rowMap, takingOut: count > 0) }) else { return false }
        if count > 0, let old {
            // Only this rack and its bottles come back, so the Undo can stay after other changes.
            showToast(String(localized: "Bottles taken out of their places: \(count)"), lasting: .long) { [weak self] in
                self?.update { cellar in
                    guard cellar.rack(old.id) != nil else { return }
                    // Out of their places first, so the rack can take its old shape, then back in.
                    for bottle in inRack where cellar.bottles[bottle.id]?.status == .inCellar {
                        cellar.bottles[bottle.id]?.slot = nil
                    }
                    try cellar.updateRack(old, takingOut: true)
                    CellarModel.putBack(inRack, in: &cellar)
                }
            }
        }
        return true
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
        flushSaves()
        sync.pause()
        #if os(iOS)
        sync.scheduleBackgroundSync()
        #endif
        spotlight.flush()
    }

    /// Spotlight follows the cellar, a while after the last change; never for the sample
    /// cellar of the screen tour, nor for a cellar that could not be loaded.
    private func indexInSpotlight() {
        guard !sync.isDemo, !loadFailed else { return }
        spotlight.schedule(cellar)
    }

    /// Racks in the window used last. Screens use their window's router instead.
    func showInRack(_ bottle: Bottle) {
        activeRouter?.showInRack(bottle, model: self)
    }

    /// The sample cellar replaces everything, so it is offered only for an empty cellar
    /// that no other device syncs with, and only in beta builds (AppChannel).
    var canLoadSample: Bool { AppChannel.isBeta && cellar.isEmpty && sync.otherDevices.isEmpty }

    /// Records of the sample cellar are still here: the Cellar offers to remove them
    /// (not in the screen tour, whose cellar is the sample).
    var showsSampleBanner: Bool { !sync.isDemo && cellar.containsSample }

    /// Removes the sample's racks, wines, bottles and Journal entries, and only them, with Undo.
    func removeSample() {
        let snapshot = cellar
        guard snapshot.containsSample, update({ $0 = $0.removingSample() }) else { return }
        showToast(String(localized: "Sample cellar removed")) { [weak self] in
            self?.update { $0 = snapshot }
        }
    }

    func loadSample() {
        guard canLoadSample else { return }
        let snapshot = cellar
        var sample = SampleData.cellar(labels: .localized)
        sample.settings = snapshot.settings
        guard update({ $0 = sample }) else { return }
        // Undo takes only the sample away, whatever was added meanwhile.
        showToast(String(localized: "Sample cellar loaded")) { [weak self] in
            self?.update { $0 = $0.removingSample() }
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
        take(\.aiCompletedAt)
        guard updated != live else { return }
        // The bottles' "Added" banner still shown: one banner, whose Undo takes both back.
        let shownAdd = lastAdd.flatMap { $0.wineID == id && toast?.id == $0.toastID && toast?.undo != nil ? $0 : nil }
        // Its own change keeps that banner, which the new one replaces just below.
        if shownAdd != nil { undoing = true }
        let saved = update { try $0.updateWine(updated) }
        undoing = false
        guard saved else { return }
        let completed = updated
        let revert: (inout Cellar) -> Void = { cellar in
            guard var wine = cellar.wines[id] else { return }
            func restore<Value: Equatable>(_ field: WritableKeyPath<Wine, Value>) {
                if wine[keyPath: field] == completed[keyPath: field] { wine[keyPath: field] = live[keyPath: field] }
            }
            restore(\.name); restore(\.producer); restore(\.vintage); restore(\.color); restore(\.appellation)
            restore(\.region); restore(\.country); restore(\.grapes); restore(\.drinkFrom); restore(\.drinkUntil)
            restore(\.aiCompletedAt)
            cellar.wines[id] = wine
        }
        guard let add = shownAdd else {
            showToast(String(localized: "Details completed by AI: \(completed.displayName)")) { [weak self] in
                self?.update(revert)
            }
            return
        }
        lastAdd = nil
        toast?.undo?.cancel()
        let name = completed.displayName
        showToast(add.isOrdered
                  ? String(localized: "On order: \(add.quantity) × \(name), details completed by AI")
                  : String(localized: "Added \(add.quantity) × \(name), details completed by AI")) { [weak self] in
            self?.update { cellar in
                revert(&cellar)
                add.revert(&cellar)
            }
        }
    }

    func showToast(_ message: String, lasting: Toast.Lasting = .standard, undo: (@MainActor () -> Void)? = nil) {
        var toast = Toast(message: message, lasting: lasting)
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
    /// Undos that put back only their own records (`.long`, `.afterChanges`) stay.
    private func dropStaleUndo() {
        guard let current = toast, let undo = current.undo, current.lasting == .standard else { return }
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
    /// Join a cellar with a pairing code from a link (the QR code scanned with the camera).
    case joinDevice(code: String, commitment: Data?)

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
        case .joinDevice(let code, _): "join-\(code)"
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
    /// Opens on the label scanner when the device has one (Cancel leaves the form).
    var scan = false
}

struct Toast: Identifiable {
    let id = UUID()
    var message: String
    var lasting: Lasting = .standard
    var undo: ToastUndo?

    enum Lasting {
        /// A few seconds; gone as soon as anything else changes (its Undo may restore a whole cellar).
        case standard
        /// Stays after other changes, here or from sync: its Undo puts back only its own records.
        case afterChanges
        /// Like `afterChanges`, and on screen longer: a large deletion.
        case long
    }

    /// Seconds on screen, longer with VoiceOver to leave time to reach Undo.
    func seconds(voiceOver: Bool) -> Int {
        switch lasting {
        case .standard, .afterChanges: voiceOver ? 12 : 5
        case .long: voiceOver ? 20 : 10
        }
    }
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

#if os(iOS)
/// Time iOS gives the app to finish a task after it goes to the background.
@MainActor
private final class BackgroundTask {
    private var id = UIBackgroundTaskIdentifier.invalid

    init(name: String) {
        id = UIApplication.shared.beginBackgroundTask(withName: name) { [weak self] in
            MainActor.assumeIsolated { self?.end() }
        }
    }

    func end() {
        guard id != .invalid else { return }
        UIApplication.shared.endBackgroundTask(id)
        id = .invalid
    }
}
#endif

extension CellarError {
    var localizedMessage: String {
        switch self {
        case .slotTaken: String(localized: "That place is already taken.")
        case .slotOutOfRange: String(localized: "That place is outside the rack.")
        case .rackNotEmpty: String(localized: "Some bottles are in places you removed.")
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
        labels.headToTailFridge = String(localized: "Head-to-tail fridge")
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
