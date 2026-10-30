import Foundation

/// A page the AI cited when it completed a wine from the web: its address and title.
/// Written in "webSources" on the wine (docs/SYNC.md §1).
public struct WineWebSource: Codable, Hashable, Sendable {
    public var url: String
    public var title: String?
    /// Keys written by a newer version, kept and written back unchanged (docs/SYNC.md §1).
    public var unknownFields = UnknownFields()

    private enum CodingKeys: String, CodingKey, CaseIterable { case url, title }

    public init(url: String, title: String? = nil) {
        self.url = url
        self.title = title.flatMap { $0.isEmpty ? nil : $0 }
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        url = try c.decode(String.self, forKey: .url)
        title = try c.decodeIfPresent(String.self, forKey: .title).flatMap { $0.isEmpty ? nil : $0 }
        unknownFields = try UnknownFields(from: decoder, known: CodingKeys.self)
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(url, forKey: .url)
        try c.encodeIfPresent(title, forKey: .title)
        try unknownFields.encode(to: encoder, known: CodingKeys.self)
    }

    /// Only http and https addresses are kept and opened.
    public var isWebAddress: Bool {
        guard let scheme = URL(string: url)?.scheme?.lowercased() else { return false }
        return scheme == "https" || scheme == "http"
    }
}

extension AIProvider {
    /// How the provider's API searches the web itself, nil when it does not (or not in a way
    /// Encave uses): Claude's web search tool, OpenRouter's web plugin, and the web search
    /// tool of the Responses API of OpenAI and xAI. Gemini's OpenAI-compatible address,
    /// Mistral, the Chinese services and servers of your own are left out.
    public var webSearch: AIWebSearch.Kind? {
        switch self {
        case .anthropic: .anthropicTool
        case .openRouter: .openRouterPlugin
        case .openAI, .xai: .responsesTool
        default: nil
        }
    }
}

public enum AIWebSearch {
    public enum Kind: String, Sendable { case anthropicTool, openRouterPlugin, responsesTool }

    /// The prompt for a model that searches: the JSON object is asked in words, as the
    /// search does not go with a strict output format everywhere.
    public static func prompt(_ prompt: String, schema: [String: Any]) -> String {
        let data = (try? JSONSerialization.data(withJSONObject: schema, options: [.sortedKeys])) ?? Data()
        return prompt + "\nAnswer with only a JSON object, without Markdown, following this JSON schema: "
            + (String(data: data, encoding: .utf8) ?? "{}")
    }

    /// Each address once, http or https only, at most `limit`.
    static func unique(_ sources: [WineWebSource], limit: Int = 8) -> [WineWebSource] {
        var seen = Set<String>()
        return Array(sources.filter { $0.isWebAddress && seen.insert($0.url).inserted }.prefix(limit))
    }

    /// Claude: the citations of its text, else the results of its searches.
    public static func claude(_ blocks: [[String: Any]]) -> [WineWebSource] {
        let cited = blocks.flatMap { ($0["citations"] as? [[String: Any]]) ?? [] }.compactMap { citation -> WineWebSource? in
            guard let url = citation["url"] as? String else { return nil }
            return WineWebSource(url: url, title: citation["title"] as? String)
        }
        if !cited.isEmpty { return unique(cited) }
        let results = blocks.filter { $0["type"] as? String == "web_search_tool_result" }
            .flatMap { ($0["content"] as? [[String: Any]]) ?? [] }
            .compactMap { item -> WineWebSource? in
                guard let url = item["url"] as? String else { return nil }
                return WineWebSource(url: url, title: item["title"] as? String)
            }
        return unique(results)
    }

    /// Chat Completions (OpenRouter): the message's url_citation annotations.
    public static func chatCompletions(_ message: [String: Any]) -> [WineWebSource] {
        unique(((message["annotations"] as? [[String: Any]]) ?? []).compactMap { annotation in
            guard let citation = annotation["url_citation"] as? [String: Any], let url = citation["url"] as? String else { return nil }
            return WineWebSource(url: url, title: citation["title"] as? String)
        })
    }

    /// Responses (OpenAI, xAI): the answer's text and its url_citation annotations.
    public static func responses(_ json: [String: Any]) -> (text: String?, sources: [WineWebSource]) {
        let parts = ((json["output"] as? [[String: Any]]) ?? []).filter { $0["type"] as? String == "message" }
            .flatMap { ($0["content"] as? [[String: Any]]) ?? [] }
            .filter { $0["type"] as? String == "output_text" }
        let text = parts.compactMap { $0["text"] as? String }.last(where: { $0.contains("{") })
        let sources = parts.flatMap { ($0["annotations"] as? [[String: Any]]) ?? [] }.compactMap { annotation -> WineWebSource? in
            guard annotation["type"] as? String == "url_citation", let url = annotation["url"] as? String else { return nil }
            return WineWebSource(url: url, title: annotation["title"] as? String)
        }
        return (text, unique(sources))
    }
}
