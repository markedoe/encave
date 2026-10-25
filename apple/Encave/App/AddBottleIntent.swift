import AppIntents

/// Opens the app on Add Bottles: Shortcuts, Siri, and the Control Center control of the
/// widget extension, which compiles this file too (WIDGET_EXTENSION, apple/project.yml).
/// The system opens the app and runs `perform` there.
struct AddBottleIntent: AppIntent {
    static let title: LocalizedStringResource = "Add Bottles"
    static let description = IntentDescription("Opens Encave to add bottles, by scanning the label or typing.")
    static let openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        #if !WIDGET_EXTENSION
        // On the label scanner where there is one: the fastest way to add a bottle.
        CellarModel.current?.request(.present(.addBottle(AddBottleContext(scan: true))))
        #endif
        return .result()
    }
}
