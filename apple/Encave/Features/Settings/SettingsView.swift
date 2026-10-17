import CaveKit
import SwiftUI
import UniformTypeIdentifiers

struct SettingsView: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var confirmingErase = false
    @State private var exportDocument: ExportDocument?
    @State private var importing = false
    @State private var importMessage: String?
    @State private var remindersOn = Reminders.isEnabled
    #if os(macOS)
    @AppStorage(MenuBarSetting.key) private var showsMenuBarExtra = true
    #endif
    @State private var remindersRefused = false

    private static let currencies = ["EUR", "CHF", "USD", "GBP", "CAD", "AUD", "JPY", "SEK", "NOK", "DKK"]

    private var version: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "–"
        let build = info?["CFBundleVersion"] as? String ?? "–"
        return "\(short) (\(build))"
    }

    private var remindersBinding: Binding<Bool> {
        Binding(get: { remindersOn }, set: { on in
            remindersOn = on
            if on {
                Task {
                    let granted = await Reminders.enable(for: model.cellar)
                    remindersOn = granted
                    remindersRefused = !granted
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
                        Text(devicesSummary)
                    } label: {
                        Label("Devices", systemImage: "laptopcomputer.and.iphone")
                    }
                }
                .accessibilityIdentifier("settings.devices")
            } footer: {
                Text("Sync your cellar with your iPhone, iPad and Mac, encrypted from end to end.")
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
                Picker("Currency", selection: currencyBinding) {
                    ForEach(Self.currencies, id: \.self) { code in
                        Text(Locale.current.localizedString(forCurrencyCode: code).map { "\($0) (\(code))" } ?? code).tag(code)
                    }
                }
            } header: {
                Text("Preferences")
            } footer: {
                Text("Used for new purchases. Prices already entered keep their currency.")
            }

            Section {
                Button {
                    export(json: true)
                } label: {
                    Label("Back Up (JSON)", systemImage: "externaldrive")
                }
                Button {
                    export(json: false)
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
                    Label("Import…", systemImage: "square.and.arrow.down")
                }
            } header: {
                Text("Your data")
            } footer: {
                Text("A backup contains everything and can be imported on another device. CSV opens in Numbers or Excel. The PDF lists every wine with its places and purchase value, for insurance or a move.")
            }

            AISettingsSection()

            Section {
                if model.sync.otherDevices.isEmpty {
                    Label("Your cellar is stored only on this device.", systemImage: "lock.shield")
                } else {
                    Label("Your cellar is stored only on your devices.", systemImage: "lock.shield")
                }
            } header: {
                Text("Privacy")
            } footer: {
                Text("Devices sync directly on your network, encrypted from end to end. No server ever sees your cellar.")
            }

            Section("Test data") {
                Button("Load sample cellar") { model.loadSample() }
                Button("Erase all data", role: .destructive) { confirmingErase = true }
            }

            Section("About") {
                LabeledContent("Version", value: version)
                Link(destination: URL(string: "https://github.com/markedoe/encave")!) {
                    Label("Source code", systemImage: "chevron.left.forwardslash.chevron.right")
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Settings")
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
            Text("This removes every bottle, rack and tasting from this device.")
        }
        .fileExporter(
            isPresented: Binding(get: { exportDocument != nil }, set: { if !$0 { exportDocument = nil } }),
            document: exportDocument,
            contentType: exportDocument?.contentType ?? .json,
            defaultFilename: exportDocument?.filename
        ) { _ in
            exportDocument = nil
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .commaSeparatedText, .plainText]) { result in
            handleImport(result)
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

    private func export(json: Bool) {
        let date = Date.now.formatted(.iso8601.year().month().day())
        if json {
            guard let data = try? CellarExport.json(model.cellar) else { return }
            exportDocument = ExportDocument(data: data, contentType: .json, filename: "Encave \(date).json")
        } else {
            let data = Data(CellarExport.csv(model.cellar).utf8)
            exportDocument = ExportDocument(data: data, contentType: .commaSeparatedText, filename: "Encave \(date).csv")
        }
    }

    private func exportInventory() {
        let date = Date.now.formatted(.iso8601.year().month().day())
        guard let data = InventoryPDF.make(for: model) else { return }
        exportDocument = ExportDocument(data: data, contentType: .pdf, filename: "Encave inventory \(date).pdf")
    }

    private func handleImport(_ result: Result<URL, Error>) {
        guard case .success(let url) = result else { return }
        let accessing = url.startAccessingSecurityScopedResource()
        defer { if accessing { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url) else {
            importMessage = String(localized: "The file could not be read.")
            return
        }
        var summary: ImportSummary?
        let ok = model.update { cellar in
            if url.pathExtension.lowercased() == "json" {
                summary = cellar.merge(try CellarExport.backup(fromJSON: data).cellar)
            } else {
                summary = try cellar.importCSV(String(decoding: data, as: UTF8.self))
            }
        }
        if ok, let summary {
            importMessage = String(localized: "Imported \(summary.bottlesAdded) bottles of \(summary.winesAdded) new wines.")
            model.errorMessage = nil
        } else {
            model.errorMessage = nil
            importMessage = String(localized: "This file is not an Encave backup or a CSV with a name column.")
        }
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
