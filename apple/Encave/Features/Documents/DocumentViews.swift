import CaveKit
import CoreGraphics
import Foundation
import QuickLook
import SwiftUI
import UniformTypeIdentifiers
#if os(iOS)
import VisionKit
#endif

// Documents of a wine (for all its bottles) or of one bottle: invoices, certificates,
// technical sheets, articles, pictures of receipts. Stored on the device (DocumentStore)
// and synced like the photos, encrypted. Never sent to the AI service or web search.

/// What a document is, for its icon and for VoiceOver.
enum DocumentKind {
    case pdf, image, text, spreadsheet, presentation, other

    init(contentType: String) {
        guard let type = UTType(mimeType: contentType) else { self = .other; return }
        if type.conforms(to: .pdf) {
            self = .pdf
        } else if type.conforms(to: .image) {
            self = .image
        } else if type.conforms(to: .spreadsheet) {
            self = .spreadsheet
        } else if type.conforms(to: .presentation) {
            self = .presentation
        } else if type.conforms(to: .text) || DocumentKind.wordProcessing.contains(where: { type.conforms(to: $0) }) {
            self = .text
        } else {
            self = .other
        }
    }

    var title: String {
        switch self {
        case .pdf: String(localized: "PDF")
        case .image: String(localized: "Image")
        case .text: String(localized: "Text")
        case .spreadsheet: String(localized: "Spreadsheet")
        case .presentation: String(localized: "Presentation")
        case .other: String(localized: "Document")
        }
    }

    var systemImage: String {
        switch self {
        case .pdf: "doc.richtext"
        case .image: "photo"
        case .text: "doc.text"
        case .spreadsheet: "tablecells"
        case .presentation: "rectangle.on.rectangle"
        case .other: "doc"
        }
    }

    private static let wordProcessing: [UTType] = [
        "com.microsoft.word.doc", "org.openxmlformats.wordprocessingml.document", "org.oasis-open.opendocument.text",
        "com.apple.iwork.pages.sffpages",
    ].compactMap { UTType($0) }

    /// What "Add a Document…" offers: PDF, images, text and office documents.
    static let importable: [UTType] = [.pdf, .image, .text, .spreadsheet, .presentation] + wordProcessing + [
        "com.microsoft.excel.xls", "org.openxmlformats.spreadsheetml.sheet", "com.microsoft.powerpoint.ppt",
        "org.openxmlformats.presentationml.presentation", "org.oasis-open.opendocument.spreadsheet",
    ].compactMap { UTType($0) }
}

extension Attachment {
    var kind: DocumentKind { DocumentKind(contentType: contentType) }
    var shownName: String { name.isEmpty ? String(localized: "Document") : name }
    var sizeText: String { Int64(byteCount).formatted(.byteCount(style: .file)) }
    /// "PDF, Invoice.pdf, 240 KB", for VoiceOver.
    var spokenDescription: String { [kind.title, shownName, sizeText].joined(separator: ", ") }
}

extension PendingDocument {
    var kind: DocumentKind { DocumentKind(contentType: contentType) }
    var sizeText: String { Int64(byteCount).formatted(.byteCount(style: .file)) }
}

// MARK: - Model

extension CellarModel {
    func attachDocuments(_ attachments: [Attachment], to target: AttachmentTarget) {
        guard !attachments.isEmpty else { return }
        if update({ try $0.attach(attachments, to: target) }) { successCount += 1 }
    }

    func renameDocument(_ id: String, of target: AttachmentTarget, to name: String) {
        update { try $0.renameAttachment(id, of: target, to: name) }
    }

    /// Removes a document, with Undo. The file stays for a while (PhotoStore.orphanGracePeriod).
    func removeDocument(_ id: String, from target: AttachmentTarget) {
        var removed: (attachment: Attachment, index: Int)?
        guard update({ removed = try $0.removeAttachment(id, from: target) }), let removed else { return }
        showToast(String(localized: "Document removed")) { [weak self] in
            self?.update { try $0.attach([removed.attachment], to: target, at: removed.index) }
        }
    }
}

// MARK: - Actions shared by the screens

/// Adding, opening, renaming and removing documents on one screen: the file picker,
/// the scanner, Quick Look and the alerts are attached once, with `documentActions`.
@MainActor
@Observable
final class DocumentActions {
    enum Destination: Equatable {
        case attach(AttachmentTarget)
        /// Kept until the bottles are added (Add Bottles).
        case pending
    }

    var importing = false
    var scanning = false
    var busy = false
    var preview: URL?
    var renaming: Renaming?
    var newName = ""
    var problem: String?
    /// Picked or scanned, not attached yet.
    var pending: [PendingDocument] = []
    @ObservationIgnored private var destination: Destination = .pending
    @ObservationIgnored weak var model: CellarModel?

    struct Renaming: Identifiable {
        var attachment: Attachment
        var target: AttachmentTarget
        var id: String { attachment.id }
    }

    func importFile(to destination: Destination) {
        self.destination = destination
        importing = true
    }

    func scan(to destination: Destination) {
        self.destination = destination
        scanning = true
    }

    func open(_ attachment: Attachment) {
        guard DocumentStore.exists(attachment.id) else {
            problem = String(localized: "This document is not on this device yet. It arrives with the next sync with the device it was added on.")
            return
        }
        Task {
            let url = await Task.detached(priority: .userInitiated) { DocumentStore.temporaryCopy(of: attachment) }.value
            if let url { preview = url } else { problem = String(localized: "This document could not be opened.") }
        }
    }

    func rename(_ attachment: Attachment, of target: AttachmentTarget) {
        newName = attachment.name
        renaming = Renaming(attachment: attachment, target: target)
    }

    func finishRenaming() {
        guard let renaming else { return }
        model?.renameDocument(renaming.attachment.id, of: renaming.target, to: newName)
        self.renaming = nil
    }

    func removePending(_ document: PendingDocument) {
        pending.removeAll { $0.id == document.id }
        DocumentStore.discard(document)
    }

    /// Cancelled: the temporary copies go.
    func discardPending() {
        pending.forEach(DocumentStore.discard)
        pending = []
    }

    /// Stores the pending documents for good; they are attached by the caller. Nil if one
    /// could not be stored (its copy is gone, or the disk is full): it is left out of the
    /// list and the user is told, so the caller can stop and let them try again.
    func commitPending() -> [Attachment]? {
        var attachments: [Attachment] = [], failed: [PendingDocument] = []
        for document in pending {
            if let attachment = DocumentStore.commit(document) { attachments.append(attachment) } else { failed.append(document) }
        }
        guard failed.isEmpty else {
            for document in failed { removePending(document) }
            problem = String(localized: "This file could not be read.")
            return nil
        }
        return attachments
    }

    func picked(_ result: Result<[URL], Error>) {
        guard case .success(let urls) = result, !urls.isEmpty else { return }
        add(to: destination) {
            var staged: [PendingDocument] = []
            var tooLarge = false, unreadable = false
            for url in urls {
                do { staged.append(try DocumentStore.stage(url)) } catch DocumentStore.AddError.tooLarge {
                    tooLarge = true
                } catch {
                    unreadable = true
                }
            }
            return (staged, tooLarge, unreadable)
        }
    }

    func dropped(_ urls: [URL], to destination: Destination) -> Bool {
        let files = urls.filter(\.isFileURL)
        guard !files.isEmpty else { return false }
        self.destination = destination
        picked(.success(files))
        return true
    }

    /// Pages from the document camera, as JPEG: made into one PDF.
    func scanned(_ pages: [Data]) {
        guard !pages.isEmpty else { return }
        let name = String(localized: "Scan \(Date.now.formatted(date: .abbreviated, time: .shortened))") + ".pdf"
        add(to: destination) {
            guard let pdf = ScannedPDF.make(pages) else { return ([], false, true) }
            do {
                return ([try DocumentStore.stage(pdf, name: name, contentType: "application/pdf")], false, false)
            } catch DocumentStore.AddError.tooLarge {
                return ([], true, false)
            } catch {
                return ([], false, true)
            }
        }
    }

    private func add(to destination: Destination,
                     _ work: @escaping @Sendable () -> (staged: [PendingDocument], tooLarge: Bool, unreadable: Bool)) {
        busy = true
        Task {
            let result = await Task.detached(priority: .userInitiated) { work() }.value
            switch destination {
            case .pending:
                pending += result.staged
            case .attach(let target):
                let staged = result.staged
                let attachments = await Task.detached(priority: .userInitiated) { () -> [Attachment] in
                    let committed = staged.compactMap { DocumentStore.commit($0) }
                    staged.forEach(DocumentStore.discard)
                    return committed
                }.value
                model?.attachDocuments(attachments, to: target)
            }
            busy = false
            if result.tooLarge {
                problem = String(localized: "A document can be at most 8 MB. Choose a smaller file, or scan fewer pages.")
            } else if result.unreadable {
                problem = String(localized: "This file could not be read.")
            }
        }
    }
}

extension View {
    /// The file picker, the scanner, Quick Look and the alerts of `actions`, once per screen.
    func documentActions(_ actions: DocumentActions) -> some View {
        modifier(DocumentActionsModifier(actions: actions))
    }
}

private struct DocumentActionsModifier: ViewModifier {
    @Environment(CellarModel.self) private var model
    @Bindable var actions: DocumentActions

    func body(content: Content) -> some View {
        content
            .fileImporter(isPresented: $actions.importing, allowedContentTypes: DocumentKind.importable,
                          allowsMultipleSelection: true) { result in actions.picked(result) }
            .quickLookPreview($actions.preview)
            #if os(iOS)
            .fullScreenCover(isPresented: $actions.scanning) {
                DocumentScanner { pages in
                    actions.scanning = false
                    actions.scanned(pages)
                } onCancel: {
                    actions.scanning = false
                }
                .ignoresSafeArea()
            }
            #endif
            .alert("Rename Document", isPresented: Binding(get: { actions.renaming != nil }, set: { if !$0 { actions.renaming = nil } })) {
                TextField("Name", text: $actions.newName)
                Button("Rename") { actions.finishRenaming() }
                Button("Cancel", role: .cancel) { actions.renaming = nil }
            }
            .alert("Document", isPresented: Binding(get: { actions.problem != nil }, set: { if !$0 { actions.problem = nil } })) {
                Button("OK", role: .cancel) { actions.problem = nil }
            } message: {
                Text(actions.problem ?? "")
            }
            .onAppear { actions.model = model }
    }
}

// MARK: - Rows and buttons

/// "Add a Document…", and "Scan a Document" where there is a document camera.
struct AddDocumentButtons: View {
    let actions: DocumentActions
    let destination: DocumentActions.Destination

    var body: some View {
        Button {
            actions.importFile(to: destination)
        } label: {
            if actions.busy {
                HStack { ProgressView(); Text("Adding…") }
            } else {
                Label("Add a Document…", systemImage: "doc.badge.plus")
            }
        }
        .disabled(actions.busy)
        .accessibilityHint(Text("A PDF, an image, a text or an office document, up to 8 MB."))
        // Not "action.": a list row is leading-aligned, not a centered button.
        .accessibilityIdentifier("document.add")
        #if os(macOS)
        .dropDestination(for: URL.self) { urls, _ in actions.dropped(urls, to: destination) }
        #endif
        #if os(iOS)
        if DocumentScanner.isSupported {
            Button {
                actions.scan(to: destination)
            } label: {
                Label("Scan a Document", systemImage: "doc.viewfinder")
            }
            .disabled(actions.busy)
            .accessibilityHint(Text("Takes pictures of a paper and keeps them as a PDF."))
        }
        #endif
    }
}

/// A document: tap to open it; Share, Rename and Delete in its menu.
struct DocumentRow: View {
    @Environment(CellarModel.self) private var model
    let document: Attachment
    let target: AttachmentTarget
    let actions: DocumentActions

    var body: some View {
        let available = DocumentStore.exists(document.id)
        Button {
            actions.open(document)
        } label: {
            HStack(spacing: 12) {
                Image(systemName: document.kind.systemImage)
                    .font(.title3)
                    .foregroundStyle(Color.accentColor)
                    .frame(minWidth: 28)
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: document.shownName)
                        .foregroundStyle(.primary)
                        .lineLimit(2)
                    let kindName = document.kind.title, sizeLabel = document.sizeText
                    Group {
                        if available {
                            Text(verbatim: "\(kindName) · \(sizeLabel)")
                        } else {
                            Text("\(kindName) · \(sizeLabel) · Not on this device yet")
                        }
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(verbatim: document.spokenDescription))
        .accessibilityValue(available ? Text(verbatim: "") : Text("Not on this device yet"))
        .accessibilityHint(Text("Opens the document."))
        .accessibilityAction(named: Text("Rename")) { actions.rename(document, of: target) }
        .accessibilityAction(named: Text("Delete")) { model.removeDocument(document.id, from: target) }
        .swipeActions(edge: .trailing) {
            Button(role: .destructive) {
                model.removeDocument(document.id, from: target)
            } label: {
                Label("Delete", systemImage: "trash")
            }
        }
        .contextMenu {
            if available {
                ShareLink(item: DocumentFile(attachment: document), preview: SharePreview(document.shownName)) {
                    Label("Share…", systemImage: "square.and.arrow.up")
                }
            }
            Button("Rename…", systemImage: "pencil") { actions.rename(document, of: target) }
            Divider()
            Button("Delete", systemImage: "trash", role: .destructive) { model.removeDocument(document.id, from: target) }
        }
        .accessibilityIdentifier("document.row")
    }
}

/// A document to share, copied with its name when it is shared.
struct DocumentFile: Transferable {
    let attachment: Attachment

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .data) { file in
            guard let url = DocumentStore.temporaryCopy(of: file.attachment) else { throw CocoaError(.fileNoSuchFile) }
            return SentTransferredFile(url)
        }
    }
}

/// The documents of a wine, on its page.
struct WineDocumentsSection: View {
    @Environment(CellarModel.self) private var model
    let wineID: UUID
    let actions: DocumentActions

    var body: some View {
        let target = AttachmentTarget.wine(wineID)
        Section {
            ForEach(model.cellar.attachments(of: target)) { document in
                DocumentRow(document: document, target: target, actions: actions)
            }
            AddDocumentButtons(actions: actions, destination: .attach(target))
        } header: {
            Text("Documents")
        } footer: {
            Text("Invoices, certificates, technical sheets, articles: for every bottle of this wine. They sync with your devices, encrypted.")
        }
    }
}

/// Documents picked while adding bottles, attached to their wine when they are added.
struct PendingDocumentsSection: View {
    let actions: DocumentActions

    var body: some View {
        Section {
            ForEach(actions.pending) { document in
                HStack(spacing: 12) {
                    Image(systemName: document.kind.systemImage)
                        .foregroundStyle(Color.accentColor)
                        .frame(minWidth: 28)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: document.name.isEmpty ? String(localized: "Document") : document.name)
                            .lineLimit(2)
                        Text(verbatim: "\(document.kind.title) · \(document.sizeText)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Text(verbatim: [document.kind.title, document.name, document.sizeText].joined(separator: ", ")))
                .accessibilityAction(named: Text("Remove")) { actions.removePending(document) }
                .swipeActions(edge: .trailing) {
                    Button(role: .destructive) {
                        actions.removePending(document)
                    } label: {
                        Label("Remove", systemImage: "minus.circle")
                    }
                }
                .contextMenu {
                    Button("Remove", systemImage: "minus.circle", role: .destructive) { actions.removePending(document) }
                }
            }
            AddDocumentButtons(actions: actions, destination: .pending)
        } header: {
            Text("Documents")
        } footer: {
            Text("An invoice, a certificate, a technical sheet: kept with the wine, for all its bottles. You can add more later on the wine's page or on a bottle.")
        }
    }
}

/// A paperclip and the number of documents of a bottle.
struct DocumentCount: View {
    let count: Int

    var body: some View {
        Label {
            Text(verbatim: "\(count)").monospacedDigit()
        } icon: {
            Image(systemName: "paperclip")
        }
        .labelStyle(.titleAndIcon)
        .font(.caption)
        .foregroundStyle(.secondary)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("\(count) documents"))
    }
}

/// The documents of one bottle, in a small sheet.
struct BottleDocumentsView: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let bottleID: UUID
    var startsImporting = false
    @State private var actions = DocumentActions()
    @State private var started = false

    var body: some View {
        NavigationStack {
            List {
                if let bottle = model.cellar.bottles[bottleID] {
                    let target = AttachmentTarget.bottle(bottleID)
                    let wine = model.cellar.wines[bottle.wineID]
                    Section {
                        HStack(spacing: 12) {
                            WineThumbnail(wine: wine, width: 32)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(wine?.displayName ?? String(localized: "Unknown wine"))
                                    .font(.headline)
                                Text(model.placeLabel(for: bottle))
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .accessibilityElement(children: .combine)
                    }
                    Section {
                        ForEach(model.cellar.attachments(of: target)) { document in
                            DocumentRow(document: document, target: target, actions: actions)
                        }
                        AddDocumentButtons(actions: actions, destination: .attach(target))
                    } footer: {
                        Text("Documents of this bottle only, such as its certificate or its receipt. Those for every bottle of the wine are on the wine's page.")
                    }
                } else {
                    ContentUnavailableView("Bottle not found", systemImage: "questionmark")
                }
            }
            .navigationTitle("Documents")
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                        .accessibilityIdentifier("sheet.done")
                }
            }
            .documentActions(actions)
            .task {
                guard startsImporting, !started else { return }
                started = true
                // After the sheet has appeared: a picker cannot open during its animation.
                try? await Task.sleep(for: .milliseconds(500))
                actions.importFile(to: .attach(.bottle(bottleID)))
            }
        }
        // Undo banners must show above this sheet too.
        .overlay { ToastOverlay() }
        .presentationDetents([.medium, .large])
        .macSheetFrame(width: 460, height: 520)
    }
}

// MARK: - Scanning

/// One PDF from the scanned pages (JPEG data), one page per picture, with the JPEG
/// kept as it is so the file stays small.
enum ScannedPDF {
    static func make(_ pages: [Data]) -> Data? {
        let output = NSMutableData()
        guard let consumer = CGDataConsumer(data: output as CFMutableData),
              let context = CGContext(consumer: consumer, mediaBox: nil, nil) else { return nil }
        for jpeg in pages {
            guard let provider = CGDataProvider(data: jpeg as CFData),
                  let image = CGImage(jpegDataProviderSource: provider, decode: nil, shouldInterpolate: true,
                                      intent: .defaultIntent) else { continue }
            // A4 width (595 points), the height of the picture.
            let width: CGFloat = 595
            let height = width * CGFloat(image.height) / CGFloat(max(1, image.width))
            var box = CGRect(x: 0, y: 0, width: width, height: height)
            context.beginPage(mediaBox: &box)
            context.draw(image, in: box)
            context.endPage()
        }
        context.closePDF()
        return output.length > 0 ? output as Data : nil
    }
}

#if os(iOS)
/// The VisionKit document camera: pages straightened and cropped on the device.
struct DocumentScanner: UIViewControllerRepresentable {
    static var isSupported: Bool { VNDocumentCameraViewController.isSupported }

    let onScan: ([Data]) -> Void
    let onCancel: () -> Void

    func makeUIViewController(context: Context) -> VNDocumentCameraViewController {
        let controller = VNDocumentCameraViewController()
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: VNDocumentCameraViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onScan: onScan, onCancel: onCancel) }

    @MainActor
    final class Coordinator: NSObject, @preconcurrency VNDocumentCameraViewControllerDelegate {
        let onScan: ([Data]) -> Void
        let onCancel: () -> Void

        init(onScan: @escaping ([Data]) -> Void, onCancel: @escaping () -> Void) {
            self.onScan = onScan; self.onCancel = onCancel
        }

        func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFinishWith scan: VNDocumentCameraScan) {
            // At most 2000 pixels on the long side, JPEG 0.7: a page is a few hundred KB.
            let pages = (0..<scan.pageCount).compactMap { index -> Data? in
                let page = scan.imageOfPage(at: index)
                let scale = min(1, 2000 / max(page.size.width, page.size.height))
                let size = CGSize(width: (page.size.width * scale).rounded(), height: (page.size.height * scale).rounded())
                let format = UIGraphicsImageRendererFormat()
                format.scale = 1
                let resized = UIGraphicsImageRenderer(size: size, format: format).image { _ in
                    page.draw(in: CGRect(origin: .zero, size: size))
                }
                return resized.jpegData(compressionQuality: 0.7)
            }
            onScan(pages)
        }

        func documentCameraViewControllerDidCancel(_ controller: VNDocumentCameraViewController) {
            onCancel()
        }

        func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFailWithError error: Error) {
            onCancel()
        }
    }
}
#endif

/// "Documents…" and "Attach a Document…" in a bottle's menu.
struct BottleDocumentButtons: View {
    let bottle: Bottle
    let open: (AppSheet) -> Void

    var body: some View {
        if bottle.attachments != nil {
            Button("Documents…", systemImage: "paperclip") { open(.bottleDocuments(bottleID: bottle.id, importing: false)) }
        }
        Button("Attach a Document…", systemImage: "doc.badge.plus") { open(.bottleDocuments(bottleID: bottle.id, importing: true)) }
    }
}
