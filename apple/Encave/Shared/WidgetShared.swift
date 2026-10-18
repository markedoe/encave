import CaveKit
import Foundation

// Shared by the app and its widget extension (see EncaveWidgets in project.yml).

/// Places in the app that widgets open: encave://wine/<id>, encave://add, encave://tonight.
enum EncaveLink: Equatable {
    case wine(UUID)
    case addBottles
    case tonight

    static let scheme = "encave"

    var url: URL {
        var components = URLComponents()
        components.scheme = Self.scheme
        switch self {
        case .wine(let id):
            components.host = "wine"
            components.path = "/" + id.uuidString
        case .addBottles:
            components.host = "add"
        case .tonight:
            components.host = "tonight"
        }
        return components.url ?? URL(fileURLWithPath: "/")
    }

    init?(url: URL) {
        guard url.scheme?.lowercased() == Self.scheme,
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        switch components.host?.lowercased() {
        case "wine":
            let id = components.path.split(separator: "/").first.flatMap { UUID(uuidString: String($0)) }
            guard let id else { return nil }
            self = .wine(id)
        case "add":
            self = .addBottles
        case "tonight":
            self = .tonight
        default:
            return nil
        }
    }
}

/// The widgets' copy of the cellar summary, in the App Group container.
/// iPhone and iPad only: there are no Mac widgets yet, and on the Mac the group
/// would need the team prefix.
enum WidgetSnapshotFile {
    static var url: URL? {
        #if os(iOS)
        FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: WidgetSnapshot.appGroup)?
            .appendingPathComponent(WidgetSnapshot.fileName)
        #else
        nil
        #endif
    }

    /// The last snapshot the app wrote, or nil if there is none (or no App Group, as in unsigned builds).
    static func read() -> WidgetSnapshot? {
        guard let url, let data = try? Data(contentsOf: url) else { return nil }
        return WidgetSnapshot.decode(data)
    }
}
