import CoreTransferable
import Foundation
import UniformTypeIdentifiers

extension UTType {
    /// A bottle dragged from a place of a rack (declared in project.yml).
    static let encaveBottle = UTType(exportedAs: "app.encave.bottle")
}

/// A bottle being dragged: its ID for Encave, and the wine's name as plain text when it
/// is dropped into another app.
struct BottleDrag: Codable, Sendable, Transferable {
    let bottleID: UUID
    let name: String

    static var transferRepresentation: some TransferRepresentation {
        CodableRepresentation(contentType: .encaveBottle)
        ProxyRepresentation(exporting: { (drag: BottleDrag) in drag.name })
    }
}
