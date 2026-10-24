import Foundation

// Optional web search to complete a wine's details. Off by default. Only the search
// terms built from the wine (producer, name, appellation, vintage) are sent, to the
// provider the user sets up; never the cellar, notes, prices or places.
// This file holds the pure parts: the query, each provider's request and response,
// and the result model. The app sends the request.

/// One web page found.
public struct WebSearchResult: Identifiable, Hashable, Sendable {
    public var title: String
    public var url: URL
    public var snippet: String

    public var id: String { url.absoluteString }

    /// "example.com" for "https://www.example.com/page".
    public var domain: String {
        let host = url.host ?? ""
        return host.hasPrefix("www.") ? String(host.dropFirst(4)) : host
    }

    public init(title: String, url: URL, snippet: String) {
        self.title = title
        self.url = url
        self.snippet = snippet
    }

    /// Nil for links that would not open a web page (no http or https address).
    init?(title: String?, url: String?, snippet: String?) {
        guard let url, let parsed = URL(string: url.trimmingCharacters(in: .whitespaces)),
              let scheme = parsed.scheme?.lowercased(), scheme == "https" || scheme == "http",
              let host = parsed.host, !host.isEmpty else { return nil }
        let title = WebSearchText.plain(title ?? "")
        self.init(title: title.isEmpty ? host : title, url: parsed, snippet: WebSearchText.plain(snippet ?? ""))
    }
}

/// An HTTP request described without URLSession, so it can be tested anywhere.
public struct WebSearchRequest: Equatable, Sendable {
    public var url: URL
    public var method: String
    public var headers: [String: String]
    public var body: Data?
}

public enum WebSearchError: Error, Equatable, Sendable {
    case missingKey
    /// The server address is not a full https:// address (or http:// on the local network).
    case invalidServerURL
    /// The key was refused.
    case unauthorized
    /// A SearXNG instance without the JSON format enabled answers 403.
    case jsonFormatDisabled
    case rateLimited
    case server(status: Int)
    case invalidResponse
}

/// A search service. More can be added by implementing these two steps.
public protocol WebSearchProvider: Sendable {
    func request(for query: String, count: Int) throws -> WebSearchRequest
    /// Reads the answer; throws for an error status.
    func results(from data: Data, status: Int) throws -> [WebSearchResult]
}

/// The services offered in Settings.
public enum WebSearchService: String, CaseIterable, Sendable {
    case off
    case brave
    case searxng
    case tavily

    /// Brand names are not translated.
    public var name: String {
        switch self {
        case .off: ""
        case .brave: "Brave Search"
        case .searxng: "SearXNG"
        case .tavily: "Tavily"
        }
    }

    public var requiresKey: Bool { self == .brave || self == .tavily }
    /// SearXNG runs on the user's own server (a NAS, a computer) or an instance they trust.
    public var requiresServerURL: Bool { self == .searxng }

    /// Where to create a key, or to install SearXNG.
    public var setupPage: URL? {
        switch self {
        case .off: nil
        case .brave: URL(string: "https://api-dashboard.search.brave.com/app/keys")
        case .searxng: URL(string: "https://docs.searxng.org/admin/installation.html")
        case .tavily: URL(string: "https://app.tavily.com/home")
        }
    }

    public func provider(apiKey: String?, serverURL: String) throws -> any WebSearchProvider {
        let key = (apiKey ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        switch self {
        case .off: throw WebSearchError.invalidServerURL
        case .brave:
            guard !key.isEmpty else { throw WebSearchError.missingKey }
            return BraveSearch(apiKey: key)
        case .tavily:
            guard !key.isEmpty else { throw WebSearchError.missingKey }
            return TavilySearch(apiKey: key)
        case .searxng:
            return try SearXNGSearch(serverURL: serverURL)
        }
    }
}

// MARK: Query

public enum WebSearchQuery {
    /// "Domaine X Chablis 1er Cru 2019 wine": producer, name, appellation and vintage, without
    /// repeating a part already in another. Nothing else about the wine or the cellar.
    /// `wineWord` is "wine" in the user's language.
    public static func terms(producer: String?, name: String, appellation: String?, vintage: Int?,
                             wineWord: String = "wine") -> String {
        func clean(_ text: String?) -> String {
            (text ?? "").split(whereSeparator: \.isWhitespace).joined(separator: " ")
        }
        func contains(_ text: String, _ part: String) -> Bool {
            text.range(of: part, options: [.caseInsensitive, .diacriticInsensitive]) != nil
        }
        let name = clean(name), producer = clean(producer), appellation = clean(appellation)
        var parts: [String] = []
        // "Château Margaux" by "Château Margaux": once. A name within the producer's: the producer.
        let addsProducer = !producer.isEmpty && !contains(name, producer)
        if addsProducer { parts.append(producer) }
        if !name.isEmpty, !(addsProducer && contains(producer, name)) { parts.append(name) }
        let soFar = parts.joined(separator: " ")
        if !appellation.isEmpty, !contains(soFar, appellation) { parts.append(appellation) }
        if let vintage, (1000...9999).contains(vintage) { parts.append(String(vintage)) }
        let word = clean(wineWord)
        if !word.isEmpty, !parts.isEmpty { parts.append(word) }
        return parts.joined(separator: " ")
    }

    public static func terms(for wine: Wine, wineWord: String = "wine") -> String {
        terms(producer: wine.producer, name: wine.name, appellation: wine.appellation, vintage: wine.vintage,
              wineWord: wineWord)
    }

    /// Percent-encodes a query parameter value (spaces as %20, and "+", "&", "=" too).
    static func encode(_ value: String) -> String {
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? ""
    }
}

// MARK: Providers

/// Brave Search API: GET /res/v1/web/search, the key in X-Subscription-Token.
/// Answer: {"web": {"results": [{"title", "url", "description"}]}}.
public struct BraveSearch: WebSearchProvider {
    public var apiKey: String

    public init(apiKey: String) { self.apiKey = apiKey }

    public func request(for query: String, count: Int) throws -> WebSearchRequest {
        let count = min(max(count, 1), 20)
        let address = "https://api.search.brave.com/res/v1/web/search?q=\(WebSearchQuery.encode(query))&count=\(count)"
        guard let url = URL(string: address) else { throw WebSearchError.invalidResponse }
        return WebSearchRequest(url: url, method: "GET",
                                headers: ["Accept": "application/json", "X-Subscription-Token": apiKey], body: nil)
    }

    public func results(from data: Data, status: Int) throws -> [WebSearchResult] {
        try WebSearchStatus.check(status)
        struct Answer: Decodable {
            struct Web: Decodable { var results: [Item]? }
            struct Item: Decodable { var title: String?; var url: String?; var description: String? }
            var web: Web?
        }
        guard let answer = try? JSONDecoder().decode(Answer.self, from: data) else { throw WebSearchError.invalidResponse }
        return (answer.web?.results ?? []).compactMap { WebSearchResult(title: $0.title, url: $0.url, snippet: $0.description) }
    }
}

/// SearXNG: GET <server>/search?q=…&format=json. JSON must be enabled on the instance
/// (search: formats: [html, json] in settings.yml), or it answers 403.
/// Answer: {"results": [{"title", "url", "content"}]}.
public struct SearXNGSearch: WebSearchProvider {
    /// "http://nas.local:8080", without "/search" or a trailing slash.
    public let base: String

    public init(serverURL: String) throws {
        var base = serverURL.trimmingCharacters(in: .whitespacesAndNewlines)
        if let query = base.firstIndex(where: { $0 == "?" || $0 == "#" }) { base = String(base[..<query]) }
        while base.hasSuffix("/") { base.removeLast() }
        if base.lowercased().hasSuffix("/search") { base.removeLast("/search".count) }
        while base.hasSuffix("/") { base.removeLast() }
        // Plain HTTP only on the local network: elsewhere anyone on the way would read the search terms.
        // A name is checked when sending (`LocalName.resolve`).
        guard let url = URL(string: base), let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https", let host = url.host, !host.isEmpty,
              scheme == "https" || LocalName.mayBeLocal(host: host) else {
            throw WebSearchError.invalidServerURL
        }
        self.base = base
    }

    public func request(for query: String, count: Int) throws -> WebSearchRequest {
        guard let url = URL(string: "\(base)/search?q=\(WebSearchQuery.encode(query))&format=json") else {
            throw WebSearchError.invalidServerURL
        }
        return WebSearchRequest(url: url, method: "GET", headers: ["Accept": "application/json"], body: nil)
    }

    public func results(from data: Data, status: Int) throws -> [WebSearchResult] {
        if status == 403 { throw WebSearchError.jsonFormatDisabled }
        try WebSearchStatus.check(status)
        struct Answer: Decodable {
            struct Item: Decodable { var title: String?; var url: String?; var content: String? }
            var results: [Item]?
        }
        guard let answer = try? JSONDecoder().decode(Answer.self, from: data) else { throw WebSearchError.invalidResponse }
        return (answer.results ?? []).compactMap { WebSearchResult(title: $0.title, url: $0.url, snippet: $0.content) }
    }
}

/// Tavily: POST /search with a JSON body, the key as a Bearer token.
/// Answer: {"results": [{"title", "url", "content"}]}.
public struct TavilySearch: WebSearchProvider {
    public var apiKey: String

    public init(apiKey: String) { self.apiKey = apiKey }

    public func request(for query: String, count: Int) throws -> WebSearchRequest {
        guard let url = URL(string: "https://api.tavily.com/search") else { throw WebSearchError.invalidResponse }
        struct Body: Encodable {
            var query: String
            var max_results: Int
            var search_depth = "basic"
        }
        let body = try JSONEncoder().encode(Body(query: query, max_results: min(max(count, 1), 20)))
        return WebSearchRequest(url: url, method: "POST",
                                headers: ["Content-Type": "application/json", "Authorization": "Bearer \(apiKey)"],
                                body: body)
    }

    public func results(from data: Data, status: Int) throws -> [WebSearchResult] {
        try WebSearchStatus.check(status)
        struct Answer: Decodable {
            struct Item: Decodable { var title: String?; var url: String?; var content: String? }
            var results: [Item]?
        }
        guard let answer = try? JSONDecoder().decode(Answer.self, from: data) else { throw WebSearchError.invalidResponse }
        return (answer.results ?? []).compactMap { WebSearchResult(title: $0.title, url: $0.url, snippet: $0.content) }
    }
}

enum WebSearchStatus {
    static func check(_ status: Int) throws {
        switch status {
        case 200..<300: return
        case 401, 403: throw WebSearchError.unauthorized
        case 429, 432, 433: throw WebSearchError.rateLimited
        default: throw WebSearchError.server(status: status)
        }
    }
}

// MARK: Text

enum WebSearchText {
    /// Snippets carry HTML ("<strong>Chablis</strong> &amp; …"): plain text, on one line.
    static func plain(_ html: String) -> String {
        var text = ""
        var inTag = false
        for character in html {
            if character == "<" { inTag = true; continue }
            if character == ">", inTag { inTag = false; continue }
            if !inTag { text.append(character) }
        }
        return decodeEntities(text).split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }

    private static let named: [String: String] = [
        "amp": "&", "lt": "<", "gt": ">", "quot": "\"", "apos": "'", "nbsp": " ",
        "ndash": "–", "mdash": "—", "hellip": "…", "rsquo": "’", "lsquo": "‘", "ldquo": "“", "rdquo": "”",
    ]

    static func decodeEntities(_ text: String) -> String {
        guard text.contains("&") else { return text }
        var result = ""
        var index = text.startIndex
        while index < text.endIndex {
            let character = text[index]
            if character == "&", let end = text[index...].prefix(12).firstIndex(of: ";") {
                let entity = String(text[text.index(after: index)..<end])
                if let value = named[entity.lowercased()] ?? numeric(entity) {
                    result += value
                    index = text.index(after: end)
                    continue
                }
            }
            result.append(character)
            index = text.index(after: index)
        }
        return result
    }

    private static func numeric(_ entity: String) -> String? {
        guard entity.hasPrefix("#") else { return nil }
        let digits = entity.dropFirst()
        let code = digits.first == "x" || digits.first == "X" ? UInt32(digits.dropFirst(), radix: 16) : UInt32(digits)
        guard let code, let scalar = Unicode.Scalar(code) else { return nil }
        return String(Character(scalar))
    }
}

// MARK: Suggested details

/// The details of a wine that a web search can complete.
public struct WineDetails: Equatable, Sendable {
    public var producer: String
    public var region: String
    public var appellation: String
    public var country: String
    public var grapes: [String]
    public var drinkFrom: Int?
    public var drinkUntil: Int?

    public init(producer: String = "", region: String = "", appellation: String = "", country: String = "",
                grapes: [String] = [], drinkFrom: Int? = nil, drinkUntil: Int? = nil) {
        self.producer = producer; self.region = region; self.appellation = appellation; self.country = country
        self.grapes = grapes; self.drinkFrom = drinkFrom; self.drinkUntil = drinkUntil
    }

    public init(_ wine: Wine) {
        self.init(producer: wine.producer ?? "", region: wine.region ?? "", appellation: wine.appellation ?? "",
                  country: wine.country ?? "", grapes: wine.grapes, drinkFrom: wine.drinkFrom, drinkUntil: wine.drinkUntil)
    }

    /// Copies one field from `other`.
    public mutating func take(_ field: WineDetailField, from other: WineDetails) {
        switch field {
        case .producer: producer = other.producer
        case .region: region = other.region
        case .appellation: appellation = other.appellation
        case .country: country = other.country
        case .grapes: grapes = other.grapes
        case .drinkingWindow: drinkFrom = other.drinkFrom; drinkUntil = other.drinkUntil
        }
    }

    /// The value as shown next to a suggestion; empty when not set.
    public func text(of field: WineDetailField) -> String {
        switch field {
        case .producer: producer
        case .region: region
        case .appellation: appellation
        case .country: country
        case .grapes: grapes.joined(separator: ", ")
        case .drinkingWindow:
            if let drinkFrom, let drinkUntil { "\(drinkFrom) – \(drinkUntil)" } else { "" }
        }
    }
}

public enum WineDetailField: String, CaseIterable, Sendable {
    case producer, region, appellation, country, grapes, drinkingWindow
}

/// A value read in the search results, shown next to the current one until the user accepts it.
public struct WineDetailSuggestion: Identifiable, Equatable, Sendable {
    public var field: WineDetailField
    public var current: String
    public var proposed: String
    public var id: WineDetailField { field }
}

extension Wine {
    /// Applies the accepted fields of `details`.
    public mutating func apply(_ details: WineDetails, fields: Set<WineDetailField>) {
        func value(_ text: String) -> String? {
            let trimmed = text.trimmingCharacters(in: .whitespaces)
            return trimmed.isEmpty ? nil : trimmed
        }
        for field in fields {
            switch field {
            case .producer: producer = value(details.producer)
            case .region: region = value(details.region)
            case .appellation: appellation = value(details.appellation)
            case .country: country = value(details.country)
            case .grapes: grapes = details.grapes
            case .drinkingWindow:
                if let from = details.drinkFrom, let until = details.drinkUntil {
                    drinkFrom = from
                    drinkUntil = max(from, until)
                }
            }
        }
    }
}

/// Builds the prompt for the on-device model and checks what it proposes.
public enum WebSearchSuggestions {
    /// The search results, numbered, for the model on the device. Snippets are shortened
    /// so the prompt fits its small context.
    public static func prompt(terms: String, results: [WebSearchResult], limit: Int, snippetLength: Int = 320) -> String {
        let sources = results.prefix(limit).enumerated().map { index, result in
            let snippet = result.snippet.count > snippetLength
                ? String(result.snippet.prefix(snippetLength)) + "…" : result.snippet
            return "\(index + 1). \(result.title) (\(result.domain))\n\(snippet)"
        }.joined(separator: "\n\n")
        return """
        Below are web search results for this wine: \(terms).
        Using only what these results say about this wine, give its producer, region, appellation, \
        country (in English), grape varieties and drinking window (first and last year to drink it). \
        Leave a text empty, the grapes empty or a year at 0 when the results do not say it. Do not guess.

        \(sources)
        """
    }

    /// Drops what cannot be right: overlong text, too many grapes, impossible years.
    public static func cleaned(_ details: WineDetails, vintage: Int?, currentYear: Int) -> WineDetails {
        func text(_ value: String) -> String {
            let trimmed = value.split(whereSeparator: \.isWhitespace).joined(separator: " ")
            return trimmed.count > 80 ? "" : trimmed
        }
        var seen = Set<String>()
        let grapes = details.grapes.map(text)
            .filter { !$0.isEmpty && $0.count <= 40 && seen.insert($0.lowercased()).inserted }
        var result = WineDetails(producer: text(details.producer), region: text(details.region),
                                 appellation: text(details.appellation), country: text(details.country),
                                 grapes: grapes.count > 8 ? [] : grapes)
        if let from = details.drinkFrom, let until = details.drinkUntil,
           (1900...(currentYear + 100)).contains(from), (from...(currentYear + 100)).contains(until),
           from >= (vintage ?? 1900) {
            result.drinkFrom = from
            result.drinkUntil = until
        }
        return result
    }

    /// The proposed values that are set and differ from the current ones.
    public static func suggestions(current: WineDetails, proposed: WineDetails) -> [WineDetailSuggestion] {
        WineDetailField.allCases.compactMap { field in
            let new = proposed.text(of: field), old = current.text(of: field)
            guard !new.isEmpty else { return nil }
            let same: Bool
            if field == .grapes {
                same = Set(proposed.grapes.map { $0.lowercased() }) == Set(current.grapes.map { $0.lowercased() })
            } else {
                same = new.compare(old, options: [.caseInsensitive, .diacriticInsensitive]) == .orderedSame
            }
            return same ? nil : WineDetailSuggestion(field: field, current: old, proposed: new)
        }
    }
}

/// Which redirects a request to a search service (or an AI provider) may follow. The search
/// terms (or the key and the photo) go only where the user pointed: the same host, over HTTPS,
/// or over plain HTTP only from an HTTP server on the local network to the same host. Another
/// host, or HTTPS to HTTP, is refused.
public enum SameHostRedirect {
    public static func allows(from origin: URL, to target: URL) -> Bool {
        guard let host = origin.host?.lowercased(), !host.isEmpty, target.host?.lowercased() == host else { return false }
        let from = origin.scheme?.lowercased(), to = target.scheme?.lowercased()
        switch to {
        case "https": return from == "https" || from == "http"
        case "http": return from == "http" && LocalAddress.isLocal(host: host)
        default: return false
        }
    }
}
