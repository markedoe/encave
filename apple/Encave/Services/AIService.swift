import CaveKit
import Foundation

/// AI services the user can connect with their own key, plus a server of their own.
/// Most speak the OpenAI chat format; Claude has its own API.
enum AIProvider: String, CaseIterable, Sendable {
    case off
    case openAI, anthropic, google, xai, mistral
    case deepseek, qwen, kimi, zhipu, minimax
    case openRouter
    /// Ollama, LM Studio, OpenWebUI or any OpenAI-compatible server.
    case local

    /// Brand names are not translated.
    var name: String {
        switch self {
        case .off: ""
        case .openAI: "ChatGPT (OpenAI)"
        case .anthropic: "Claude (Anthropic)"
        case .google: "Gemini (Google)"
        case .xai: "Grok (xAI)"
        case .mistral: "Mistral AI"
        case .deepseek: "DeepSeek"
        case .qwen: "Qwen (Alibaba Cloud)"
        case .kimi: "Kimi (Moonshot AI)"
        case .zhipu: "GLM (Z.ai)"
        case .minimax: "MiniMax"
        case .openRouter: "OpenRouter"
        case .local: ""
        }
    }

    var usesAnthropicAPI: Bool { self == .anthropic }

    var defaultBaseURL: String {
        switch self {
        case .off: ""
        case .openAI: "https://api.openai.com/v1"
        case .anthropic: "https://api.anthropic.com/v1"
        case .google: "https://generativelanguage.googleapis.com/v1beta/openai"
        case .xai: "https://api.x.ai/v1"
        case .mistral: "https://api.mistral.ai/v1"
        case .deepseek: "https://api.deepseek.com/v1"
        case .qwen: "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
        case .kimi: "https://api.moonshot.ai/v1"
        case .zhipu: "https://api.z.ai/api/paas/v4"
        case .minimax: "https://api.minimax.io/v1"
        case .openRouter: "https://openrouter.ai/api/v1"
        case .local: "http://localhost:11434/v1"
        }
    }

    /// A key is optional only for a server of your own.
    var requiresKey: Bool { self != .local && self != .off }

    /// Model used until the list is loaded, when one is known to read images.
    var suggestedModel: String {
        switch self {
        case .anthropic: "claude-opus-5-5"
        default: ""
        }
    }

    /// Where to create a key, for the providers whose page is stable.
    var keyPage: URL? {
        switch self {
        case .openAI: URL(string: "https://platform.openai.com/api-keys")
        case .anthropic: URL(string: "https://console.anthropic.com/settings/keys")
        case .google: URL(string: "https://aistudio.google.com/apikey")
        case .xai: URL(string: "https://console.x.ai")
        case .mistral: URL(string: "https://console.mistral.ai/api-keys")
        case .deepseek: URL(string: "https://platform.deepseek.com/api_keys")
        case .openRouter: URL(string: "https://openrouter.ai/keys")
        default: nil
        }
    }

    static var global: [AIProvider] { [.openAI, .anthropic, .google, .xai, .mistral] }
    static var chinese: [AIProvider] { [.deepseek, .qwen, .kimi, .zhipu, .minimax] }
}

/// Optional AI features, with a model and key the user chooses and pays for.
/// Off by default. Label recognition sends only the label photo, never the cellar.
struct AISettings: Equatable, Sendable {
    var provider: AIProvider = .off
    var model = ""
    /// Only for a local server (or to override a provider's address).
    var baseURL = ""

    var isEnabled: Bool { provider != .off }

    var effectiveModel: String {
        let trimmed = model.trimmingCharacters(in: .whitespaces)
        return trimmed.isEmpty ? provider.suggestedModel : trimmed
    }

    var effectiveBaseURL: String {
        let trimmed = baseURL.trimmingCharacters(in: .whitespaces)
        let base = trimmed.isEmpty || provider != .local ? provider.defaultBaseURL : trimmed
        return base.hasSuffix("/") ? String(base.dropLast()) : base
    }

    static func load(from defaults: UserDefaults = .standard) -> AISettings {
        var settings = AISettings()
        let raw = defaults.string(forKey: "ai.provider") ?? "off"
        settings.baseURL = defaults.string(forKey: "ai.baseURL") ?? ""
        switch raw {
        // Names used by the first test builds.
        case "claude": settings.provider = .anthropic
        case "openAICompatible":
            settings.provider = settings.baseURL.isEmpty || settings.baseURL.contains("api.openai.com") ? .openAI : .local
        default: settings.provider = AIProvider(rawValue: raw) ?? .off
        }
        settings.model = defaults.string(forKey: "ai.model.\(settings.provider.rawValue)")
            ?? defaults.string(forKey: "ai.model") ?? ""
        return settings
    }

    func save(to defaults: UserDefaults = .standard) {
        defaults.set(provider.rawValue, forKey: "ai.provider")
        defaults.set(model, forKey: "ai.model.\(provider.rawValue)")
        defaults.set(baseURL, forKey: "ai.baseURL")
    }

    /// Model chosen earlier for a provider, so switching back keeps it.
    static func savedModel(for provider: AIProvider, defaults: UserDefaults = .standard) -> String {
        defaults.string(forKey: "ai.model.\(provider.rawValue)") ?? ""
    }

    /// One key per provider, in the Keychain on this device.
    static func apiKey(for provider: AIProvider) -> String? {
        Keychain.get("ai.apiKey.\(provider.rawValue)") ?? (provider == .anthropic ? Keychain.get("ai.apiKey") : nil)
    }

    static func setAPIKey(_ key: String?, for provider: AIProvider) {
        Keychain.set(key, for: "ai.apiKey.\(provider.rawValue)")
    }

    var apiKey: String? { AISettings.apiKey(for: provider) }
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
    case noModel
    case refused
    case server(status: Int, message: String)
    case invalidResponse

    var errorDescription: String? {
        switch self {
        case .notConfigured: String(localized: "Choose an AI provider in Settings.")
        case .missingKey: String(localized: "Add your API key in Settings.")
        case .noModel: String(localized: "Choose a model in Settings.")
        case .refused: String(localized: "The model declined to read this photo.")
        case .server(let status, let message):
            message.isEmpty ? String(localized: "The AI service returned error \(status).") : message
        case .invalidResponse: String(localized: "The answer from the AI service could not be read.")
        }
    }
}

enum AIService {
    /// Sends the label photo (a resized JPEG) to the configured provider.
    static func analyzeLabel(jpeg: Data, settings: AISettings = .load()) async throws -> LabelAnalysis {
        let apiKey = settings.apiKey
        guard settings.isEnabled else { throw AIServiceError.notConfigured }
        if settings.provider.requiresKey, (apiKey ?? "").isEmpty { throw AIServiceError.missingKey }
        guard !settings.effectiveModel.isEmpty else { throw AIServiceError.noModel }
        if settings.provider.usesAnthropicAPI {
            return try await claude(jpeg: jpeg, model: settings.effectiveModel, apiKey: apiKey ?? "")
        }
        return try await openAICompatible(jpeg: jpeg, settings: settings, apiKey: apiKey)
    }

    /// Checks the key by listing the models it can use. Returns their identifiers, sorted.
    static func models(settings: AISettings, apiKey: String?) async throws -> [String] {
        guard settings.isEnabled else { throw AIServiceError.notConfigured }
        if settings.provider.requiresKey, (apiKey ?? "").isEmpty { throw AIServiceError.missingKey }
        guard let url = URL(string: settings.effectiveBaseURL + "/models" + (settings.provider.usesAnthropicAPI ? "?limit=100" : "")) else {
            throw AIServiceError.notConfigured
        }
        var request = URLRequest(url: url)
        request.timeoutInterval = 30
        if settings.provider.usesAnthropicAPI {
            request.setValue(apiKey, forHTTPHeaderField: "x-api-key")
            request.setValue("2023-06-01", forHTTPHeaderField: "anthropic-version")
        } else if let apiKey, !apiKey.isEmpty {
            request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        }
        let json = try await send(request)
        let items = (json["data"] as? [[String: Any]]) ?? (json["models"] as? [[String: Any]]) ?? []
        // Gemini lists "models/gemini-…"; chat requests take the bare name.
        let ids = items.compactMap { (($0["id"] as? String) ?? ($0["name"] as? String))?.replacingOccurrences(of: "models/", with: "") }
        // Leave out models that cannot chat: embeddings, speech, image generation, moderation.
        let excluded = ["embed", "tts", "whisper", "dall-e", "audio", "realtime", "moderation", "transcribe",
                        "gpt-image", "imagen", "rerank", "speech", "sora", "veo"]
        let chat = ids.filter { id in !excluded.contains { id.lowercased().contains($0) } }
        return Array(Set(chat)).sorted { $0.localizedStandardCompare($1) == .orderedDescending }
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
        // Not every service supports a JSON schema: fall back to plain JSON, then to no format.
        let formats: [JSONObject?] = [
            ["type": "json_schema",
             "json_schema": ["name": "wine_label", "strict": true, "schema": LabelAnalysis.schema] as JSONObject],
            ["type": "json_object"],
            nil,
        ]
        var lastError: Error = AIServiceError.invalidResponse
        for format in formats {
            do {
                return try await openAICompatible(jpeg: jpeg, settings: settings, apiKey: apiKey, format: format)
            } catch AIServiceError.server(let status, let message) where status == 400 || status == 422 {
                lastError = AIServiceError.server(status: status, message: message)
            }
        }
        throw lastError
    }

    private static func openAICompatible(jpeg: Data, settings: AISettings, apiKey: String?, format: JSONObject?) async throws -> LabelAnalysis {
        guard let url = URL(string: settings.effectiveBaseURL + "/chat/completions"), url.scheme == "https" || url.scheme == "http" else {
            throw AIServiceError.notConfigured
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 120
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let apiKey, !apiKey.isEmpty {
            request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        }
        var body: JSONObject = [
            "model": settings.effectiveModel,
            "messages": [[
                "role": "user",
                "content": [
                    ["type": "text", "text": LabelAnalysis.prompt],
                    ["type": "image_url",
                     "image_url": ["url": "data:image/jpeg;base64,\(jpeg.base64EncodedString())"]] as JSONObject,
                ] as [JSONObject],
            ] as JSONObject],
        ]
        if let format { body["response_format"] = format }
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
