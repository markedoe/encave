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
        // A SearXNG server named on the home network gets the request at its local address.
        var request = URLRequest(url: try await LocalName.resolve(described.url))
        request.httpMethod = described.method
        request.timeoutInterval = 30
        for (field, value) in described.headers {
            request.setValue(value, forHTTPHeaderField: field)
        }
        request.httpBody = described.body
        let (data, response) = try await session.data(for: request, delegate: SameHostRedirects.shared)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        return try provider.results(from: data, status: status)
    }

    /// Pictures for the query, from the image search of the same service.
    static func searchImages(_ query: String, settings: WebSearchSettings = .load(), count: Int = 30) async throws -> [WebImageResult] {
        let provider = try settings.service.imageProvider(apiKey: settings.apiKey, serverURL: settings.serverURL)
        let described = try provider.imageRequest(for: query, count: count)
        var request = URLRequest(url: try await LocalName.resolve(described.url))
        request.httpMethod = described.method
        request.timeoutInterval = 30
        for (field, value) in described.headers {
            request.setValue(value, forHTTPHeaderField: field)
        }
        request.httpBody = described.body
        let (data, response) = try await session.data(for: request, delegate: SameHostRedirects.shared)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        return try provider.imageResults(from: data, status: status)
    }

    /// Downloads a picture (a thumbnail, or the one chosen) from its host: https only, never
    /// from this device or the local network (each redirect is checked too), an image type,
    /// at most `maxBytes`. No cookies or cache, as for the searches.
    static func downloadImage(_ url: URL, maxBytes: Int = WebImageDownload.maxBytes) async throws -> Data {
        guard WebImageDownload.isAllowed(url) else { throw WebSearchError.invalidResponse }
        var request = URLRequest(url: url)
        request.timeoutInterval = WebImageDownload.timeout
        let (bytes, response) = try await session.bytes(for: request, delegate: PublicImageRedirects.shared)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
              http.url.map(WebImageDownload.isAllowed) ?? true,
              WebImageDownload.isImage(contentType: http.value(forHTTPHeaderField: "Content-Type")),
              WebImageDownload.acceptsLength(response.expectedContentLength),
              response.expectedContentLength <= Int64(maxBytes) else {
            throw WebSearchError.invalidResponse
        }
        var data = Data()
        data.reserveCapacity(Int(min(max(response.expectedContentLength, 0), Int64(maxBytes))))
        for try await byte in bytes {
            data.append(byte)
            if data.count > maxBytes { throw WebSearchError.invalidResponse }
        }
        return data
    }

    /// What went wrong, in words.
    static func message(for error: Error) -> String {
        if let error = error as? LocalNetworkError { return error.localizedDescription }
        guard let error = error as? WebSearchError else {
            return String(localized: "The search service could not be reached. Check the connection and the address.")
        }
        switch error {
        case .missingKey:
            return String(localized: "Add your API key in Settings.")
        case .invalidServerURL:
            return String(localized: "Enter the full server address, starting with https:// (http:// only for a server on your local network).")
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

/// A picture's redirects go only to public https hosts (`WebImageDownload.isAllowed`); a
/// refused one ends the download.
final class PublicImageRedirects: NSObject, URLSessionTaskDelegate, Sendable {
    static let shared = PublicImageRedirects()

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest) async -> URLRequest? {
        guard let target = request.url, WebImageDownload.isAllowed(target) else { return nil }
        return request
    }
}

/// Follows a redirect only on the same host, over HTTPS (or HTTP on the local network, see
/// `SameHostRedirect`): the search terms, or the AI key and the photo, never go to another
/// host or in clear text. A refused redirect is answered with its own status.
final class SameHostRedirects: NSObject, URLSessionTaskDelegate, Sendable {
    static let shared = SameHostRedirects()

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest) async -> URLRequest? {
        guard let origin = task.originalRequest?.url, let target = request.url,
              SameHostRedirect.allows(from: origin, to: target) else { return nil }
        return request
    }
}

extension LocalNetworkError: @retroactive LocalizedError {
    /// For a server named on the home network over plain http (`LocalName`).
    public var errorDescription: String? {
        switch self {
        case .notLocal:
            String(localized: "http:// is only for a server on your local network: use https:// or a local address.")
        case .notFound:
            String(localized: "This server name could not be found on this network.")
        }
    }
}
