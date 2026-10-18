import CaveKit
import SwiftUI

/// Settings for the optional web search: service, key or server, and a test search.
/// Keys stay in the Keychain on this device, one per service.
struct WebSearchSettingsSection: View {
    @State private var settings = WebSearchSettings.load()
    @State private var apiKey = ""
    @State private var test: TestState = .idle
    @State private var loaded = false

    enum TestState: Equatable {
        case idle, testing, worked(Int), failed(String)
    }

    private var canTest: Bool {
        test != .testing && (!settings.service.requiresKey || !apiKey.isEmpty)
            && (!settings.service.requiresServerURL || !settings.serverURL.trimmingCharacters(in: .whitespaces).isEmpty)
    }

    var body: some View {
        Section {
            Picker("Search Service", selection: $settings.service) {
                Text("Off").tag(WebSearchService.off)
                Section {
                    Text(verbatim: WebSearchService.brave.name).tag(WebSearchService.brave)
                    Text(verbatim: WebSearchService.tavily.name).tag(WebSearchService.tavily)
                }
                Section {
                    Text(verbatim: WebSearchService.searxng.name).tag(WebSearchService.searxng)
                }
            }
            .fieldHelp("The search engine that looks up a wine on the web when you tap Search Online.")

            if settings.isEnabled {
                if settings.service.requiresServerURL {
                    TextField("Server URL", text: $settings.serverURL, prompt: Text(verbatim: "http://nas.local:8080"))
                        .urlField()
                        .fieldHelp("The address of your SearXNG server, on your network or online.")
                }
                if settings.service.requiresKey {
                    SecureField("API key", text: $apiKey)
                        .plainInput()
                        .fieldHelp("Created on the service's website. Stored in the Keychain on this device.")
                }
                HStack {
                    Button(action: runTest) {
                        Label("Test Search", systemImage: "magnifyingglass")
                    }
                    .disabled(!canTest)
                    Spacer()
                    testStatus
                }
                .accessibilityHint(Text("Searches for “Chablis wine” to check the settings."))
                if let page = settings.service.setupPage {
                    Link(destination: page) {
                        if settings.service.requiresServerURL {
                            Label("Install SearXNG", systemImage: "arrow.up.right.square")
                        } else {
                            let serviceName = settings.service.name
                            Label("Create an API key at \(serviceName)", systemImage: "arrow.up.right.square")
                        }
                    }
                }
                HelpLink(topic: .webSearch, title: "How to set up web search")
            }
        } header: {
            Text("Web Search")
        } footer: {
            footer
        }
        .onAppear {
            guard !loaded else { return }
            loaded = true
            apiKey = settings.apiKey ?? ""
        }
        .onChange(of: settings.service) { _, service in
            apiKey = WebSearchSettings.apiKey(for: service) ?? ""
            settings.serverURL = WebSearchSettings.savedServerURL(for: service)
            test = .idle
            settings.saveService()
        }
        .onChange(of: settings.serverURL) { _, url in
            WebSearchSettings.saveServerURL(url, for: settings.service)
            if test != .testing { test = .idle }
        }
        .onChange(of: apiKey) { _, key in
            WebSearchSettings.setAPIKey(key, for: settings.service)
            if test != .testing { test = .idle }
        }
    }

    @ViewBuilder
    private var testStatus: some View {
        switch test {
        case .idle:
            EmptyView()
        case .testing:
            ProgressView().controlSize(.small)
        case .worked(let count):
            Label("\(count) results", systemImage: "checkmark.circle.fill")
                .foregroundStyle(.green)
                .font(.footnote)
        case .failed:
            Label("Not working", systemImage: "xmark.octagon.fill")
                .foregroundStyle(.red)
                .font(.footnote)
        }
    }

    @ViewBuilder
    private var footer: some View {
        VStack(alignment: .leading, spacing: 6) {
            if case .failed(let message) = test {
                Text(verbatim: message).foregroundStyle(.red)
            }
            if !settings.isEnabled {
                Text("Optional. Looks up a wine on the web to complete its details, with the search service you choose.")
            } else {
                if settings.service.requiresServerURL {
                    Text("SearXNG must answer in JSON: add json under search: formats: in its settings.yml.")
                }
                Text("Only the search terms are sent, directly to this service, when you tap Search: the producer, name, appellation and vintage of the wine, and the bottle size for a price. You see them and can change them first. Your cellar, notes and prices are never sent.")
            }
        }
    }

    private func runTest() {
        test = .testing
        let settings = settings
        let key = AISettings.cleanKey(apiKey)
        Task {
            do {
                let results = try await WebSearchClient.search(String(localized: "Chablis wine"), settings: settings,
                                                               apiKey: key, count: 5)
                // Changed while testing: the result is about settings that are gone.
                test = isCurrent(settings, key: key) ? .worked(results.count) : .idle
            } catch {
                test = isCurrent(settings, key: key) ? .failed(WebSearchClient.message(for: error)) : .idle
            }
        }
    }

    private func isCurrent(_ tested: WebSearchSettings, key: String) -> Bool {
        tested.service == settings.service && tested.serverURL == settings.serverURL && key == AISettings.cleanKey(apiKey)
    }
}
