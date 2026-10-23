import CaveKit
import CoreTransferable
import SwiftUI
import UniformTypeIdentifiers

extension UTType {
    /// A wine dragged from the Cellar list (declared in project.yml).
    static let encaveWine = UTType(exportedAs: "app.encave.wine")
}

/// A wine being dragged: its ID for Encave, and its name as plain text in another app.
struct WineDrag: Codable, Sendable, Transferable {
    let wineID: UUID
    let name: String

    static var transferRepresentation: some TransferRepresentation {
        CodableRepresentation(contentType: .encaveWine)
        ProxyRepresentation(exporting: { (drag: WineDrag) in drag.name })
    }
}

/// What a storage of the Mac sidebar takes: a wine from the Cellar list, or a bottle from
/// a wine's page or a rack.
enum StorageDropItem: Sendable, Transferable {
    case wine(UUID)
    case bottle(UUID)

    static var transferRepresentation: some TransferRepresentation {
        ProxyRepresentation(importing: { (drag: WineDrag) in StorageDropItem.wine(drag.wineID) })
        ProxyRepresentation(importing: { (drag: BottleDrag) in StorageDropItem.bottle(drag.bottleID) })
    }
}

extension CellarModel {
    /// A wine or a bottle dropped onto a storage: one bottle goes to its first free
    /// position, and a banner names it, with Undo. Never into a full storage.
    @discardableResult
    func drop(_ item: StorageDropItem, onto rackID: UUID) -> Bool {
        let drop = switch item {
        case .wine(let id): cellar.bottleDrop(wineID: id, onto: rackID)
        case .bottle(let id): cellar.bottleDrop(bottleID: id, onto: rackID)
        }
        switch drop {
        case .noFreePosition:
            showToast(String(localized: "No free position"))
            return false
        case .nothingToPlace:
            showToast(String(localized: "No bottle to place in this storage"))
            return false
        case .place(let bottleID, let slot):
            let snapshot = cellar
            guard update({ cellar in
                try cellar.move(bottleID, to: slot)
                // As when placing bottles from the list: the note said where it was before.
                cellar.bottles[bottleID]?.storageNote = nil
            }) else { return false }
            successCount += 1
            let place = cellar.bottles[bottleID].flatMap { locationLabel(for: $0) } ?? ""
            showToast(String(localized: "Bottle placed: \(place)")) { [weak self] in self?.update { $0 = snapshot } }
            return true
        }
    }
}

extension View {
    /// Mac: a storage of the sidebar takes wines and bottles dropped onto it.
    func storageDropTarget(_ rackID: UUID) -> some View {
        modifier(StorageDropTarget(rackID: rackID))
    }

    /// Mac: dragged onto a storage of the sidebar. Not on iPhone and iPad, where a drag
    /// would only get in the way of the menus and of scrolling.
    @ViewBuilder
    func sidebarDraggable<T: Transferable>(_ payload: @autoclosure @escaping () -> T) -> some View {
        #if os(macOS)
        draggable(payload())
        #else
        self
        #endif
    }
}

private struct StorageDropTarget: ViewModifier {
    @Environment(CellarModel.self) private var model
    let rackID: UUID
    @State private var isTargeted = false

    func body(content: Content) -> some View {
        content
            .dropDestination(for: StorageDropItem.self) { items, _ in
                guard let item = items.first else { return false }
                return model.drop(item, onto: rackID)
            } isTargeted: { isTargeted = $0 }
            // The storage under the pointer, as Finder shows a folder.
            .background {
                if isTargeted {
                    RoundedRectangle(cornerRadius: 6, style: .continuous)
                        .fill(Color.accentColor.opacity(0.25))
                        .padding(-4)
                        .allowsHitTesting(false)
                }
            }
    }
}
