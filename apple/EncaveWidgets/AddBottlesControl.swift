import AppIntents
import SwiftUI
import WidgetKit

/// Control Center, lock screen and Action button: opens Encave on Add Bottles.
/// The intent is also part of the app, which performs it once opened.
struct AddBottlesControl: ControlWidget {
    static let kind = "app.encave.widgets.add-bottles"

    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: Self.kind) {
            ControlWidgetButton(action: AddBottleIntent()) {
                Label("Add Bottles", systemImage: "plus")
            }
        }
        .displayName("Add Bottles")
        .description("Opens Encave to add bottles, by scanning the label or typing.")
    }
}
