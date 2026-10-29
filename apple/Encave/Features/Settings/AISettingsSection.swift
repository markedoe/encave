import CaveKit
import SwiftUI

/// Settings › AI: the connections (provider, key, address) and the models that read label
/// photos, in order. Keys stay in the Keychain on this device, one per connection.
struct AISettingsSection: View {
    @State private var store = AIConfigurationModel()

    var body: some View {
        Section {
            NavigationLink {
                AIConnectionsView(store: store)
            } label: {
                LabeledContent {
                    Text(verbatim: "\(store.configuration.connections.count)")
                } label: {
                    Label("Connections", systemImage: "key")
                }
            }
            .accessibilityIdentifier("settings.ai.connections")
            NavigationLink {
                AIModelsView(store: store)
            } label: {
                LabeledContent {
                    Text(verbatim: "\(store.configuration.usableModels.count)")
                } label: {
                    Label("Models", systemImage: "cpu")
                }
            }
            .accessibilityIdentifier("settings.ai.models")
            HelpLink(topic: .ai, title: "How to set up AI")
        } header: {
            Text("AI")
        } footer: {
            if store.configuration.isEnabled {
                Text("Only the label photo is sent: to the first model of the list, and to the next one only if it fails. Your cellar is never sent. Keys are stored in the Keychain on this device.")
            } else {
                Text("Optional. Reads labels from a photo with the AI models you choose, using your own API keys.")
            }
        }
        .onAppear { store.reload() }
    }
}

/// The configuration being edited, saved at each change; what was checked and tested.
@MainActor @Observable
final class AIConfigurationModel {
    private(set) var configuration: AIConfiguration
    /// Models listed by "Check Key and Load Models", by connection, for adding them.
    var loadedModels: [UUID: [String]] = [:]
    var tests: [UUID: ModelTest] = [:]
    private let keys = AIKeychain()
    /// The screen tour shows made-up connections, never saved, with no key.
    private let isSample = ProcessInfo.processInfo.arguments.contains("--sample-ai")

    enum ModelTest: Equatable {
        case testing, passed
        case failed(String)
    }

    init() {
        configuration = ProcessInfo.processInfo.arguments.contains("--sample-ai") ? Self.sample : AISettings.load()
    }

    func reload() {
        guard !isSample else { return }
        configuration = AISettings.load()
    }

    func update(_ change: (inout AIConfiguration) -> Void) {
        change(&configuration)
        if !isSample { AISettings.save(configuration) }
    }

    func key(for connection: AIConnection) -> String { keys.key(for: connection) ?? "" }

    func setKey(_ key: String, for connection: AIConnection) {
        guard !isSample else { return }
        keys.setKey(key, for: connection)
    }

    /// Removes the connection, its models and its key.
    func delete(_ id: UUID) {
        guard let connection = configuration.connection(id: id) else { return }
        for model in configuration.models(of: id) { tests[model.id] = nil }
        update { $0.removeConnection(id) }
        loadedModels[id] = nil
        if !isSample { keys.setKey(nil, for: connection) }
    }

    func title(of model: AIModelChoice) -> String {
        configuration.connection(for: model)?.title ?? ""
    }

    /// Checks the key and that the model answers on a made-up label bundled with the app.
    func test(_ model: AIModelChoice) {
        guard let connection = configuration.connection(for: model) else { return }
        tests[model.id] = .testing
        let key = keys.key(for: connection)
        Task {
            do {
                try await AIService.test(connection: connection, model: model.model, apiKey: key)
                tests[model.id] = .passed
            } catch {
                tests[model.id] = .failed(error.localizedDescription)
            }
        }
    }

    private static var sample: AIConfiguration {
        let openRouter = AIConnection(provider: .openRouter, name: "OpenRouter perso")
        let anthropic = AIConnection(provider: .anthropic)
        let ollama = AIConnection(provider: .ollama, name: "Mac mini")
        var configuration = AIConfiguration(connections: [openRouter, anthropic, ollama])
        configuration.addModel("claude-opus-5-5", connection: anthropic.id)
        configuration.addModel("google/gemini-3-flash", connection: openRouter.id)
        configuration.addModel("gemma3:27b", connection: ollama.id)
        return configuration
    }
}

// MARK: Connections

/// Settings › AI › Connections: each with its provider, name, address and key.
struct AIConnectionsView: View {
    @Bindable var store: AIConfigurationModel
    @State private var adding = false
    @State private var deleting: AIConnection?

    var body: some View {
        Form {
            Section {
                if store.configuration.connections.isEmpty {
                    Text("No connection yet. Add one for each AI service or server you use, with its API key.")
                        .foregroundStyle(.secondary)
                }
                ForEach(store.configuration.connections) { connection in
                    NavigationLink {
                        AIConnectionEditor(store: store, connection: connection, isNew: false)
                    } label: {
                        AIConnectionRow(connection: connection, models: store.configuration.models(of: connection.id).count)
                    }
                    .contextMenu {
                        Button("Delete", systemImage: "trash", role: .destructive) { confirmDelete(connection) }
                    }
                }
                .onDelete { offsets in
                    if let index = offsets.first { confirmDelete(store.configuration.connections[index]) }
                }
            } footer: {
                Text("Several connections to the same service are possible, for example two OpenRouter accounts. Keys stay in the Keychain on this device: they are never synced, exported or backed up.")
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Connections")
        .inlineTitle()
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    adding = true
                } label: {
                    Label("Add Connection", systemImage: "plus")
                }
                .accessibilityIdentifier("ai.connections.add")
            }
        }
        .sheet(isPresented: $adding) {
            NavigationStack {
                AIConnectionEditor(store: store, connection: AIConnection(provider: .openRouter), isNew: true)
            }
            .macSheetFrame(width: 520, height: 680)
        }
        .confirmationDialog("Delete this connection?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
                            titleVisibility: .visible, presenting: deleting) { connection in
            Button("Delete Connection and Its Models", role: .destructive) {
                withAnimation { store.delete(connection.id) }
            }
        } message: { connection in
            let count = store.configuration.models(of: connection.id).count
            Text("\(count) models of this connection are removed from the list, and its key from this device.")
        }
    }

    /// A connection with models asks first; one without is deleted with its key.
    private func confirmDelete(_ connection: AIConnection) {
        if store.configuration.models(of: connection.id).isEmpty {
            withAnimation { store.delete(connection.id) }
        } else {
            deleting = connection
        }
    }
}

private struct AIConnectionRow: View {
    let connection: AIConnection
    let models: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: connection.title)
            Group {
                if connection.title == connection.provider.title {
                    Text("\(models) models")
                } else {
                    Text(verbatim: connection.provider.title) + Text(verbatim: " · ") + Text("\(models) models")
                }
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
    }
}

/// A connection's provider, name, address and key, with the key checked live. A new one
/// is added with Add; an existing one is saved at each change.
struct AIConnectionEditor: View {
    @Bindable var store: AIConfigurationModel
    @State var connection: AIConnection
    let isNew: Bool
    @Environment(\.dismiss) private var dismiss
    @State private var apiKey = ""
    @State private var check: KeyCheck = .idle
    @State private var loaded = false
    @State private var confirmingDelete = false

    enum KeyCheck: Equatable {
        case idle, checking, valid(Int), failed(String)
    }

    var body: some View {
        Form {
            Section {
                Picker("Provider", selection: $connection.provider) {
                    Section {
                        ForEach(AIProvider.global, id: \.self) { Text(verbatim: $0.name).tag($0) }
                    }
                    Section {
                        ForEach(AIProvider.chinese, id: \.self) { Text(verbatim: $0.name).tag($0) }
                    }
                    Section {
                        Text(verbatim: AIProvider.openRouter.name).tag(AIProvider.openRouter)
                    }
                    Section {
                        ForEach(AIProvider.localServers, id: \.self) { Text(verbatim: $0.title).tag($0) }
                    }
                }
                .fieldHelp("The AI service that reads label photos. You need an account and an API key with it.")
                TextField("Name (optional)", text: $connection.name, prompt: Text(verbatim: connection.provider.title))
                    .fieldHelp("To tell apart several connections to the same service.")
                TextField("Server URL", text: $connection.baseURL, prompt: Text(verbatim: connection.provider.defaultBaseURL))
                    .urlField()
                    .fieldHelp("Leave empty for the provider's usual address. For a local server, its address on your network.")
                Group {
                    if connection.provider.requiresKey {
                        SecureField("API key", text: $apiKey)
                    } else {
                        SecureField("API key (optional)", text: $apiKey)
                    }
                }
                .plainInput()
                .onSubmit(checkKey)
                .fieldHelp("Created on the provider's website. Stored in the Keychain on this device.")
                HStack {
                    Button(action: checkKey) {
                        if connection.provider.isLocal {
                            Label("Connect and Load Models", systemImage: "network")
                        } else {
                            Label("Check Key and Load Models", systemImage: "checkmark.shield")
                        }
                    }
                    .disabled(check == .checking || (connection.provider.requiresKey && apiKey.isEmpty))
                    Spacer()
                    checkStatus
                }
                if let page = connection.provider.keyPage {
                    Link(destination: page) { keyPageLabel }
                }
            } footer: {
                VStack(alignment: .leading, spacing: 6) {
                    if case .failed(let message) = check {
                        Text(verbatim: message).foregroundStyle(Color.error)
                    }
                    if connection.provider.isLocal {
                        localServerTips
                    } else {
                        Text("Leave the server URL empty to use the provider’s usual address.")
                    }
                }
            }

            if connection.provider == .openRouter {
                Section {
                    Toggle("Allow Providers That Keep Data", isOn: $connection.allowsDataCollection)
                        .accessibilityIdentifier("ai.connection.dataCollection")
                } footer: {
                    Text("Through OpenRouter, the photo reaches the provider of the model you choose. Off: Encave asks OpenRouter to use only providers that do not keep the photo or train on it, so some models may not answer. On: any provider can be used.")
                }
            }

            if !isNew {
                Section {
                    Button("Delete Connection", role: .destructive) {
                        if store.configuration.models(of: connection.id).isEmpty {
                            store.delete(connection.id)
                            dismiss()
                        } else {
                            confirmingDelete = true
                        }
                    }
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle(isNew ? "New Connection" : connection.title)
        .inlineTitle()
        .toolbar {
            if isNew {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        store.update { $0.upsert(connection) }
                        store.setKey(AISettings.cleanKey(apiKey), for: connection)
                        dismiss()
                    }
                    .disabled(connection.provider.requiresKey && AISettings.cleanKey(apiKey).isEmpty)
                }
            }
        }
        .confirmationDialog("Delete this connection?", isPresented: $confirmingDelete, titleVisibility: .visible) {
            Button("Delete Connection and Its Models", role: .destructive) {
                store.delete(connection.id)
                dismiss()
            }
        } message: {
            let count = store.configuration.models(of: connection.id).count
            Text("\(count) models of this connection are removed from the list, and its key from this device.")
        }
        .onAppear {
            guard !loaded else { return }
            loaded = true
            if !isNew { apiKey = store.key(for: connection) }
        }
        .onChange(of: connection) { old, new in
            if old.provider != new.provider || old.baseURL != new.baseURL, check != .checking { check = .idle }
            // Only an existing connection is saved as it changes; a new one on Add.
            if !isNew, store.configuration.connection(id: new.id) != nil { store.update { $0.upsert(new) } }
        }
        .onChange(of: apiKey) { _, key in
            if !isNew { store.setKey(AISettings.cleanKey(key), for: connection) }
            if check != .checking { check = .idle }
        }
        .macSheetFrame(width: 520, height: 680)
    }

    @ViewBuilder
    private var keyPageLabel: some View {
        switch connection.provider {
        case .ollama: Label("Install Ollama", systemImage: "arrow.up.right.square")
        case .vllm: Label("vLLM quick start", systemImage: "arrow.up.right.square")
        case .lmStudio: Label("Download LM Studio", systemImage: "arrow.up.right.square")
        default:
            let providerName = connection.provider.name
            Label("Create an API key at \(providerName)", systemImage: "arrow.up.right.square")
        }
    }

    @ViewBuilder
    private var checkStatus: some View {
        switch check {
        case .idle:
            EmptyView()
        case .checking:
            ProgressView().controlSize(.small)
        case .valid(let count):
            Label("\(count) models", systemImage: "checkmark.circle.fill")
                .foregroundStyle(Color.success)
                .font(.footnote)
        case .failed:
            Label("Not valid", systemImage: "xmark.octagon.fill")
                .foregroundStyle(Color.error)
                .font(.footnote)
        }
    }

    /// How to make the chosen server reachable, and which models work.
    @ViewBuilder
    private var localServerTips: some View {
        switch connection.provider {
        case .ollama:
            Text("Install a model that reads images, for example: ollama pull gemma3. To use it from iPhone or iPad, start Ollama with OLLAMA_HOST=0.0.0.0 on the computer.")
        case .vllm:
            Text("Start a vision model with: vllm serve <model> --host 0.0.0.0. If you set --api-key, enter the same key here.")
        case .lmStudio:
            Text("In LM Studio, load a vision model and start the server; turn on “Serve on Local Network” to use it from iPhone or iPad.")
        default:
            Text("Any server with an OpenAI-compatible /chat/completions API and a model that reads images.")
        }
        Text("Only the label photo is sent, to your own server. On iPhone and iPad, use the computer’s IP address or its name ending in .local instead of localhost.")
    }

    private func checkKey() {
        check = .checking
        let checked = connection
        let key = AISettings.cleanKey(apiKey)
        Task {
            do {
                let list = try await AIService.models(connection: checked, apiKey: key)
                // Changed while checking: the answer is about settings that are gone.
                guard isCurrent(checked, key: key) else { check = .idle; return }
                store.loadedModels[checked.id] = list
                check = .valid(list.count)
            } catch {
                check = isCurrent(checked, key: key) ? .failed(error.localizedDescription) : .idle
            }
        }
    }

    private func isCurrent(_ checked: AIConnection, key: String) -> Bool {
        checked.provider == connection.provider && checked.baseURL == connection.baseURL && key == AISettings.cleanKey(apiKey)
    }
}

// MARK: Models

/// Settings › AI › Models: the models that read label photos, in order. The first one
/// reads the photo; the next one only when it fails.
struct AIModelsView: View {
    @Bindable var store: AIConfigurationModel
    @State private var adding = false

    var body: some View {
        List {
            if store.configuration.connections.isEmpty {
                Section {
                    Text("Add a connection first, with the key of the AI service or the address of your server.")
                        .foregroundStyle(.secondary)
                    NavigationLink {
                        AIConnectionsView(store: store)
                    } label: {
                        Label("Connections", systemImage: "key")
                    }
                }
            }
            Section {
                ForEach(store.configuration.models) { model in
                    AIModelRow(model: model, connection: store.title(of: model), test: store.tests[model.id]) {
                        store.test(model)
                    }
                }
                .onMove { source, destination in
                    store.update { $0.moveModels(fromOffsets: source, toOffset: destination) }
                }
                .onDelete { offsets in
                    let ids = offsets.map { store.configuration.models[$0].id }
                    store.update { $0.models.removeAll { ids.contains($0.id) } }
                }
            } header: {
                Text("In order of preference")
            } footer: {
                Text("The label photo goes to the first model. If it cannot read it (no network, server not reachable, key refused, no credit left, too many requests, a server error, a refusal or an answer that cannot be read), the next one is tried, one at a time. Drag to change the order. Test sends a made-up label bundled with Encave, never your photos.")
            }
        }
        .navigationTitle("Models")
        .inlineTitle()
        .toolbar {
            #if os(iOS)
            ToolbarItem(placement: .topBarTrailing) {
                EditButton().disabled(store.configuration.models.isEmpty)
            }
            #endif
            ToolbarItem(placement: .primaryAction) {
                Button {
                    adding = true
                } label: {
                    Label("Add Model", systemImage: "plus")
                }
                .disabled(store.configuration.connections.isEmpty)
                .accessibilityIdentifier("ai.models.add")
            }
        }
        .sheet(isPresented: $adding) {
            NavigationStack { AddAIModelView(store: store) }
                .macSheetFrame(width: 520, height: 620)
        }
    }
}

private struct AIModelRow: View {
    let model: AIModelChoice
    let connection: String
    let test: AIConfigurationModel.ModelTest?
    let runTest: @MainActor () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: model.model)
                    Text(verbatim: connection).font(.footnote).foregroundStyle(.secondary)
                }
                .accessibilityElement(children: .combine)
                Spacer()
                switch test {
                case .testing:
                    ProgressView().controlSize(.small)
                case .passed:
                    Label("Works", systemImage: "checkmark.circle.fill")
                        .font(.footnote)
                        .foregroundStyle(Color.success)
                case .failed:
                    Label("Failed", systemImage: "xmark.octagon.fill")
                        .font(.footnote)
                        .foregroundStyle(Color.error)
                case nil:
                    EmptyView()
                }
                Button("Test", action: runTest)
                    .buttonStyle(.borderless)
                    .disabled(test == .testing)
            }
            if case .failed(let message) = test {
                Text(verbatim: message).font(.footnote).foregroundStyle(Color.error)
            }
        }
    }
}

/// Adds a model of a connection, from its list (checked with its key) or typed.
private struct AddAIModelView: View {
    @Bindable var store: AIConfigurationModel
    @Environment(\.dismiss) private var dismiss
    @State private var connectionID: UUID?
    @State private var model = ""
    @State private var typing = false
    @State private var loading = false
    @State private var problem: String?

    private var connection: AIConnection? { connectionID.flatMap { store.configuration.connection(id: $0) } }
    private var list: [String] { connectionID.flatMap { store.loadedModels[$0] } ?? [] }

    var body: some View {
        Form {
            Section {
                Picker("Connection", selection: $connectionID) {
                    ForEach(store.configuration.connections) { Text(verbatim: $0.title).tag(Optional($0.id)) }
                }
                if !list.isEmpty && !typing {
                    Picker("Model", selection: $model) {
                        if !list.contains(model) { Text("Choose…").tag(model) }
                        ForEach(list, id: \.self) { Text(verbatim: $0).tag($0) }
                    }
                    #if os(iOS)
                    .pickerStyle(.navigationLink)
                    #endif
                    Button("Enter a model name") { typing = true }
                } else {
                    let suggested = connection?.provider.suggestedModel ?? ""
                    TextField("Model", text: $model, prompt: Text(verbatim: suggested.isEmpty ? "model-name" : suggested))
                        .plainInput()
                    if !list.isEmpty {
                        Button("Choose from the list") { typing = false }
                    }
                }
                HStack {
                    Button {
                        load()
                    } label: {
                        Label("Load Models", systemImage: "arrow.down.circle")
                    }
                    .disabled(connection == nil || loading)
                    Spacer()
                    if loading { ProgressView().controlSize(.small) }
                }
            } footer: {
                VStack(alignment: .leading, spacing: 6) {
                    if let problem {
                        Text(verbatim: problem).foregroundStyle(Color.error)
                    }
                    if connection?.provider == .openRouter {
                        Text("Only the models that read images are listed.")
                    } else {
                        Text("Choose a model that reads images. Larger models are more accurate but slower and cost more.")
                    }
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Add Model")
        .inlineTitle()
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Add") {
                    guard let connectionID else { return }
                    store.update { _ = $0.addModel(model, connection: connectionID) }
                    dismiss()
                }
                .disabled(connectionID == nil || model.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .onAppear {
            guard connectionID == nil else { return }
            connectionID = store.configuration.connections.first?.id
        }
        .onChange(of: connectionID) { _, _ in
            problem = nil
            model = connection?.provider.suggestedModel ?? ""
            typing = list.isEmpty
            if list.isEmpty { load() }
        }
    }

    private func load() {
        guard let connection else { return }
        loading = true
        problem = nil
        let key = store.key(for: connection)
        Task {
            defer { loading = false }
            do {
                let models = try await AIService.models(connection: connection, apiKey: key)
                store.loadedModels[connection.id] = models
                guard connection.id == connectionID else { return }
                typing = models.isEmpty
                if !models.contains(model) { model = models.contains(connection.provider.suggestedModel) ? connection.provider.suggestedModel : "" }
            } catch {
                guard connection.id == connectionID else { return }
                problem = error.localizedDescription
                typing = true
            }
        }
    }
}
