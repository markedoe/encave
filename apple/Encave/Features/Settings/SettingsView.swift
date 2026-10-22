import CaveKit
import SwiftUI
import UniformTypeIdentifiers

struct SettingsView: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var confirmingErase = false
    @State private var confirmingSample = false
    @State private var deletingUnreadable: URL?
    @State private var exportDocument: ExportDocument?
    @State private var importing = false
    /// A backup the user picked is being read (a large archive takes a while).
    @State private var readingBackup = false
    @State private var importMessage: String?
    @State private var restoreSource: RestoreSource?
    @State private var remindersOn = Reminders.isEnabled
    #if os(macOS)
    @AppStorage(MenuBarSetting.key) private var showsMenuBarExtra = true
    #endif
    @AppStorage(StudioPhoto.settingKey) private var studioPhotos = true
    @State private var remindersRefused = false

    private static let currencies = ["EUR", "CHF", "USD", "GBP", "CAD", "AUD", "JPY", "SEK", "NOK", "DKK"]

    private var version: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "–"
        let build = info?["CFBundleVersion"] as? String ?? "–"
        return "\(short) (\(build)) · " + String(localized: "Beta")
    }

    private var remindersBinding: Binding<Bool> {
        Binding(get: { remindersOn }, set: { on in
            remindersOn = on
            if on {
                Task {
                    let granted = await Reminders.enable(for: model.cellar)
                    remindersOn = granted
                    remindersRefused = !granted
                    if !granted { Reminders.announceRefused() }
                }
            } else {
                Reminders.disable()
                remindersRefused = false
            }
        })
    }

    private var devicesSummary: String {
        let count = model.sync.otherDevices.count
        return count == 0 ? String(localized: "This device only") : String(localized: "\(count + 1) devices")
    }

    var body: some View {
        Form {
            Section {
                NavigationLink {
                    HelpView()
                } label: {
                    Label("Help", systemImage: "questionmark.circle")
                }
                .accessibilityIdentifier("settings.help")
            }

            Section {
                NavigationLink {
                    DevicesView()
                } label: {
                    LabeledContent {
                        SyncHealthReader { health in
                            if health.needsAttention {
                                SyncHealthLabel(health: health)
                            } else {
                                Text(devicesSummary)
                            }
                        }
                    } label: {
                        Label("Devices", systemImage: "laptopcomputer.and.iphone")
                    }
                }
                .accessibilityIdentifier("settings.devices")
            } footer: {
                Text("Sync your cellar with your other devices (iPhone, iPad, Mac or Android), encrypted from end to end.")
            }

            Section {
                Toggle(isOn: remindersBinding) {
                    Label("Reminders", systemImage: "bell.badge")
                }
                .accessibilityIdentifier("settings.reminders")
            } footer: {
                if remindersRefused {
                    Text("Notifications are off for Encave. Turn them on in the system settings.")
                } else {
                    Text("On the first of each month, if bottles should be drunk this year, have just become ready or are past their peak, and on the day bottles on order are expected. Worked out on this device.")
                }
            }

            #if os(macOS)
            Section {
                Toggle(isOn: $showsMenuBarExtra) {
                    Label("Show Tonight in the Menu Bar", systemImage: "menubar.arrow.up.rectangle")
                }
            } footer: {
                Text("A glass in the menu bar lists the bottles to open now; click one to open it.")
            }
            #endif

            Section {
                Toggle(isOn: $studioPhotos) {
                    Label("Studio Look for Label Photos", systemImage: "wand.and.stars")
                }
            } footer: {
                Text("Label photos are shown cut out and straightened on a clean background, made on this device. The photo you took is kept as is; turn this off to see it as taken.")
            }

            Section {
                Picker("Currency", selection: currencyBinding) {
                    // The cellar's currency is always listed, even if set elsewhere (import, another device).
                    let current = model.cellar.settings.currency
                    let codes = Self.currencies.contains(current) ? Self.currencies : Self.currencies + [current]
                    ForEach(codes, id: \.self) { code in
                        Text(Locale.current.localizedString(forCurrencyCode: code).map { "\($0) (\(code))" } ?? code).tag(code)
                    }
                }
            } header: {
                Text("Preferences")
            } footer: {
                Text("Used for new purchases. Prices already entered keep their currency.")
            }

            Section {
                BackupRows()
                Button {
                    exportCSV()
                } label: {
                    Label("Export Spreadsheet (CSV)", systemImage: "tablecells")
                }
                Button {
                    exportInventory()
                } label: {
                    Label("Inventory for Printing (PDF)", systemImage: "doc.richtext")
                }
                .disabled(model.cellar.bottlesInCellar.isEmpty)
                Button {
                    importing = true
                } label: {
                    if readingBackup {
                        Label {
                            Text("Reading the Backup…")
                        } icon: {
                            ProgressView()
                        }
                    } else {
                        Label("Import…", systemImage: "square.and.arrow.down")
                    }
                }
                .disabled(readingBackup)
            } header: {
                Text("Your data")
            } footer: {
                #if os(iOS)
                Text("A backup is a ZIP file with your whole cellar, its photos and documents; import it on any device: iPhone, iPad, Mac or Android. The last two weekly backups stay in the Files app, under On My iPhone (or On My iPad) › Encave. CSV opens in Numbers or Excel. The PDF lists every wine in your cellar with its places and purchase value, for insurance or a move.")
                #else
                Text("A backup is a ZIP file with your whole cellar, its photos and documents; import it on any device: Mac, iPhone, iPad or Android. CSV opens in Numbers or Excel. The PDF lists every wine in your cellar with its places and purchase value, for insurance or a move.")
                #endif
            }

            let unreadable = model.unreadableFiles
            if !unreadable.isEmpty {
                Section {
                    ForEach(unreadable, id: \.self) { url in
                        let fileName = url.lastPathComponent
                        ShareLink(item: url) {
                            Label("Share \(fileName)", systemImage: "square.and.arrow.up")
                        }
                        .contextMenu {
                            Button("Delete File", systemImage: "trash", role: .destructive) { deletingUnreadable = url }
                        }
                        .swipeActions {
                            Button("Delete", role: .destructive) { deletingUnreadable = url }
                        }
                        .accessibilityAction(named: Text("Delete File")) { deletingUnreadable = url }
                    }
                } header: {
                    Text("Cellar files that could not be read")
                    if model.offersRestoreAfterUnreadable {
                        Button {
                            Task { restoreSource = await model.backups.latestVersionSource() }
                        } label: {
                            Label("Restore Previous Version…", systemImage: "clock.arrow.circlepath")
                        }
                    }
                } footer: {
                    Text("Kept when Encave could not read your cellar at launch. Share one to keep it elsewhere or to report the problem; delete it when you no longer need it.")
                }
            }

            AISettingsSection()

            WebSearchSettingsSection()

            Section {
                if model.sync.remoteStorage != nil {
                    Label("Your cellar is kept on your devices, with an encrypted copy in the storage you chose for syncing away from home.",
                          systemImage: "lock.shield")
                } else if model.sync.otherDevices.isEmpty {
                    Label("Your cellar is kept on this device.", systemImage: "lock.shield")
                } else {
                    Label("Your cellar is kept on your devices.", systemImage: "lock.shield")
                }
            } header: {
                Text("Privacy")
            } footer: {
                Text("No account, no analytics, no advertising. Sync is encrypted from end to end: on the same Wi-Fi your devices connect directly, and away from home the storage you choose holds only encrypted files. If you turn them on, AI label recognition sends only the label photo to your AI service, and web search only the search terms to your search service.")
            }

            Section {
                if model.canLoadSample {
                    Button("Load sample cellar") { confirmingSample = true }
                }
                Button("Erase all data", role: .destructive) { confirmingErase = true }
                    .disabled(model.cellar.isEmpty)
            } header: {
                Text("Test data")
            } footer: {
                if model.canLoadSample {
                    Text("The sample cellar is offered while your cellar is empty and no other device syncs with this one.")
                }
            }

            Section {
                LabeledContent("Version", value: version)
                Link(destination: ProjectLinks.privacyPolicy) {
                    Label("Privacy Policy", systemImage: "hand.raised")
                }
                Link(destination: ProjectLinks.source) {
                    Label("Source code", systemImage: "chevron.left.forwardslash.chevron.right")
                }
                NavigationLink {
                    LicencesView()
                } label: {
                    Label("Licences", systemImage: "doc.text")
                }
            } header: {
                Text("About")
            } footer: {
                Text("Encave is in beta: features can still change and some bugs remain. Make a backup from time to time.")
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Settings")
        .onChange(of: scenePhase) { _, phase in
            // Back from the system settings: drop the message once notifications are allowed.
            guard phase == .active, remindersRefused else { return }
            Task { if await Reminders.notificationsAllowed() { remindersRefused = false } }
        }
        #if os(iOS)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Done") { dismiss() }
                        .accessibilityIdentifier("sheet.done")
            }
        }
        #endif
        .confirmationDialog("Erase all data?", isPresented: $confirmingErase, titleVisibility: .visible) {
            Button("Erase all data", role: .destructive) { model.eraseAll() }
        } message: {
            if model.sync.otherDevices.isEmpty {
                Text("This erases every bottle, wine, rack and Journal entry. Your settings, such as the currency and saved searches, are kept. You can undo right after.")
            } else {
                Text("This erases every bottle, wine, rack and Journal entry on this device and on the other devices that sync with it. Your settings, such as the currency and saved searches, are kept. You can undo right after.")
            }
        }
        .confirmationDialog("Load the sample cellar?", isPresented: $confirmingSample, titleVisibility: .visible) {
            Button("Load sample cellar") { model.loadSample() }
        } message: {
            Text("Example wines, racks and tastings to try Encave. Erase them later with Erase all data.")
        }
        .confirmationDialog(
            "Delete this file?",
            isPresented: Binding(get: { deletingUnreadable != nil }, set: { if !$0 { deletingUnreadable = nil } }),
            titleVisibility: .visible
        ) {
            Button("Delete File", role: .destructive) {
                if let deletingUnreadable { model.deleteUnreadableFile(deletingUnreadable) }
            }
        } message: {
            Text("The cellar it holds cannot be recovered afterwards.")
        }
        .fileExporter(
            isPresented: Binding(get: { exportDocument != nil }, set: { if !$0 { exportDocument = nil } }),
            document: exportDocument,
            contentType: exportDocument?.contentType ?? .json,
            defaultFilename: exportDocument?.filename
        ) { _ in
            exportDocument = nil
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.zip, .json, .commaSeparatedText, .plainText]) { result in
            handleImport(result)
        }
        .sheet(item: $restoreSource) { source in
            NavigationStack { RestorePreviewView(source: source, inSheet: true) }
                .environment(model)
                .macSheetFrame(width: 520, height: 640)
        }
        .alert("Import", isPresented: Binding(get: { importMessage != nil }, set: { if !$0 { importMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(importMessage ?? "")
        }
        .macSheetFrame(width: 520, height: 640)
    }

    private var currencyBinding: Binding<String> {
        Binding(
            get: { model.cellar.settings.currency },
            set: { code in model.update { $0.settings.currency = code } }
        )
    }

    private func exportCSV() {
        let date = Date.now.formatted(.iso8601.year().month().day())
        let data = Data(CellarExport.csv(model.cellar).utf8)
        exportDocument = ExportDocument(data: data, contentType: .commaSeparatedText, filename: "Encave \(date).csv")
    }

    private func exportInventory() {
        let date = Date.now.formatted(.iso8601.year().month().day())
        guard let data = InventoryPDF.make(for: model) else { return }
        exportDocument = ExportDocument(data: data, contentType: .pdf, filename: "Encave inventory \(date).pdf")
    }

    private func handleImport(_ result: Result<URL, Error>) {
        guard case .success(let url) = result else { return }
        // A backup (archive or JSON) is shown first, to add what is missing or replace the cellar.
        if ["zip", "json"].contains(url.pathExtension.lowercased()) {
            readingBackup = true
            Task {
                let accessing = url.startAccessingSecurityScopedResource()
                defer {
                    if accessing { url.stopAccessingSecurityScopedResource() }
                    readingBackup = false
                }
                do {
                    restoreSource = try await BackupCenter.source(forFileAt: url)
                } catch let error as ImportError {
                    importMessage = Self.message(for: error)
                } catch {
                    importMessage = String(localized: "The file could not be read.")
                }
            }
            return
        }
        let accessing = url.startAccessingSecurityScopedResource()
        defer { if accessing { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url) else {
            importMessage = String(localized: "The file could not be read.")
            return
        }
        // Read first: a file that cannot be read says so; a failed save shows its own error.
        var imported = model.cellar
        let summary: ImportSummary
        do {
            summary = try imported.importCSV(String(decoding: data, as: UTF8.self))
        } catch {
            importMessage = String(localized: "This file is not an Encave backup or a CSV with a name column.")
            return
        }
        let discoveriesAdded = imported.discoveries.count - model.cellar.discoveries.count
        guard model.update({ $0 = imported }) else { return }
        importMessage = Self.importMessage(summary, discoveriesAdded: discoveriesAdded)
    }

    /// Why a backup could not be read.
    static func message(for error: ImportError) -> String {
        switch error {
        case .newerVersion:
            String(localized: "This backup was made by a newer version of Encave. Update Encave on this device, then import it again.")
        case .unsupportedArchive:
            String(localized: "This ZIP file was not made by Encave, or another app changed it. Import the archive as Encave made it, or the cellar.json file it holds.")
        case .damagedArchive:
            String(localized: "This backup is damaged: it could not be read in full.")
        case .notAnEncaveBackup, .emptyFile, .missingNameColumn:
            String(localized: "This file is not an Encave backup or a CSV with a name column.")
        }
    }

    /// "Imported 12 bottles, 3 new wines and 2 wines tasted elsewhere. Rows skipped: 1".
    static func importMessage(_ summary: ImportSummary, discoveriesAdded: Int) -> String {
        var parts: [String] = []
        if summary.bottlesAdded > 0 { parts.append(String(localized: "\(summary.bottlesAdded) bottles")) }
        if summary.winesAdded > 0 { parts.append(String(localized: "\(summary.winesAdded) new wines")) }
        if discoveriesAdded > 0 { parts.append(String(localized: "\(discoveriesAdded) wines tasted elsewhere")) }
        let importedLabel = parts.formatted(.list(type: .and))
        var message = parts.isEmpty ? String(localized: "Nothing new: everything in this file is already in your cellar.")
            : String(localized: "Imported \(importedLabel).")
        if summary.rowsSkipped > 0 {
            message += " " + String(localized: "Rows without a wine name, skipped: \(summary.rowsSkipped)")
        }
        return message
    }
}

/// In-memory file for the exporter.
struct ExportDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json, .commaSeparatedText, .pdf] }
    var data: Data
    var contentType: UTType
    var filename: String

    init(data: Data, contentType: UTType, filename: String) {
        self.data = data
        self.contentType = contentType
        self.filename = filename
    }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
        contentType = configuration.contentType
        filename = "Encave"
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
