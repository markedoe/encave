import CaveKit
import SwiftUI
#if canImport(FoundationModels)
import FoundationModels
#endif

/// Navigation value for "Ask your cellar".
struct AskCellarRoute: Hashable {}

/// "Ask your cellar": free questions answered with the bottles you own, by the
/// language model built into the device (Apple Intelligence). The question and
/// the cellar never leave the device. Shown only where that model is available.
enum CellarAssistant {
    static var isAvailable: Bool {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, macOS 26.0, *) {
            if case .available = SystemLanguageModel.default.availability { return true }
        }
        #endif
        return false
    }

    struct Answer: Equatable {
        var text: String
        var wineIDs: [UUID]
    }

    enum Failure: Error {
        case unavailable, refused, tooLarge, other
    }

    @MainActor
    static func ask(_ question: String, model: CellarModel) async throws -> Answer {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, macOS 26.0, *) {
            return try await Generator.ask(question, model: model)
        }
        #endif
        throw Failure.unavailable
    }
}

#if canImport(FoundationModels)
@available(iOS 26.0, macOS 26.0, *)
@Generable
private struct CellarReply {
    @Guide(description: "A short answer of two or three sentences that names the suggested wines and why they fit.")
    var answer: String
    @Guide(description: "The numbers of the suggested wines in the list, best first. At most four. Empty if none fits.")
    var wines: [Int]
}

@available(iOS 26.0, macOS 26.0, *)
private enum Generator {
    /// The model's context is small: a large cellar is tried with fewer wines (the
    /// most urgent first) before giving up.
    @MainActor
    static func ask(_ question: String, model: CellarModel) async throws -> CellarAssistant.Answer {
        guard case .available = SystemLanguageModel.default.availability else { throw CellarAssistant.Failure.unavailable }
        for limit in [60, 30, 15] {
            do {
                return try await ask(question, model: model, limit: limit)
            } catch CellarAssistant.Failure.tooLarge where limit > 15 {
                continue
            }
        }
        throw CellarAssistant.Failure.tooLarge
    }

    @MainActor
    private static func ask(_ question: String, model: CellarModel, limit: Int) async throws -> CellarAssistant.Answer {
        let catalog = model.cellar.assistantCatalog(year: model.currentYear, limit: limit) { model.locationLabel(for: $0) }
        let language = Locale.current.localizedString(forLanguageCode: Locale.current.language.languageCode?.identifier ?? "en")
            ?? "English"
        let prompt = """
        You help someone choose wine from their own cellar. Suggest only wines from the numbered list below, \
        and prefer wines that are ready or to drink soon. If nothing fits, say so. Answer in \(language).

        The cellar (number. wine | producer | style | origin | grapes | maturity | bottles | place):
        \(catalog.text)

        Question: \(question)
        """
        let session = LanguageModelSession()
        do {
            let reply = try await session.respond(to: prompt, generating: CellarReply.self).content
            var seen = Set<UUID>()
            let ids = reply.wines.compactMap { catalog.wineID(number: $0) }.filter { seen.insert($0).inserted }
            return CellarAssistant.Answer(text: reply.answer, wineIDs: Array(ids.prefix(4)))
        } catch let error as LanguageModelSession.GenerationError {
            switch error {
            case .guardrailViolation: throw CellarAssistant.Failure.refused
            case .exceededContextWindowSize: throw CellarAssistant.Failure.tooLarge
            default: throw CellarAssistant.Failure.other
            }
        }
    }
}
#endif

/// Type a question, get an answer and the bottles it suggests.
struct AskCellarView: View {
    @Environment(CellarModel.self) private var model
    /// Mac and iPad columns: the wine opens beside the list instead of being pushed.
    var selection: Binding<UUID?>?
    @State private var question = ""
    @State private var answer: CellarAssistant.Answer?
    @State private var asking = false
    @State private var problem: String?
    @FocusState private var focused: Bool

    private var examples: [String] {
        [String(localized: "Something light for a ratatouille"),
         String(localized: "A bottle to bring to friends who love Burgundy"),
         String(localized: "What goes with roast chicken tonight?")]
    }

    var body: some View {
        List {
            Section {
                TextField("Ask about your cellar", text: $question)
                    .focused($focused)
                    .submitLabel(.send)
                    .onSubmit(send)
                    .accessibilityIdentifier("ask.question")
                Button {
                    send()
                } label: {
                    if asking {
                        HStack { ProgressView(); Text("Thinking…") }
                    } else {
                        Label("Ask", systemImage: "sparkles")
                    }
                }
                .disabled(asking || question.trimmingCharacters(in: .whitespaces).isEmpty)
            } footer: {
                Text("Answered on this device by Apple Intelligence, from the bottles in your cellar. Nothing is sent anywhere.")
            }

            if let problem {
                Section {
                    Label(problem, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.secondary)
                }
            }

            if let answer {
                Section {
                    Text(answer.text)
                        .textSelection(.enabled)
                    ForEach(answer.wineIDs, id: \.self) { id in
                        if let group = model.cellar.groups().first(where: { $0.wine.id == id }) {
                            if let selection {
                                Button { selection.wrappedValue = id } label: { WineRow(group: group) }
                                    .buttonStyle(.plain)
                            } else {
                                NavigationLink(value: id) { WineRow(group: group) }
                            }
                        }
                    }
                } header: {
                    Text("Answer")
                }
            } else if !asking {
                Section("Ideas") {
                    ForEach(examples, id: \.self) { example in
                        Button {
                            question = example
                            send()
                        } label: {
                            Label(example, systemImage: "text.bubble")
                        }
                    }
                }
            }
        }
        .navigationTitle("Ask Your Cellar")
        .inlineTitle()
        .onAppear { focused = answer == nil }
    }

    private func send() {
        let text = question.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !asking else { return }
        asking = true
        problem = nil
        focused = false
        Task {
            defer { asking = false }
            do {
                answer = try await CellarAssistant.ask(text, model: model)
            } catch CellarAssistant.Failure.refused {
                problem = String(localized: "This question cannot be answered. Try asking about a dish or an occasion.")
            } catch CellarAssistant.Failure.tooLarge {
                problem = String(localized: "Your cellar or the question is too large for the model on this device. Try a shorter question.")
            } catch CellarAssistant.Failure.unavailable {
                problem = String(localized: "Apple Intelligence is not available on this device right now.")
            } catch {
                problem = String(localized: "No answer this time. Try again.")
            }
        }
    }
}
