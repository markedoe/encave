import Foundation
import Observation

/// A yes/no setting kept in the defaults, for the views at the root of a window or of
/// the app. Not @AppStorage there: it redraws its view on every write to the defaults,
/// AppKit's own included, and on the Mac AppKit writes them while laying out the window,
/// so the window and the menus redrew themselves without end. This one changes only when
/// the setting does.
@MainActor
@Observable
final class DefaultsFlag {
    /// Mac and iPad: the cellar as a table rather than a list.
    static let cellarTable = DefaultsFlag(CellarLayout.key, default: false)
    /// Mac: Tonight in the menu bar.
    static let menuBar = DefaultsFlag(MenuBarSetting.key, default: MenuBarSetting.defaultValue)

    @ObservationIgnored private let key: String
    private var stored: Bool

    var isOn: Bool {
        get { stored }
        set {
            guard newValue != stored else { return }
            stored = newValue
            UserDefaults.standard.set(newValue, forKey: key)
        }
    }

    private init(_ key: String, default value: Bool) {
        self.key = key
        stored = UserDefaults.standard.object(forKey: key) as? Bool ?? value
    }
}
