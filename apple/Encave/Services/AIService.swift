import CaveKit
import Foundation

/// Optional label recognition by an AI model the user chooses and pays for.
/// Off by default. When on, only the label photo is sent, never the cellar.
struct AISettings: Equatable, Sendable {
    enum Provider: String, CaseIterable, Sendable {
        case off
        case claude
        /// OpenAI, a local OpenWebUI or Ollama server, or any compatible API.
        case openAICompatible
    }

    var provider: Provider = .off
    var model = ""
    var baseURL = ""

    static let defaultClaudeModel = "claude-opus-5-5"
    static let defaultOpenAIBaseURL = "https://api.openai.com/v1"
    private static let keychainAccount = "ai.apiKey"

    var isEnabled: Bool { provider != .off }

    var effectiveModel: String {
        let trimmed = model.trimmingCharacters(in: .whitespaces)
        if !trimmed.isEmpty { return trimmed }
        return provider == .claude ? Self.defaultClaudeModel : ""
    }

    var effectiveBaseURL: String {
        let trimmed = baseURL.trimmingCharacters(in: .whitespaces)
        return trimmed.isEmpty ? Self.defaultOpenAIBaseURL : trimmed
    }

    static func load(from defaults: UserDefaults = .standard) -> AISettings {
        var settings = AISettings()
        settings.provider = defaults.string(forKey: "ai.provider").flatMap(Provider.init(rawValue:)) ?? .off
        settings.model = defaults.string(forKey: "ai.model") ?? ""
        settings.baseURL = defaults.string(forKey: "ai.baseURL") ?? ""
        return settings
    }

    func save(to defaults: UserDefaults = .standard) {
        defaults.set(provider.rawValue, forKey: "ai.provider")
        defaults.set(model, forKey: "ai.model")
        defaults.set(baseURL, forKey: "ai.baseURL")
    }

    static var apiKey: String? {
        get { Keychain.get(keychainAccount) }
        set { Keychain.set(newValue, for: keychainAccount) }
    }
}

/// What the model read on the label. Empty strings and zeros mean "unknown".
struct LabelAnalysis: Codable, Equatable, Sendable {
    var name: String
    var producer: String
    var vintage: Int
    var color: String
    var appellation: String
    var region: String
    var country: String
    var grapes: [String]
    var drinkFrom: Int
    var drinkUntil: Int

    var wineColor: WineColor? { WineColor(rawValue: color) }

    /// Fills a draft. A field is replaced only if it is empty or still holds the value filled
    /// automatically before (recorded in `filled`), so what the user typed is kept.
    func apply(to draft: inout WineDraft, replacing filled: inout WineDraft) {
        func fill(_ field: WritableKeyPath<WineDraft, String>, _ value: String) {
            let value = value.trimmingCharacters(in: .whitespaces)
            let current = draft[keyPath: field].trimmingCharacters(in: .whitespaces)
            if !value.isEmpty, current.isEmpty || current == filled[keyPath: field] {
                draft[keyPath: field] = value
                filled[keyPath: field] = value
            }
        }
        fill(\.name, name)
        fill(\.producer, producer)
        fill(\.appellation, appellation)
        fill(\.region, region)
        fill(\.country, country)
        fill(\.grapes, grapes.joined(separator: ", "))
        if (1900...2100).contains(vintage), draft.vintage == nil || draft.vintage == filled.vintage {
            draft.vintage = vintage
            filled.vintage = vintage
        }
        if let wineColor, draft.color == nil || draft.color == filled.color {
            draft.color = wineColor
            filled.color = wineColor
        }
        if draft.windowIsAutomatic, drinkFrom > 1900, drinkUntil >= drinkFrom {
            draft.windowIsAutomatic = false
            draft.drinkFrom = drinkFrom
            draft.drinkUntil = drinkUntil
        }
    }

    static var schema: JSONObject {
        [
            "type": "object",
            "additionalProperties": false,
            "required": ["name", "producer", "vintage", "color", "appellation", "region", "country",
                         "grapes", "drinkFrom", "drinkUntil"],
            "properties": [
                "name": ["type": "string"],
                "producer": ["type": "string"],
                "vintage": ["type": "integer"],
                "color": ["type": "string", "enum": [""] + WineColor.allCases.map(\.rawValue)] as JSONObject,
                "appellation": ["type": "string"],
                "region": ["type": "string"],
                "country": ["type": "string"],
                "grapes": ["type": "array", "items": ["type": "string"]] as JSONObject,
                "drinkFrom": ["type": "integer"],
                "drinkUntil": ["type": "integer"],
            ] as JSONObject,
        ]
    }

    static let prompt = """
    This is a photo of a wine label. Read it and return the wine's details.
    - name: the wine or cuvée name as printed (for example the château or domaine and cuvée), without the vintage.
    - producer: the estate, domaine or house, if different from the name; otherwise "".
    - vintage: the year on the label, or 0 for a non-vintage wine.
    - color: one of red, white, rose, sparkling, dessert, fortified, orange; "" if you cannot tell.
    - appellation, region, country: from the label, or from your knowledge of the appellation. Country in English.
    - grapes: the grape varieties if printed or typical of the appellation; [] if unsure.
    - drinkFrom and drinkUntil: a realistic drinking window in years for this wine and vintage, or 0 if unknown.
    Use "" or 0 for anything you cannot determine. Do not invent a producer or vintage that is not visible.
    """
}

typealias JSONObject = [String: Any]

enum AIServiceError: LocalizedError {
    case notConfigured
    case missingKey
    case refused
    case server(status: Int, message: String)
    case invalidResponse

    var errorDescription: String? {
        switch self {
        case .notConfigured: String(localized: "Choose an AI provider in Settings.")
        case .missingKey: String(localized: "Add your API key in Settings.")
        case .refused: String(localized: "The model declined to read this photo.")
        case .server(let status, let message):
            message.isEmpty ? String(localized: "The AI service returned error \(status).") : message
        case .invalidResponse: String(localized: "The answer from the AI service could not be read.")
        }
    }
}

enum AIService {
    /// Sends the label photo (a resized JPEG) to the configured provider.
    static func analyzeLabel(jpeg: Data, settings: AISettings = .load(), apiKey: String? = AISettings.apiKey) async throws -> LabelAnalysis {
        switch settings.provider {
        case .off:
            throw AIServiceError.notConfigured
        case .claude:
            guard let apiKey, !apiKey.isEmpty else { throw AIServiceError.missingKey }
            return try await claude(jpeg: jpeg, model: settings.effectiveModel, apiKey: apiKey)
        case .openAICompatible:
            guard !settings.effectiveModel.isEmpty else { throw AIServiceError.notConfigured }
            return try await openAICompatible(jpeg: jpeg, settings: settings, apiKey: apiKey)
        }
    }

    // MARK: Claude (Anthropic Messages API)

    private static func claude(jpeg: Data, model: String, apiKey: String) async throws -> LabelAnalysis {
        var request = URLRequest(url: URL(string: "https://api.anthropic.com/v1/messages")!)
        request.httpMethod = "POST"
        request.timeoutInterval = 120
        request.setValue(apiKey, forHTTPHeaderField: "x-api-key")
        request.setValue("2023-06-01", forHTTPHeaderField: "anthropic-version")
        // Re-runs a declined request on another model instead of returning a refusal.
        request.setValue("server-side-fallback-2026-07-01", forHTTPHeaderField: "anthropic-beta")
        request.setValue("application/json", forHTTPHeaderField: "content-type")
        let body: JSONObject = [
            "model": model,
            "max_tokens": 8000,
            "fallbacks": "default",
            "output_config": [
                "effort": "low",
                "format": ["type": "json_schema", "schema": LabelAnalysis.schema] as JSONObject,
            ] as JSONObject,
            "messages": [[
                "role": "user",
                "content": [
                    ["type": "image",
                     "source": ["type": "base64", "media_type": "image/jpeg", "data": jpeg.base64EncodedString()]] as JSONObject,
                    ["type": "text", "text": LabelAnalysis.prompt],
                ] as [JSONObject],
            ] as JSONObject],
        ]
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let json = try await send(request)
        if json["stop_reason"] as? String == "refusal" { throw AIServiceError.refused }
        let blocks = json["content"] as? [[String: Any]] ?? []
        guard let text = blocks.first(where: { $0["type"] as? String == "text" })?["text"] as? String else {
            throw AIServiceError.invalidResponse
        }
        return try decode(text)
    }

    // MARK: OpenAI-compatible (Chat Completions)

    private static func openAICompatible(jpeg: Data, settings: AISettings, apiKey: String?) async throws -> LabelAnalysis {
        let base = settings.effectiveBaseURL.hasSuffix("/") ? String(settings.effectiveBaseURL.dropLast()) : settings.effectiveBaseURL
        guard let url = URL(string: base + "/chat/completions"), url.scheme == "https" || url.scheme == "http" else {
            throw AIServiceError.notConfigured
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 120
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let apiKey, !apiKey.isEmpty {
            request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        }
        let body: JSONObject = [
            "model": settings.effectiveModel,
            "response_format": [
                "type": "json_schema",
                "json_schema": ["name": "wine_label", "strict": true, "schema": LabelAnalysis.schema] as JSONObject,
            ] as JSONObject,
            "messages": [[
                "role": "user",
                "content": [
                    ["type": "text", "text": LabelAnalysis.prompt],
                    ["type": "image_url",
                     "image_url": ["url": "data:image/jpeg;base64,\(jpeg.base64EncodedString())"]] as JSONObject,
                ] as [JSONObject],
            ] as JSONObject],
        ]
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let json = try await send(request)
        let choices = json["choices"] as? [[String: Any]] ?? []
        let message = choices.first?["message"] as? [String: Any]
        if let refusal = message?["refusal"] as? String, !refusal.isEmpty { throw AIServiceError.refused }
        guard let text = message?["content"] as? String else { throw AIServiceError.invalidResponse }
        return try decode(text)
    }

    // MARK: Helpers

    private static func send(_ request: URLRequest) async throws -> [String: Any] {
        let (data, response) = try await URLSession.shared.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        guard (200..<300).contains(status) else {
            let error = json?["error"] as? [String: Any]
            throw AIServiceError.server(status: status, message: error?["message"] as? String ?? "")
        }
        guard let json else { throw AIServiceError.invalidResponse }
        return json
    }

    /// Parses the JSONObject answer, tolerating a Markdown code fence from servers without strict schemas.
    static func decode(_ text: String) throws -> LabelAnalysis {
        var text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if let start = text.firstIndex(of: "{"), let end = text.lastIndex(of: "}") {
            text = String(text[start...end])
        }
        guard let analysis = try? JSONDecoder().decode(LabelAnalysis.self, from: Data(text.utf8)) else {
            throw AIServiceError.invalidResponse
        }
        return analysis
    }
}
