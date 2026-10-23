import CaveKit
import Foundation

/// AI services the user can connect with their own key, plus a server of their own.
/// Most speak the OpenAI chat format; Claude has its own API.
enum AIProvider: String, CaseIterable, Sendable {
    case off
    case openAI, anthropic, google, xai, mistral
    case deepseek, qwen, kimi, zhipu, minimax
    case openRouter
    /// Servers on your own computer or network.
    case ollama, vllm, lmStudio
    /// Any other OpenAI-compatible server (OpenWebUI, llama.cpp…).
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
        case .ollama: "Ollama"
        case .vllm: "vLLM"
        case .lmStudio: "LM Studio"
        case .local: ""
        }
    }

    /// Runs on the user's computer or network: no key needed, slower answers allowed.
    var isLocal: Bool { [.ollama, .vllm, .lmStudio, .local].contains(self) }

    /// Ollama has its own API (structured outputs, list of models that read images).
    var usesOllamaAPI: Bool { self == .ollama }

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
        case .ollama: "http://localhost:11434"
        case .vllm: "http://localhost:8000/v1"
        case .lmStudio: "http://localhost:1234/v1"
        case .local: "http://localhost:8080/v1"
        }
    }

    /// A key is optional only for a server of your own.
    var requiresKey: Bool { !isLocal && self != .off }

    /// Model used until the list is loaded, when one is known to read images.
    var suggestedModel: String {
        switch self {
        case .anthropic: "claude-opus-5-5"
        default: ""
        }
    }

    /// Where to create a key (for a local server: where to get one).
    var keyPage: URL? {
        switch self {
        case .off: nil
        case .openAI: URL(string: "https://platform.openai.com/api-keys")
        case .anthropic: URL(string: "https://console.anthropic.com/settings/keys")
        case .google: URL(string: "https://aistudio.google.com/apikey")
        case .xai: URL(string: "https://console.x.ai")
        case .mistral: URL(string: "https://console.mistral.ai/api-keys")
        case .deepseek: URL(string: "https://platform.deepseek.com/api_keys")
        // Alibaba Cloud's guide: the key is created in the Model Studio console, region Singapore.
        case .qwen: URL(string: "https://help.aliyun.com/en/model-studio/get-api-key")
        case .kimi: URL(string: "https://platform.moonshot.ai/console/api-keys")
        case .zhipu: URL(string: "https://z.ai/manage-apikey/apikey-list")
        case .minimax: URL(string: "https://platform.minimax.io/user-center/basic-information/interface-key")
        case .openRouter: URL(string: "https://openrouter.ai/keys")
        case .ollama: URL(string: "https://ollama.com/download")
        case .vllm: URL(string: "https://docs.vllm.ai/en/latest/getting_started/quickstart.html")
        case .lmStudio: URL(string: "https://lmstudio.ai/download")
        case .local: nil
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
    /// A local server's address, or a different address for a provider (empty: the default).
    var baseURL = ""

    var isEnabled: Bool { provider != .off }

    var effectiveModel: String {
        let trimmed = model.trimmingCharacters(in: .whitespaces)
        return trimmed.isEmpty ? provider.suggestedModel : trimmed
    }

    var effectiveBaseURL: String {
        let trimmed = baseURL.trimmingCharacters(in: .whitespaces)
        let base = trimmed.isEmpty ? provider.defaultBaseURL : trimmed
        return base.hasSuffix("/") ? String(base.dropLast()) : base
    }

    static func load(from defaults: UserDefaults = .standard) -> AISettings {
        var settings = AISettings()
        let raw = defaults.string(forKey: "ai.provider") ?? "off"
        let legacyURL = defaults.string(forKey: "ai.baseURL") ?? ""
        switch raw {
        // Names used by the first test builds.
        case "claude": settings.provider = .anthropic
        case "openAICompatible":
            settings.provider = legacyURL.isEmpty || legacyURL.contains("api.openai.com") ? .openAI : .local
        // The first "local" option pointed at Ollama by default.
        case "local" where (defaults.string(forKey: "ai.baseURL.local") ?? legacyURL).isEmpty
            || (defaults.string(forKey: "ai.baseURL.local") ?? legacyURL).contains(":11434"):
            settings.provider = .ollama
        default: settings.provider = AIProvider(rawValue: raw) ?? .off
        }
        settings.model = defaults.string(forKey: "ai.model.\(settings.provider.rawValue)")
            ?? defaults.string(forKey: "ai.model") ?? ""
        settings.baseURL = defaults.string(forKey: "ai.baseURL.\(settings.provider.rawValue)")
            ?? (settings.provider == .local ? legacyURL : "")
        if raw == "local", settings.provider == .ollama, settings.baseURL.isEmpty {
            // Keep the address of a server set up with the first builds, without the "/v1".
            var old = defaults.string(forKey: "ai.baseURL.local") ?? legacyURL
            if old.hasSuffix("/v1") { old = String(old.dropLast(3)) }
            settings.baseURL = old == AIProvider.ollama.defaultBaseURL ? "" : old
        }
        return settings
    }

    /// Saves the provider only. Model and address are saved per provider when they change,
    /// so switching providers never copies one provider's model to another.
    func saveProvider(to defaults: UserDefaults = .standard) {
        defaults.set(provider.rawValue, forKey: "ai.provider")
    }

    static func saveModel(_ model: String, for provider: AIProvider, defaults: UserDefaults = .standard) {
        defaults.set(model, forKey: "ai.model.\(provider.rawValue)")
    }

    static func saveBaseURL(_ url: String, for provider: AIProvider, defaults: UserDefaults = .standard) {
        defaults.set(url, forKey: "ai.baseURL.\(provider.rawValue)")
    }

    /// Model chosen earlier for a provider, so switching back keeps it.
    static func savedModel(for provider: AIProvider, defaults: UserDefaults = .standard) -> String {
        defaults.string(forKey: "ai.model.\(provider.rawValue)") ?? ""
    }

    static func savedBaseURL(for provider: AIProvider, defaults: UserDefaults = .standard) -> String {
        defaults.string(forKey: "ai.baseURL.\(provider.rawValue)") ?? ""
    }

    /// One key per provider, in the Keychain on this device.
    static func apiKey(for provider: AIProvider) -> String? {
        let key = Keychain.get("ai.apiKey.\(provider.rawValue)") ?? (provider == .anthropic ? Keychain.get("ai.apiKey") : nil)
        return key.map(cleanKey)
    }

    static func setAPIKey(_ key: String?, for provider: AIProvider) {
        Keychain.set(key.map(cleanKey), for: "ai.apiKey.\(provider.rawValue)")
    }

    /// Pasted keys often carry spaces, line breaks or quotes, which providers reject as invalid.
    static func cleanKey(_ key: String) -> String {
        key.trimmingCharacters(in: .whitespacesAndNewlines.union(CharacterSet(charactersIn: "\"'“”‘’")))
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
    /// Where the whole front label is in the photo ("label_box" and "label_angle"), checked
    /// when the answer is read; nil when absent or invalid, which never fails the answer.
    var placement: AILabelPlacement? = nil

    private enum CodingKeys: String, CodingKey {
        case name, producer, vintage, color, appellation, region, country, grapes, drinkFrom, drinkUntil
    }

    /// The label's frame for the studio picture, for the photo that was read.
    func labelFrame(forPhoto photoID: String?) -> WineLabelFrame? {
        guard let photoID else { return nil }
        return placement?.frame(forPhoto: photoID)
    }

    var wineColor: WineColor? { WineColor(rawValue: color) }

    /// Fills a draft. A field is replaced only if it is empty or still holds the value filled
    /// automatically before (recorded in `filled`), so what the user typed is kept.
    /// True when a field changed: otherwise the AI found nothing to add.
    @discardableResult
    func apply(to draft: inout WineDraft, replacing filled: inout WineDraft) -> Bool {
        let before = draft
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
        return draft != before
    }
}

typealias JSONObject = [String: Any]

enum AIServiceError: LocalizedError {
    case notConfigured
    case missingKey
    case noModel
    case refused
    case server(status: Int, message: String)
    case invalidResponse
    /// Not an https:// address, or plain http:// to a server outside the local network.
    case insecureURL

    var errorDescription: String? {
        switch self {
        case .notConfigured: String(localized: "Choose an AI provider in Settings.")
        case .missingKey: String(localized: "Add your API key in Settings.")
        case .noModel: String(localized: "Choose a model in Settings.")
        case .refused: String(localized: "The model declined to read this photo.")
        case .server(let status, let message):
            message.isEmpty ? String(localized: "The AI service returned error \(status).") : message
        case .invalidResponse: String(localized: "The answer from the AI service could not be read.")
        case .insecureURL:
            String(localized: "Enter the full server address, starting with https:// (http:// only for a server on your local network).")
        }
    }
}

enum AIService {
    /// The studio look is on (the default): the same request also asks where the label is.
    static var findsLabel: Bool {
        UserDefaults.standard.object(forKey: StudioPhoto.settingKey) as? Bool ?? true
    }

    /// Sends the label photo (a resized JPEG) to the configured provider, in one request.
    static func analyzeLabel(jpeg: Data, settings: AISettings = .load(),
                             request: LabelRequest = LabelRequest(findsLabel: findsLabel)) async throws -> LabelAnalysis {
        let apiKey = settings.apiKey
        guard settings.isEnabled else { throw AIServiceError.notConfigured }
        if settings.provider.requiresKey, (apiKey ?? "").isEmpty { throw AIServiceError.missingKey }
        guard !settings.effectiveModel.isEmpty else { throw AIServiceError.noModel }
        var analysis: LabelAnalysis
        if settings.provider.usesAnthropicAPI {
            analysis = try await claude(jpeg: jpeg, settings: settings, apiKey: apiKey ?? "", request: request)
        } else if settings.provider.usesOllamaAPI {
            analysis = try await ollama(jpeg: jpeg, settings: settings, request: request)
        } else {
            analysis = try await openAICompatible(jpeg: jpeg, settings: settings, apiKey: apiKey, request: request)
        }
        // Not asked for: an answer that gives it anyway is not used.
        if !request.findsLabel { analysis.placement = nil }
        return analysis
    }

    /// Local models can take a while, especially the first time they load.
    private static func timeout(for settings: AISettings) -> TimeInterval {
        settings.provider.isLocal ? 300 : 120
    }

    /// Checks the key by listing the models it can use. Returns their identifiers, sorted.
    static func models(settings: AISettings, apiKey: String?) async throws -> [String] {
        guard settings.isEnabled else { throw AIServiceError.notConfigured }
        if settings.provider.requiresKey, (apiKey ?? "").isEmpty { throw AIServiceError.missingKey }
        if settings.provider.usesOllamaAPI { return try await ollamaModels(settings: settings) }
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

    /// Models documented to accept `output_config.effort` (dated snapshots of them too).
    static let effortModelPrefixes = [
        "claude-fable-5", "claude-mythos-5", "claude-opus-5", "claude-opus-4-8", "claude-opus-4-7", "claude-opus-4-6",
        "claude-opus-4-5", "claude-sonnet-5", "claude-sonnet-4-6", "claude-haiku-5",
    ]
    /// Models documented to accept the server-side `fallbacks: "default"` field.
    static let fallbackModels: Set<String> = [
        "claude-fable-5-1", "claude-mythos-5-1", "claude-fable-5", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5",
    ]

    static func supportsEffort(_ model: String) -> Bool { effortModelPrefixes.contains { model.hasPrefix($0) } }
    static func supportsFallbacks(_ model: String) -> Bool { fallbackModels.contains(model) }

    private static func claude(jpeg: Data, settings: AISettings, apiKey: String,
                               request label: LabelRequest) async throws -> LabelAnalysis {
        let model = settings.effectiveModel
        let options = supportsEffort(model) || supportsFallbacks(model)
        do {
            return try await claude(jpeg: jpeg, settings: settings, apiKey: apiKey, request: label, withOptions: options)
        } catch AIServiceError.server(let status, _) where status == 400 && options {
            // A model or server that does not take these options yet: once more without them.
            return try await claude(jpeg: jpeg, settings: settings, apiKey: apiKey, request: label, withOptions: false)
        }
    }

    private static func claude(jpeg: Data, settings: AISettings, apiKey: String, request label: LabelRequest,
                               withOptions: Bool) async throws -> LabelAnalysis {
        let model = settings.effectiveModel
        guard let url = URL(string: settings.effectiveBaseURL + "/messages") else { throw AIServiceError.notConfigured }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 120
        request.setValue(apiKey, forHTTPHeaderField: "x-api-key")
        request.setValue("2023-06-01", forHTTPHeaderField: "anthropic-version")
        request.setValue("application/json", forHTTPHeaderField: "content-type")
        var output: JSONObject = ["format": ["type": "json_schema", "schema": label.schema] as JSONObject]
        var body: JSONObject = [
            "model": model,
            "max_tokens": 8000,
            "messages": [[
                "role": "user",
                "content": [
                    ["type": "image",
                     "source": ["type": "base64", "media_type": "image/jpeg", "data": jpeg.base64EncodedString()]] as JSONObject,
                    ["type": "text", "text": label.prompt],
                ] as [JSONObject],
            ] as JSONObject],
        ]
        if withOptions, supportsEffort(model) {
            // Reading a label is a simple task.
            output["effort"] = "low"
        }
        if withOptions, supportsFallbacks(model) {
            // Re-runs a declined request on another model instead of returning a refusal.
            request.setValue("server-side-fallback-2026-07-01", forHTTPHeaderField: "anthropic-beta")
            body["fallbacks"] = "default"
        }
        body["output_config"] = output
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

    private static func openAICompatible(jpeg: Data, settings: AISettings, apiKey: String?,
                                         request label: LabelRequest) async throws -> LabelAnalysis {
        // Not every service supports a JSON schema: fall back to plain JSON, then to no format.
        let formats: [JSONObject?] = [
            ["type": "json_schema",
             "json_schema": ["name": "wine_label", "strict": true, "schema": label.schema] as JSONObject],
            ["type": "json_object"],
            nil,
        ]
        var lastError: Error = AIServiceError.invalidResponse
        for format in formats {
            do {
                return try await openAICompatible(jpeg: jpeg, settings: settings, apiKey: apiKey, request: label, format: format)
            } catch AIServiceError.server(let status, let message) where status == 400 || status == 422 {
                lastError = AIServiceError.server(status: status, message: message)
            }
        }
        throw lastError
    }

    private static func openAICompatible(jpeg: Data, settings: AISettings, apiKey: String?, request label: LabelRequest,
                                         format: JSONObject?) async throws -> LabelAnalysis {
        guard let url = URL(string: settings.effectiveBaseURL + "/chat/completions") else {
            throw AIServiceError.notConfigured
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = timeout(for: settings)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let apiKey, !apiKey.isEmpty {
            request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        }
        var body: JSONObject = [
            "model": settings.effectiveModel,
            "messages": [[
                "role": "user",
                "content": [
                    ["type": "text", "text": label.prompt],
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

    // MARK: Ollama (native API)

    /// "http://host:11434", whether the user typed it with "/v1" or "/api" or not.
    private static func ollamaBase(_ settings: AISettings) -> String {
        var base = settings.effectiveBaseURL
        for suffix in ["/v1", "/api"] where base.hasSuffix(suffix) { base = String(base.dropLast(suffix.count)) }
        return base
    }

    /// Structured output with the label's JSON schema, and the photo in "images".
    private static func ollama(jpeg: Data, settings: AISettings, request label: LabelRequest) async throws -> LabelAnalysis {
        guard let url = URL(string: ollamaBase(settings) + "/api/chat") else { throw AIServiceError.notConfigured }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = timeout(for: settings)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let body: JSONObject = [
            "model": settings.effectiveModel,
            "stream": false,
            "format": label.schema,
            // A photo needs more room than Ollama's default context.
            "options": ["temperature": 0, "num_ctx": 8192] as JSONObject,
            "messages": [[
                "role": "user",
                "content": label.prompt,
                "images": [jpeg.base64EncodedString()],
            ] as JSONObject],
        ]
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let json = try await send(request)
        guard let text = (json["message"] as? [String: Any])?["content"] as? String else {
            throw AIServiceError.invalidResponse
        }
        return try decode(text)
    }

    /// Installed models, the ones that read images first (Ollama reports "vision" for them).
    private static func ollamaModels(settings: AISettings) async throws -> [String] {
        let base = ollamaBase(settings)
        guard let url = URL(string: base + "/api/tags") else { throw AIServiceError.notConfigured }
        var request = URLRequest(url: url)
        request.timeoutInterval = 30
        let json = try await send(request)
        let names = ((json["models"] as? [[String: Any]]) ?? []).compactMap { ($0["name"] as? String) ?? ($0["model"] as? String) }
        let capabilities = await withTaskGroup(of: (String, [String]?).self) { group in
            for name in names {
                group.addTask { (name, try? await ollamaCapabilities(of: name, base: base)) }
            }
            var result: [String: [String]] = [:]
            for await (name, list) in group { if let list { result[name] = list } }
            return result
        }
        let sorted = names.sorted { $0.localizedStandardCompare($1) == .orderedAscending }
        let vision = sorted.filter { capabilities[$0]?.contains("vision") == true }
        // Older Ollama versions do not report capabilities: then every model is offered.
        return vision.isEmpty ? sorted : vision
    }

    private static func ollamaCapabilities(of model: String, base: String) async throws -> [String] {
        guard let url = URL(string: base + "/api/show") else { return [] }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 30
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["model": model])
        let json = try await send(request)
        return json["capabilities"] as? [String] ?? []
    }

    // MARK: Helpers

    /// HTTPS, or plain HTTP only for a server on this device or the local network, as for
    /// web search and WebDAV: elsewhere anyone on the way would read the key and the photo.
    static func isAllowed(_ url: URL?) -> Bool {
        guard let url, let host = url.host, !host.isEmpty else { return false }
        switch url.scheme?.lowercased() {
        case "https": return true
        case "http": return LocalAddress.isLocal(host: host)
        default: return false
        }
    }

    /// Every request goes through here.
    private static func send(_ request: URLRequest) async throws -> [String: Any] {
        guard isAllowed(request.url) else { throw AIServiceError.insecureURL }
        let (data, response) = try await URLSession.shared.data(for: request, delegate: SameHostRedirects.shared)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        guard (200..<300).contains(status) else {
            // {"error": {"message": …}} (OpenAI, Anthropic) or {"error": "…"} (Ollama), or {"detail": …} (vLLM).
            let message = ((json?["error"] as? [String: Any])?["message"] as? String)
                ?? (json?["error"] as? String) ?? (json?["detail"] as? String) ?? (json?["message"] as? String) ?? ""
            throw AIServiceError.server(status: status, message: message)
        }
        guard let json else { throw AIServiceError.invalidResponse }
        return json
    }

    /// Parses the JSONObject answer, tolerating a Markdown code fence from servers without strict schemas.
    static func decode(_ text: String) throws -> LabelAnalysis {
        var text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        // Reasoning models served locally may think aloud before answering.
        if let close = text.range(of: "</think>") { text = String(text[close.upperBound...]) }
        if let start = text.firstIndex(of: "{"), let end = text.lastIndex(of: "}") {
            text = String(text[start...end])
        }
        guard var analysis = try? JSONDecoder().decode(LabelAnalysis.self, from: Data(text.utf8)) else {
            throw AIServiceError.invalidResponse
        }
        analysis.placement = AILabelPlacement.parse(Data(text.utf8))
        return analysis
    }
}
