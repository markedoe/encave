import CaveKit
import Foundation

/// Optional web search to complete a wine's details, with a service the user chooses.
/// Off by default. Only the search terms are sent, never the cellar.
struct WebSearchSettings: Equatable, Sendable {
    var service: WebSearchService = .off
    /// A SearXNG server's address, such as http://nas.local:8080.
    var serverURL = ""

    var isEnabled: Bool { service != .off }

    static func load(from defaults: UserDefaults = .standard) -> WebSearchSettings {
        var settings = WebSearchSettings()
        settings.service = WebSearchService(rawValue: defaults.string(forKey: "webSearch.service") ?? "off") ?? .off
        settings.serverURL = savedServerURL(for: settings.service, defaults: defaults)
        return settings
    }

    func saveService(to defaults: UserDefaults = .standard) {
        defaults.set(service.rawValue, forKey: "webSearch.service")
    }

    static func savedServerURL(for service: WebSearchService, defaults: UserDefaults = .standard) -> String {
        defaults.string(forKey: "webSearch.serverURL.\(service.rawValue)") ?? ""
    }

    static func saveServerURL(_ url: String, for service: WebSearchService, defaults: UserDefaults = .standard) {
        defaults.set(url, forKey: "webSearch.serverURL.\(service.rawValue)")
    }

    /// One key per service, in the Keychain on this device.
    static func apiKey(for service: WebSearchService) -> String? {
        Keychain.get("webSearch.apiKey.\(service.rawValue)").map(AISettings.cleanKey)
    }

    static func setAPIKey(_ key: String?, for service: WebSearchService) {
        Keychain.set(key.map(AISettings.cleanKey), for: "webSearch.apiKey.\(service.rawValue)")
    }

    var apiKey: String? { WebSearchSettings.apiKey(for: service) }
}

enum WebSearchClient {
    /// No cookies or cache: each search stands alone.
    private static let session = URLSession(configuration: .ephemeral)

    /// Sends the query, and nothing else, to the service set up in Settings.
    static func search(_ query: String, settings: WebSearchSettings = .load(), apiKey: String? = nil,
                       count: Int = 10) async throws -> [WebSearchResult] {
        let provider = try settings.service.provider(apiKey: apiKey ?? settings.apiKey, serverURL: settings.serverURL)
        let described = try provider.request(for: query, count: count)
        var request = URLRequest(url: described.url)
        request.httpMethod = described.method
        request.timeoutInterval = 30
        for (field, value) in described.headers {
            request.setValue(value, forHTTPHeaderField: field)
        }
        request.httpBody = described.body
        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        return try provider.results(from: data, status: status)
    }

    /// What went wrong, in words.
    static func message(for error: Error) -> String {
        guard let error = error as? WebSearchError else {
            return String(localized: "The search service could not be reached. Check the connection and the address.")
        }
        switch error {
        case .missingKey:
            return String(localized: "Add your API key in Settings.")
        case .invalidServerURL:
            return String(localized: "Enter the full server address, starting with http:// or https://.")
        case .unauthorized:
            return String(localized: "The search service refused the key. Check it in Settings.")
        case .jsonFormatDisabled:
            return String(localized: "This SearXNG server does not answer in JSON. Add json under search: formats: in its settings.yml, then restart it.")
        case .rateLimited:
            return String(localized: "Too many searches for now, or the free allowance is used up. Try again later.")
        case .server(let status):
            return String(localized: "The search service returned error \(status).")
        case .invalidResponse:
            return String(localized: "The answer from the search service could not be read.")
        }
    }
}
