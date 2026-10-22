import Foundation

/// Which build this is, from the `EncaveChannel` Info.plist key (the ENCAVE_CHANNEL build
/// setting in project.yml). A beta (TestFlight, and by default) shows the beta notices
/// and offers the sample cellar; a store build ("store") shows neither.
enum AppChannel {
    static let isBeta: Bool = {
        let value = Bundle.main.object(forInfoDictionaryKey: "EncaveChannel") as? String ?? ""
        return value.trimmingCharacters(in: .whitespaces).lowercased() != "store"
    }()
}
