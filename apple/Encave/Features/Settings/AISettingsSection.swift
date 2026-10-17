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
                    Text("Local server (Ollama, LM Studio…)").tag(AIProvider.local)
                }
            }

            if settings.isEnabled {
                if settings.provider == .local {
                    TextField("Server URL", text: $settings.baseURL,
                              prompt: Text(verbatim: AIProvider.local.defaultBaseURL))
                        .urlField()
                }
                Group {
                    if settings.provider.requiresKey {
                        SecureField("API key", text: $apiKey)
                    } else {
                        SecureField("API key (optional)", text: $apiKey)
                    }
                }
                .plainInput()
                .onSubmit(checkKey)
                HStack {
                    Button(action: checkKey) {
                        Label("Check Key and Load Models", systemImage: "checkmark.shield")
                    }
                    .disabled(check == .checking || (settings.provider.requiresKey && apiKey.isEmpty))
                    Spacer()
                    checkStatus
                }
                if let page = settings.provider.keyPage {
                    Link(destination: page) {
                        Label("Get an API key", systemImage: "arrow.up.right.square")
                    }
                }
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
            models = []
            check = .idle
            customModel = !settings.model.isEmpty
        }
        .onChange(of: settings) { _, new in new.save() }
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
            } else if settings.provider == .local {
                Text("On iPhone and iPad, use your computer’s address on the network instead of localhost. Choose a model that reads images for label recognition.")
            } else {
                Text("Only the label photo is sent, directly to this service, when you scan a label or tap “Complete with AI”. Your cellar is never sent. The key is stored in the Keychain on this device.")
            }
        }
    }

    private func checkKey() {
        check = .checking
        let settings = settings
        let key = apiKey
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
