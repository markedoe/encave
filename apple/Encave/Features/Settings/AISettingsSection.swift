import SwiftUI

/// Settings for the optional AI label recognition. The key stays in the Keychain.
struct AISettingsSection: View {
    @State private var settings = AISettings.load()
    @State private var apiKey = AISettings.apiKey ?? ""

    var body: some View {
        Section {
            Picker("Provider", selection: $settings.provider) {
                Text("Off").tag(AISettings.Provider.off)
                Text(verbatim: "Claude (Anthropic)").tag(AISettings.Provider.claude)
                Text("OpenAI-compatible").tag(AISettings.Provider.openAICompatible)
            }
            if settings.provider == .openAICompatible {
                TextField("Server URL", text: $settings.baseURL, prompt: Text(verbatim: AISettings.defaultOpenAIBaseURL))
                    .urlField()
            }
            if settings.isEnabled {
                TextField("Model", text: $settings.model,
                          prompt: Text(verbatim: settings.provider == .claude ? AISettings.defaultClaudeModel : "gpt-4o"))
                    .plainInput()
                SecureField("API key", text: $apiKey)
                    .plainInput()
            }
        } header: {
            Text("AI label recognition")
        } footer: {
            footer
        }
        .onChange(of: settings) { _, new in new.save() }
        .onChange(of: apiKey) { _, new in AISettings.apiKey = new }
    }

    @ViewBuilder
    private var footer: some View {
        if settings.isEnabled {
            Text("Only the label photo is sent, directly to this service, when you scan a label or tap “Complete with AI”. Your cellar is never sent. The key is stored in the Keychain on this device.")
        } else {
            Text("Optional. Reads the label from a photo with the AI service you choose, using your own API key.")
        }
    }
}
