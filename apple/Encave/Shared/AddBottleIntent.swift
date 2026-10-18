import AppIntents

/// Opens the app on Add Bottles, from Siri, Shortcuts and the Control Center control.
/// Compiled into the app and the widget extension: the control needs the type, and
/// because the intent opens the app, the system performs it in the app.
struct AddBottleIntent: AppIntent {
    static let title: LocalizedStringResource = "Add Bottles"
    static let description = IntentDescription("Opens Encave to add bottles, by scanning the label or typing.")
    static let openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        #if !ENCAVE_WIDGETS
        CellarModel.current?.handle(.addBottles)
        #endif
        return .result()
    }
}
