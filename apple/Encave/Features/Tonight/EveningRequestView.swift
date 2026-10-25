import CaveKit
import SwiftUI

/// Share an evening request from Tonight, as on Android: the occasion and the options of
/// Prepare an Evening Request, then the share sheet (the share menu on the Mac) with the
/// same text, for the app the user picks, such as Claude. Encave sends nothing itself.
struct EveningRequestView: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var occasion = ""
    /// Nil lets the assistant choose.
    @State private var bottles: Int?
    @State private var onlyReady = true

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Occasion, dishes, guests", text: $occasion, axis: .vertical)
                        .lineLimit(1...4)
                        .accessibilityIdentifier("evening.occasion")
                    Picker("Number of Bottles", selection: $bottles) {
                        Text("Let the assistant choose").tag(Int?.none)
                        ForEach(1...24, id: \.self) { Text(verbatim: "\($0)").tag(Int?.some($0)) }
                    }
                    Toggle("Only Bottles Ready to Drink", isOn: $onlyReady)
                } footer: {
                    Text("The text goes to the app you choose: your wines with their positions, without prices, notes or purchase places.")
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Evening Request")
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .accessibilityIdentifier("sheet.cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    ShareLink(item: requestText) {
                        Label("Share", systemImage: "square.and.arrow.up")
                    }
                    .accessibilityHint(Text("Opens the share sheet to choose the app, such as Claude."))
                    .accessibilityIdentifier("evening.share")
                }
            }
        }
        .macSheetFrame(width: 460, height: 360)
    }

    /// The same text as the App Intent and Android, in the app's language.
    private var requestText: String {
        let cellar = model.cellar
        return cellar.eveningRequest(occasion: occasion, bottlesWanted: bottles, onlyReady: onlyReady,
                                     language: EveningRequest.Language(code: Bundle.main.preferredLocalizations.first),
                                     year: model.currentYear) { model.locationLabel(for: $0) }.text
    }
}
