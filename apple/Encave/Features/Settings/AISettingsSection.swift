import SwiftUI

/// Settings for the optional AI features: provider, key (checked live), and model.
/// Keys stay in the Keychain on this device, one per provider.
struct AISettingsSection: View {
    @State private var settings = AISettings.load()
    @State private var apiKey = ""
    @State private var models: [String] = []
    @State private var check: KeyCheck = .idle
    @State private var customModel = false
    @State private var loaded = false

    enum KeyCheck: Equatable {
        case idle, checking, valid(Int), failed(String)
    }

    var body: some View {
        Section {
            Picker("Provider", selection: $settings.provider) {
                Text("Off").tag(AIProvider.off)
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
                    Text(verbatim: AIProvider.ollama.name).tag(AIProvider.ollama)
                    Text(verbatim: AIProvider.vllm.name).tag(AIProvider.vllm)
                    Text(verbatim: AIProvider.lmStudio.name).tag(AIProvider.lmStudio)
                    Text("Other server (OpenAI-compatible)").tag(AIProvider.local)
                }
            }
            .fieldHelp("The AI service that reads label photos. You need an account and an API key with it.")

            if settings.isEnabled {
                TextField("Server URL", text: $settings.baseURL,
                          prompt: Text(verbatim: settings.provider.defaultBaseURL))
                    .urlField()
                    .fieldHelp("Leave empty for the provider's usual address. For a local server, its address on your network.")
                Group {
                    if settings.provider.requiresKey {
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
                        if settings.provider.isLocal {
                            Label("Connect and Load Models", systemImage: "network")
                        } else {
                            Label("Check Key and Load Models", systemImage: "checkmark.shield")
                        }
                    }
                    .disabled(check == .checking || (settings.provider.requiresKey && apiKey.isEmpty))
                    Spacer()
                    checkStatus
                }
                if let page = settings.provider.keyPage {
                    Link(destination: page) {
                        switch settings.provider {
                        case .ollama:
                            Label("Install Ollama", systemImage: "arrow.up.right.square")
                        case .vllm:
                            Label("vLLM quick start", systemImage: "arrow.up.right.square")
                        case .lmStudio:
                            Label("Download LM Studio", systemImage: "arrow.up.right.square")
                        default:
                            let providerName = settings.provider.name
                            Label("Create an API key at \(providerName)", systemImage: "arrow.up.right.square")
                        }
                    }
                }
                HelpLink(topic: .ai, title: "How to set up AI")
                modelPicker
            }
        } header: {
            Text("AI")
        } footer: {
            footer
        }
        .onAppear {
            guard !loaded else { return }
            loaded = true
            apiKey = settings.apiKey ?? ""
            customModel = !settings.model.isEmpty
        }
        .onChange(of: settings.provider) { _, provider in
            apiKey = AISettings.apiKey(for: provider) ?? ""
            settings.model = AISettings.savedModel(for: provider)
            settings.baseURL = AISettings.savedBaseURL(for: provider)
            models = []
            check = .idle
            customModel = !settings.model.isEmpty
        }
        .onChange(of: settings.provider) { _, _ in settings.saveProvider() }
        .onChange(of: settings.model) { _, model in AISettings.saveModel(model, for: settings.provider) }
        .onChange(of: settings.baseURL) { _, url in AISettings.saveBaseURL(url, for: settings.provider) }
        .onChange(of: apiKey) { _, key in
            AISettings.setAPIKey(key, for: settings.provider)
            if check != .checking { check = .idle }
        }
    }

    @ViewBuilder
    private var modelPicker: some View {
        if !models.isEmpty && !customModel {
            Picker("Model", selection: $settings.model) {
                if settings.model.isEmpty || !models.contains(settings.model) {
                    Text("Choose…").tag(settings.model)
                }
                ForEach(models, id: \.self) { Text(verbatim: $0).tag($0) }
            }
            .fieldHelp("Choose a model that reads images. Larger models are more accurate but slower and cost more.")
            Button("Enter a model name") { customModel = true }
                .font(.footnote)
        } else {
            TextField("Model", text: $settings.model,
                      prompt: Text(verbatim: settings.provider.suggestedModel.isEmpty ? "model-name" : settings.provider.suggestedModel))
                .plainInput()
            if !models.isEmpty {
                Button("Choose from the list") { customModel = false }
                    .font(.footnote)
            }
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
                .foregroundStyle(.green)
                .font(.footnote)
        case .failed:
            Label("Not valid", systemImage: "xmark.octagon.fill")
                .foregroundStyle(.red)
                .font(.footnote)
        }
    }

    @ViewBuilder
    private var footer: some View {
        VStack(alignment: .leading, spacing: 6) {
            if case .failed(let message) = check {
                Text(verbatim: message).foregroundStyle(.red)
            }
            if !settings.isEnabled {
                Text("Optional. Reads labels from a photo with the AI service you choose, using your own API key.")
            } else if settings.provider.isLocal {
                localServerTips
            } else {
                Text("Leave the server URL empty to use the provider’s usual address.")
                Text("Only the label photo is sent, directly to this service, when you scan a label or tap “Complete with AI”. Your cellar is never sent. The key is stored in the Keychain on this device.")
            }
        }
    }

    /// How to make the chosen server reachable, and which models work.
    @ViewBuilder
    private var localServerTips: some View {
        switch settings.provider {
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
        let settings = settings
        let key = AISettings.cleanKey(apiKey)
        Task {
            do {
                let list = try await AIService.models(settings: settings, apiKey: key)
                models = list
                check = .valid(list.count)
                if self.settings.model.isEmpty || !list.contains(self.settings.model) {
                    let suggested = settings.provider.suggestedModel
                    self.settings.model = list.contains(suggested) ? suggested : (self.settings.model.isEmpty ? "" : self.settings.model)
                }
                customModel = list.isEmpty
            } catch {
                check = .failed(error.localizedDescription)
            }
        }
    }
}
