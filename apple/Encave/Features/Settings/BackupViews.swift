import CaveKit
import SwiftUI

/// Settings › Your data: Back Up Now, Previous Versions and the weekly backup.
struct BackupRows: View {
    @Environment(CellarModel.self) private var model
    @State private var archive: URL?
    @State private var failed = false

    var body: some View {
        @Bindable var backups = model.backups
        Button {
            Task {
                do {
                    archive = try await model.backups.makeArchive(of: model.cellar)
                } catch {
                    failed = true
                }
            }
        } label: {
            if model.backups.isWorking {
                Label {
                    Text("Backing Up…")
                } icon: {
                    ProgressView()
                }
            } else {
                Label("Back Up Now", systemImage: "archivebox")
            }
        }
        .disabled(model.backups.isWorking || model.cellar.isEmpty)
        .accessibilityIdentifier("settings.backup")
        .sheet(item: Binding(get: { archive.map(ArchiveFile.init) }, set: { if $0 == nil { archive = nil } })) { file in
            BackupReadyView(url: file.url)
        }
        .alert("The backup could not be made.", isPresented: $failed) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Check that this device has enough free space, then try again.")
        }

        NavigationLink {
            PreviousVersionsView()
        } label: {
            Label("Previous Versions", systemImage: "clock.arrow.circlepath")
        }
        .accessibilityIdentifier("settings.versions")

        Toggle(isOn: $backups.weeklyEnabled) {
            Label {
                Text("Weekly Backup on This Device")
                if let status = lastBackupText {
                    Text(status)
                }
            } icon: {
                Image(systemName: "calendar.badge.clock")
            }
        }
        .accessibilityIdentifier("settings.weeklyBackup")

        if model.backups.reminderDue(for: model.cellar) {
            Label("No backup for more than a month. Back up now to keep a copy of your cellar, its photos and documents.",
                  systemImage: "exclamationmark.triangle")
                .accessibilityIdentifier("settings.backupReminder")
        }

        #if os(macOS)
        if !model.backups.automaticArchives.isEmpty {
            Button {
                model.backups.showAutomaticArchivesInFinder()
            } label: {
                Label("Show Weekly Backups in Finder", systemImage: "folder")
            }
        }
        #endif
    }

    /// "Last backup: 3 days ago".
    private var lastBackupText: String? {
        guard let last = model.backups.lastBackup else { return nil }
        let formatted = last.formatted(.relative(presentation: .named))
        return String(localized: "Last backup: \(formatted)")
    }
}

private struct ArchiveFile: Identifiable {
    let url: URL
    var id: URL { url }
}

/// After Back Up Now: share the archive or save it in Files.
struct BackupReadyView: View {
    let url: URL
    @Environment(\.dismiss) private var dismiss
    @State private var saving = false
    @State private var saved = false

    private var size: String {
        let bytes = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0
        return Int64(bytes).formatted(.byteCount(style: .file))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LabeledContent("File", value: url.lastPathComponent)
                    LabeledContent("Size", value: size)
                } footer: {
                    Text("A ZIP file with your whole cellar, its photos and documents. It is not encrypted: keep it somewhere safe, such as iCloud Drive or a computer.")
                }
                Section {
                    ShareLink(item: url) {
                        Label("Share…", systemImage: "square.and.arrow.up")
                    }
                    Button {
                        saving = true
                    } label: {
                        Label("Save to Files…", systemImage: "folder")
                    }
                }
                if saved {
                    Label("Saved to Files", systemImage: "checkmark.circle")
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Backup Ready")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .fileMover(isPresented: $saving, file: url) { result in
                if case .success = result {
                    saved = true
                    dismiss()
                }
            }
        }
        .macSheetFrame(width: 460, height: 360)
    }
}

/// The earlier versions of the cellar kept on this device, newest first.
struct PreviousVersionsView: View {
    @Environment(CellarModel.self) private var model
    @State private var rows: [Row]?

    struct Row: Identifiable, Sendable {
        var version: CellarVersion
        var bottles: Int
        var wines: Int
        var id: String { version.id }
    }

    var body: some View {
        Group {
            if let rows, rows.isEmpty {
                ContentUnavailableView {
                    Label("No Previous Versions Yet", systemImage: "clock.arrow.circlepath")
                } description: {
                    Text("Encave keeps a copy of your cellar on each day it changes. The first one appears tomorrow.")
                }
            } else {
                List {
                    Section {
                        ForEach(rows ?? []) { row in
                            NavigationLink {
                                VersionPreviewLoader(version: row.version)
                            } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(row.version.date.formatted(date: .complete, time: .shortened))
                                    Text([String(localized: "\(row.bottles) bottles"), String(localized: "\(row.wines) wines")]
                                        .joined(separator: " · "))
                                        .font(.subheadline)
                                        .foregroundStyle(.secondary)
                                }
                                .accessibilityElement(children: .combine)
                            }
                            .accessibilityIdentifier("versions.row")
                        }
                    } footer: {
                        Text("A copy of your cellar is kept on each day it changes: one a day for the last 7 days, then one a week for 4 weeks. They stay on this device; the photos and documents they need are kept with them.")
                    }
                }
                .overlay {
                    if rows == nil { ProgressView() }
                }
            }
        }
        .navigationTitle("Previous Versions")
        .task(id: model.backups.changeCount) {
            let versions = model.backups.versions
            rows = await Task.detached(priority: .userInitiated) {
                versions.versions().map { version -> Row in
                    let cellar = try? versions.cellar(of: version)
                    return Row(version: version,
                               bottles: cellar?.bottles.values.filter { $0.status == .inCellar }.count ?? 0,
                               wines: cellar?.wines.count ?? 0)
                }
            }.value
        }
    }
}

/// Reads a version, then shows its preview.
private struct VersionPreviewLoader: View {
    let version: CellarVersion
    @Environment(CellarModel.self) private var model
    @State private var source: RestoreSource?
    @State private var failed = false

    var body: some View {
        Group {
            if let source {
                RestorePreviewView(source: source)
            } else if failed {
                ContentUnavailableView("This version could not be read.", systemImage: "exclamationmark.triangle")
            } else {
                ProgressView()
            }
        }
        .task {
            do { source = try await model.backups.source(for: version) } catch { failed = true }
        }
    }
}

/// What a backup or an earlier version holds and how it differs from the cellar, with the
/// two ways to restore it.
struct RestorePreviewView: View {
    let source: RestoreSource
    /// Shown in a sheet of its own (not pushed in a list), with a Cancel button.
    var inSheet = false
    @Environment(CellarModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var confirmingReplace = false
    @State private var working = false

    private var preview: RestorePreview {
        RestorePreview(of: source.cellar, date: source.date, comparedWith: model.cellar)
    }

    private var title: LocalizedStringKey {
        switch source.kind {
        case .version: "Previous Version"
        case .archive, .json: "Backup"
        }
    }

    var body: some View {
        let preview = preview
        Form {
            Section {
                let date = source.date.formatted(date: .long, time: .shortened)
                if source.kind == .version {
                    LabeledContent("Saved On", value: date)
                } else {
                    LabeledContent("Made On", value: date)
                }
            }
            Section("In This Backup") {
                count("Wines", preview.wines)
                count("Bottles in the cellar", preview.bottles)
                count("Racks", preview.racks)
                count("Journal entries", preview.journalEntries)
                count("Photos", preview.photos)
                count("Documents", preview.documents)
            }
            Section {
                if preview.isSame {
                    Label("Same as your cellar", systemImage: "checkmark.circle")
                } else {
                    count("Not in your cellar", preview.added)
                    count("In your cellar only", preview.removed)
                    count("Different", preview.changed)
                }
            } header: {
                Text("Compared with Your Cellar")
            } footer: {
                Text("Wines, bottles, racks and wines tasted elsewhere.")
            }
            if source.kind == .json, preview.photos + preview.documents > 0 {
                Section {
                    Text("A JSON backup holds no photo or document files: those not on this device show as missing until a device that has them syncs.")
                        .font(.footnote)
                }
            } else if source.kind == .archive {
                Section {
                    count("Photos and documents included", source.files.count)
                }
            }
            Section {
                Button {
                    run(.addMissing)
                } label: {
                    Label("Add What Is Missing", systemImage: "plus.circle")
                }
                .accessibilityIdentifier("restore.add")
                Button(role: .destructive) {
                    confirmingReplace = true
                } label: {
                    Label("Replace This Cellar", systemImage: "arrow.counterclockwise")
                }
                .accessibilityIdentifier("restore.replace")
            } footer: {
                Text("Add What Is Missing adds the wines, bottles, racks and Journal entries that are not in your cellar and changes nothing else. Replace This Cellar makes your cellar exactly as in this backup. You can undo right after.")
            }
            .disabled(working)
        }
        .formStyle(.grouped)
        .accessibilityIdentifier("restore.preview")
        .navigationTitle(title)
        .toolbar {
            if inSheet {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        .confirmationDialog("Replace this cellar?", isPresented: $confirmingReplace, titleVisibility: .visible) {
            Button("Replace This Cellar", role: .destructive) { run(.replace) }
        } message: {
            if model.sync.otherDevices.isEmpty {
                Text("Your cellar becomes exactly as in this backup: what is not in it is deleted. You can undo right after.")
            } else {
                Text("Your cellar becomes exactly as in this backup, on this device and on the other devices that sync with it: what is not in it is deleted there too. You can undo right after.")
            }
        }
    }

    private func count(_ title: LocalizedStringKey, _ value: Int) -> some View {
        LabeledContent(title) { Text(value, format: .number) }
    }

    private func run(_ mode: RestoreMode) {
        working = true
        Task {
            let done = await model.restore(source, mode: mode)
            working = false
            guard done else { return }
            // The banner with Undo is under the sheets on iPhone and iPad: close them.
            #if os(iOS)
            model.sheet = nil
            #endif
            dismiss()
        }
    }
}
