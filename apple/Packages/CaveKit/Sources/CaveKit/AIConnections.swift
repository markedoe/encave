import Crypto
import Foundation

/// AI services the user can connect with their own key, plus a server of their own.
/// Most speak the OpenAI chat format; Claude has its own API, Ollama too.
public enum AIProvider: String, CaseIterable, Codable, Sendable {
    case openAI, anthropic, google, xai, mistral
    case deepseek, qwen, kimi, zhipu, minimax
    case openRouter
    /// Servers on your own computer or network.
    case ollama, vllm, lmStudio
    /// Any other OpenAI-compatible server (OpenWebUI, llama.cpp…).
    case local

    /// Brand names are not translated; "" for `local`, named by the app in each language.
    public var name: String {
        switch self {
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
    public var isLocal: Bool { [.ollama, .vllm, .lmStudio, .local].contains(self) }

    /// Ollama has its own API (structured outputs, list of models that read images).
    public var usesOllamaAPI: Bool { self == .ollama }

    public var usesAnthropicAPI: Bool { self == .anthropic }

    public var defaultBaseURL: String {
        switch self {
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
    public var requiresKey: Bool { !isLocal }

    /// Model offered before the list is loaded, when one is known to read images.
    public var suggestedModel: String { self == .anthropic ? "claude-opus-5-5" : "" }

    /// Where to create a key (for a local server: where to get one).
    public var keyPage: URL? {
        switch self {
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

    public static var global: [AIProvider] { [.openAI, .anthropic, .google, .xai, .mistral] }
    public static var chinese: [AIProvider] { [.deepseek, .qwen, .kimi, .zhipu, .minimax] }
    public static var localServers: [AIProvider] { [.ollama, .vllm, .lmStudio, .local] }
}

/// A way to reach an AI service: its provider, an optional name ("OpenRouter perso") and
/// server address. Several per provider are allowed. Its key is not here: it stays in the
/// Keychain of this device (`keyAccount`), never synced, exported or backed up.
public struct AIConnection: Codable, Equatable, Identifiable, Sendable {
    public var id: UUID
    public var provider: AIProvider
    public var name: String
    /// Empty: the provider's usual address.
    public var baseURL: String
    /// OpenRouter only. Off (the default): every request asks OpenRouter to skip the
    /// providers that keep or train on the data it is sent.
    public var allowsDataCollection: Bool
    /// Off by default: "Allow web search", for a provider whose API searches the web itself
    /// (`webSearch`). Used only to complete a saved wine from the web, never for a label.
    public var allowsWebSearch: Bool

    public init(id: UUID = UUID(), provider: AIProvider, name: String = "", baseURL: String = "",
                allowsDataCollection: Bool = false, allowsWebSearch: Bool = false) {
        self.id = id
        self.provider = provider
        self.name = name
        self.baseURL = baseURL
        self.allowsDataCollection = allowsDataCollection
        self.allowsWebSearch = allowsWebSearch
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        provider = try c.decode(AIProvider.self, forKey: .provider)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        baseURL = try c.decodeIfPresent(String.self, forKey: .baseURL) ?? ""
        allowsDataCollection = try c.decodeIfPresent(Bool.self, forKey: .allowsDataCollection) ?? false
        allowsWebSearch = try c.decodeIfPresent(Bool.self, forKey: .allowsWebSearch) ?? false
    }

    /// How this connection's API searches the web, nil when it cannot: by its provider, at
    /// the provider's own address (another server with the same API may not search).
    public var webSearch: AIWebSearch.Kind? {
        guard let kind = provider.webSearch else { return nil }
        let host = URL(string: effectiveBaseURL)?.host?.lowercased() ?? ""
        let usual = URL(string: provider.defaultBaseURL)?.host?.lowercased() ?? ""
        return host == usual ? kind : nil
    }

    /// Web search is allowed and possible.
    public var searchesWeb: Bool { allowsWebSearch && webSearch != nil }

    public var effectiveBaseURL: String {
        let trimmed = baseURL.trimmingCharacters(in: .whitespaces)
        let base = trimmed.isEmpty ? provider.defaultBaseURL : trimmed
        return base.hasSuffix("/") ? String(base.dropLast()) : base
    }

    /// Where its key is in the Keychain.
    public var keyAccount: String { "ai.connection.\(id.uuidString)" }

    /// Every request through OpenRouter refuses providers that keep or train on data,
    /// unless this connection allows it.
    /// A connection to another kind pointed at OpenRouter's address is treated the same.
    public var deniesDataCollection: Bool {
        let host = URL(string: effectiveBaseURL)?.host?.lowercased() ?? ""
        let isOpenRouter = provider == .openRouter || host == "openrouter.ai" || host.hasSuffix(".openrouter.ai")
        return isOpenRouter && !allowsDataCollection
    }
}

/// A model of a connection, in the user's order of preference.
public struct AIModelChoice: Codable, Equatable, Identifiable, Sendable {
    public var id: UUID
    public var connectionID: UUID
    public var model: String

    public init(id: UUID = UUID(), connectionID: UUID, model: String) {
        self.id = id
        self.connectionID = connectionID
        self.model = model
    }
}

/// The connections and the ordered list of models that read label photos. AI label
/// recognition is on when the list has a model. Keys are never part of it.
public struct AIConfiguration: Codable, Equatable, Sendable {
    public var connections: [AIConnection] = []
    public var models: [AIModelChoice] = []

    public init(connections: [AIConnection] = [], models: [AIModelChoice] = []) {
        self.connections = connections
        self.models = models
    }

    /// A connection or a model this version cannot read (a newer provider) is left out.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        connections = (try c.decodeIfPresent([Lossy<AIConnection>].self, forKey: .connections) ?? []).compactMap(\.value)
        let ids = Set(connections.map(\.id))
        models = (try c.decodeIfPresent([Lossy<AIModelChoice>].self, forKey: .models) ?? []).compactMap(\.value)
            .filter { ids.contains($0.connectionID) }
    }

    public var isEnabled: Bool { !usableModels.isEmpty }

    /// The models in order, each with its connection.
    public var usableModels: [AIModelChoice] {
        models.filter { connection(for: $0) != nil && !$0.model.trimmingCharacters(in: .whitespaces).isEmpty }
    }

    public func connection(for model: AIModelChoice) -> AIConnection? {
        connections.first { $0.id == model.connectionID }
    }

    public func connection(id: UUID) -> AIConnection? { connections.first { $0.id == id } }

    public func models(of connection: UUID) -> [AIModelChoice] { models.filter { $0.connectionID == connection } }

    /// Removes a connection and its models. Its key is removed by the caller.
    public mutating func removeConnection(_ id: UUID) {
        connections.removeAll { $0.id == id }
        models.removeAll { $0.connectionID == id }
    }

    public mutating func upsert(_ connection: AIConnection) {
        if let index = connections.firstIndex(where: { $0.id == connection.id }) {
            connections[index] = connection
        } else {
            connections.append(connection)
        }
    }

    /// Adds a model at the end of the list, unless the same one is in it already.
    @discardableResult
    public mutating func addModel(_ model: String, connection: UUID) -> AIModelChoice? {
        let name = model.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, connections.contains(where: { $0.id == connection }) else { return nil }
        if let existing = models.first(where: { $0.connectionID == connection && $0.model == name }) { return existing }
        let choice = AIModelChoice(connectionID: connection, model: name)
        models.append(choice)
        return choice
    }

    /// The list order of a native List's `onMove`.
    public mutating func moveModels(fromOffsets source: IndexSet, toOffset destination: Int) {
        let moving = source.sorted().map { models[$0] }
        var rest = models.enumerated().filter { !source.contains($0.offset) }.map(\.element)
        let before = source.filter { $0 < destination }.count
        rest.insert(contentsOf: moving, at: min(max(destination - before, 0), rest.count))
        models = rest
    }

    /// The models to try for a reading: all in order, or the one the user picked.
    public func readingOrder(only modelID: UUID? = nil) -> [AIModelChoice] {
        let usable = usableModels
        guard let modelID else { return usable }
        return usable.filter { $0.id == modelID }
    }
}

private struct Lossy<Value: Decodable>: Decodable {
    let value: Value?
    init(from decoder: Decoder) throws { value = try? Value(from: decoder) }
}

// MARK: Keys

/// What a Keychain read found. A failure (device locked) is not "missing": a key that
/// could not be read is never replaced or lost.
public enum AIKeyRead: Equatable, Sendable {
    case found(String)
    case missing
    case failed
}

/// The Keychain of this device in the app; a dictionary in tests.
public protocol AIKeyStore {
    func readKey(_ account: String) -> AIKeyRead
    /// Nil or empty deletes. Returns whether it was stored (or deleted).
    @discardableResult func writeKey(_ value: String?, account: String) -> Bool
}

extension AIKeyStore {
    public func key(for connection: AIConnection) -> String? {
        if case .found(let key) = readKey(connection.keyAccount) { return AIKeys.clean(key) }
        return nil
    }

    @discardableResult
    public func setKey(_ key: String?, for connection: AIConnection) -> Bool {
        writeKey(key.map(AIKeys.clean), account: connection.keyAccount)
    }
}

public enum AIKeys {
    /// Pasted keys often carry spaces, line breaks or quotes, which providers reject as invalid.
    public static func clean(_ key: String) -> String {
        key.trimmingCharacters(in: .whitespacesAndNewlines.union(CharacterSet(charactersIn: "\"'“”‘’")))
    }
}

// MARK: Saving, and the settings of earlier versions

public enum AIConfigurationStore {
    public static let defaultsKey = "ai.configuration"
    /// Set once the settings of earlier versions are moved (`migrate`).
    public static let migratedKey = "ai.configuration.migrated"

    /// The saved configuration; on first launch after the update, the earlier single
    /// provider, model, address and keys become connections and the first model. If that
    /// could not be done yet (a key could not be read), it is tried again at the next
    /// load, keeping what the user set up meanwhile.
    public static func load(defaults: UserDefaults = .standard, keys: AIKeyStore) -> AIConfiguration {
        let saved = defaults.data(forKey: defaultsKey).flatMap { try? JSONDecoder().decode(AIConfiguration.self, from: $0) }
        if defaults.bool(forKey: migratedKey) { return saved ?? AIConfiguration() }
        return migrate(defaults: defaults, keys: keys, into: saved ?? AIConfiguration()) ?? saved ?? AIConfiguration()
    }

    public static func save(_ configuration: AIConfiguration, defaults: UserDefaults = .standard) {
        if let data = try? JSONEncoder().encode(configuration) { defaults.set(data, forKey: defaultsKey) }
    }

    /// The provider, model and address of versions with one provider ("ai.provider",
    /// "ai.model.<provider>", "ai.baseURL.<provider>"), including the names of the first test builds.
    public static func legacySettings(_ defaults: UserDefaults) -> (provider: AIProvider?, model: String, baseURL: String) {
        let raw = defaults.string(forKey: "ai.provider") ?? "off"
        let legacyURL = defaults.string(forKey: "ai.baseURL") ?? ""
        let localURL = defaults.string(forKey: "ai.baseURL.local") ?? legacyURL
        let provider: AIProvider?
        switch raw {
        case "claude": provider = .anthropic
        case "openAICompatible":
            provider = legacyURL.isEmpty || legacyURL.contains("api.openai.com") ? .openAI : .local
        // The first "local" option pointed at Ollama by default.
        case "local" where localURL.isEmpty || localURL.contains(":11434"): provider = .ollama
        default: provider = AIProvider(rawValue: raw)
        }
        guard let provider else { return (nil, "", "") }
        let model = defaults.string(forKey: "ai.model.\(provider.rawValue)") ?? defaults.string(forKey: "ai.model") ?? ""
        var baseURL = defaults.string(forKey: "ai.baseURL.\(provider.rawValue)") ?? (provider == .local ? legacyURL : "")
        if raw == "local", provider == .ollama, baseURL.isEmpty {
            // The address of a server set up with the first builds, without the "/v1".
            var old = localURL
            if old.hasSuffix("/v1") { old = String(old.dropLast(3)) }
            baseURL = old == AIProvider.ollama.defaultBaseURL ? "" : old
        }
        return (provider, model, baseURL)
    }

    /// Account of a provider's key in earlier versions; the very first one had "ai.apiKey" (Claude).
    static func legacyKeyAccounts(_ provider: AIProvider) -> [String] {
        provider == .anthropic ? ["ai.apiKey.anthropic", "ai.apiKey"] : ["ai.apiKey.\(provider.rawValue)"]
    }

    /// Moves the settings of earlier versions: the provider in use becomes the first
    /// connection, with its model first in the list; every other provider that had a key
    /// gets a connection too, so no key is lost. Keys are copied to their connection, the
    /// configuration saved, and only then the old keys removed. Nil when a key could not
    /// be read or written (device locked): nothing changes, it is tried again next time.
    public static func migrate(defaults: UserDefaults, keys: AIKeyStore,
                               into existing: AIConfiguration = AIConfiguration()) -> AIConfiguration? {
        let legacy = legacySettings(defaults)
        var found: [AIProvider: String] = [:]
        var oldAccounts: [String] = []
        for provider in AIProvider.allCases {
            for account in legacyKeyAccounts(provider) {
                switch keys.readKey(account) {
                case .found(let key):
                    oldAccounts.append(account)
                    if found[provider] == nil, !AIKeys.clean(key).isEmpty { found[provider] = AIKeys.clean(key) }
                case .missing: break
                case .failed: return nil
                }
            }
        }
        var configuration = AIConfiguration()
        if let provider = legacy.provider {
            let connection = AIConnection(id: migratedID(provider), provider: provider, baseURL: legacy.baseURL)
            configuration.connections.append(connection)
            let model = legacy.model.trimmingCharacters(in: .whitespaces)
            configuration.addModel(model.isEmpty ? provider.suggestedModel : model, connection: connection.id)
        }
        for provider in AIProvider.allCases where provider != legacy.provider && found[provider] != nil {
            let baseURL = defaults.string(forKey: "ai.baseURL.\(provider.rawValue)") ?? ""
            configuration.connections.append(AIConnection(id: migratedID(provider), provider: provider, baseURL: baseURL))
        }
        var written: [AIConnection] = []
        for connection in configuration.connections {
            guard let key = found[connection.provider] else { continue }
            guard keys.setKey(key, for: connection) else {
                written.forEach { keys.setKey(nil, for: $0) }
                return nil
            }
            written.append(connection)
        }
        // What was set up before the move could be made stays, after the earlier settings.
        let earlier = Set(configuration.connections.map(\.id))
        configuration.connections += existing.connections.filter { !earlier.contains($0.id) }
        configuration.models += existing.models.filter { model in
            !configuration.models.contains { $0.connectionID == model.connectionID && $0.model == model.model }
        }
        save(configuration, defaults: defaults)
        defaults.set(true, forKey: migratedKey)
        for account in oldAccounts { keys.writeKey(nil, account: account) }
        return configuration
    }

    /// The same identifier for a provider's moved connection each time the move is tried, so
    /// a move interrupted (the app quit) and done again writes its key at the same place.
    static func migratedID(_ provider: AIProvider) -> UUID {
        let digest = Array(SHA256.hash(data: Data("encave.ai.migrated.\(provider.rawValue)".utf8)))
        var bytes = Array(digest.prefix(16))
        bytes[6] = (bytes[6] & 0x0F) | 0x50
        bytes[8] = (bytes[8] & 0x3F) | 0x80
        return UUID(uuid: (bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
                           bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]))
    }
}

// MARK: Reading with one model after another

/// One model that could not read the label, and why.
public struct AIModelFailure: Sendable {
    public let model: AIModelChoice
    public let error: any Error
}

/// Every model was tried and none could read the label: what failed, per model.
public struct AIAllModelsFailed: Error, Sendable {
    public let failures: [AIModelFailure]
    /// Models left untried because the time for the reading ran out.
    public let untried: [AIModelChoice]
}

/// The time for a reading ran out before this model could be tried.
public struct AIOutOfTime: Error, Sendable {}

public enum AIFallback {
    /// A whole reading, every model included: long enough for a local model that loads.
    public static let totalBudget: TimeInterval = 360
    /// A model is not started with less time than this left.
    public static let minimumAttempt: TimeInterval = 15

    /// Tries the models in order, one at a time and each at most once, until one answers.
    /// Any failure moves on to the next (network, timeout, a server that cannot be reached,
    /// 401/402/403/429/5xx, a refusal, an answer that cannot be read); cancelling stops.
    /// `attempt` gets the time left for its request.
    public static func read<Answer>(
        _ models: [AIModelChoice],
        budget: TimeInterval = totalBudget,
        now: () -> Date = Date.init,
        attempt: (AIModelChoice, TimeInterval) async throws -> Answer
    ) async throws -> (model: AIModelChoice, answer: Answer) {
        let deadline = now().addingTimeInterval(budget)
        var failures: [AIModelFailure] = []
        for (index, model) in models.enumerated() {
            try Task.checkCancellation()
            let left = deadline.timeIntervalSince(now())
            if left < minimumAttempt, !failures.isEmpty {
                throw AIAllModelsFailed(failures: failures, untried: Array(models[index...]))
            }
            do {
                return (model, try await attempt(model, max(left, minimumAttempt)))
            } catch {
                if error is CancellationError || Task.isCancelled { throw CancellationError() }
                failures.append(AIModelFailure(model: model, error: error))
            }
        }
        throw AIAllModelsFailed(failures: failures, untried: [])
    }
}

// MARK: Requests

public enum AIRequestBody {
    /// OpenRouter's provider preferences: `data_collection: "deny"` keeps the request
    /// away from providers that keep or train on it (openrouter.ai/docs, provider routing).
    public static func openRouterProvider(for connection: AIConnection) -> [String: Any]? {
        connection.deniesDataCollection ? ["data_collection": "deny"] : nil
    }

    /// A Chat Completions request with the prompt and the photo (none for a question about
    /// the wine's identity); `format` is the response_format, nil to send none. With
    /// `webSearch`, through OpenRouter only, its web plugin (openrouter.ai/docs, web search).
    public static func chatCompletions(connection: AIConnection, model: String, prompt: String,
                                       jpeg: Data?, format: [String: Any]?, webSearch: Bool = false) -> [String: Any] {
        var content: [[String: Any]] = [["type": "text", "text": prompt]]
        if let jpeg {
            content.append(["type": "image_url", "image_url": ["url": "data:image/jpeg;base64,\(jpeg.base64EncodedString())"]])
        }
        var body: [String: Any] = [
            "model": model,
            "messages": [["role": "user", "content": content] as [String: Any]],
        ]
        if let format { body["response_format"] = format }
        if let provider = openRouterProvider(for: connection) { body["provider"] = provider }
        if webSearch, connection.webSearch == .openRouterPlugin { body["plugins"] = [["id": "web", "max_results": 5]] }
        return body
    }

    /// The Anthropic Messages request: the photo when there is one, then the prompt. With
    /// `webSearch`, the web search tool (at most 5 searches) and no output format, which the
    /// search's citations do not go with: the prompt asks for the JSON object instead.
    public static func claude(model: String, prompt: String, schema: [String: Any], jpeg: Data?, webSearch: Bool,
                              effort: Bool, fallbacks: Bool) -> [String: Any] {
        var content: [[String: Any]] = []
        if let jpeg {
            content.append(["type": "image",
                            "source": ["type": "base64", "media_type": "image/jpeg", "data": jpeg.base64EncodedString()]])
        }
        content.append(["type": "text", "text": webSearch ? AIWebSearch.prompt(prompt, schema: schema) : prompt])
        var body: [String: Any] = [
            "model": model,
            "max_tokens": 8000,
            "messages": [["role": "user", "content": content] as [String: Any]],
        ]
        var output: [String: Any] = [:]
        if !webSearch { output["format"] = ["type": "json_schema", "schema": schema] as [String: Any] }
        // Reading a label is a simple task.
        if effort { output["effort"] = "low" }
        if !output.isEmpty { body["output_config"] = output }
        if fallbacks { body["fallbacks"] = "default" }
        if webSearch { body["tools"] = [["type": "web_search_20250305", "name": "web_search", "max_uses": 5]] }
        return body
    }

    /// The Responses request of OpenAI and xAI with their web search tool: text only (never
    /// a photo), the JSON object asked in the prompt.
    public static func responses(model: String, prompt: String, schema: [String: Any]) -> [String: Any] {
        [
            "model": model,
            "input": [["role": "user", "content": [["type": "input_text", "text": AIWebSearch.prompt(prompt, schema: schema)]]]],
            "tools": [["type": "web_search"]],
            // The Responses API keeps requests and answers by default: not these.
            "store": false,
        ]
    }

    /// Of OpenRouter's model list, the models that read images
    /// (`architecture.input_modalities` holds "image").
    public static func openRouterVisionModels(_ items: [[String: Any]]) -> [String] {
        items.compactMap { item in
            guard let id = item["id"] as? String,
                  let modalities = (item["architecture"] as? [String: Any])?["input_modalities"] as? [String],
                  modalities.contains("image") else { return nil }
            return id
        }
    }

    /// Whether a listed model reads images: yes, no, or not known.
    public enum Vision: String, Sendable { case yes, no, unknown }

    /// A model list entry: its own metadata first (OpenRouter and xAI `input_modalities`,
    /// Mistral `capabilities.vision`), then its name (`fixtures/model-vision-vectors.json`).
    public static func vision(of item: [String: Any], provider: AIProvider) -> Vision {
        if let modalities = (item["architecture"] as? [String: Any])?["input_modalities"] as? [String]
            ?? item["input_modalities"] as? [String] {
            return modalities.contains("image") ? .yes : .no
        }
        if let vision = (item["capabilities"] as? [String: Any])?["vision"] as? Bool { return vision ? .yes : .no }
        guard let id = (item["id"] as? String) ?? (item["name"] as? String) else { return .unknown }
        return vision(ofModel: id, provider: provider)
    }

    /// By the model's name, from each provider's documentation of the models that take images.
    public static func vision(ofModel id: String, provider: AIProvider) -> Vision {
        let full = id.lowercased().replacingOccurrences(of: "models/", with: "")
        let name = full.split(separator: "/").last.map(String.init) ?? full
        func has(_ parts: String...) -> Bool { parts.contains { name.contains($0) } }
        func starts(_ prefixes: String...) -> Bool { prefixes.contains { name.hasPrefix($0) } }
        switch provider {
        case .anthropic:
            guard starts("claude") else { return .unknown }
            return starts("claude-1", "claude-2", "claude-instant") ? .no : .yes
        case .openAI:
            if starts("gpt-3.5", "davinci", "babbage", "text-", "o1-mini", "o3-mini", "gpt-oss", "gpt-4-0")
                || has("instruct") || name == "gpt-4" { return .no }
            if starts("gpt-4o", "gpt-4.1", "gpt-4.5", "gpt-5", "chatgpt-4o", "gpt-4-turbo", "gpt-4-vision", "o1", "o3", "o4") {
                return .yes
            }
            return .unknown
        case .google:
            if starts("gemini") { return .yes }
            if starts("gemma-3-1b") { return .no }
            return starts("gemma-3") ? .yes : .unknown
        case .xai:
            if has("image") { return .no }
            if has("vision") || starts("grok-4") { return .yes }
            return starts("grok-code", "grok-3", "grok-2", "grok-beta") ? .no : .unknown
        case .mistral:
            return starts("pixtral") ? .yes : .unknown
        case .deepseek:
            return has("vl") ? .yes : .no
        case .qwen:
            return has("vl", "omni") || starts("qvq") ? .yes : .no
        case .kimi:
            if has("vision", "-vl") || name == "kimi-latest" { return .yes }
            return starts("kimi-k2", "moonshot-v1") ? .no : .unknown
        case .zhipu:
            return name.range(of: #"^glm-[0-9.]+v"#, options: .regularExpression) != nil ? .yes : .no
        case .minimax:
            return has("vl") ? .yes : .unknown
        default:
            return .unknown
        }
    }

    /// The models to offer for reading labels: those known to read images; when none is
    /// known, those not known either way (a new or unlisted model); never one known not to.
    public static func imageModels(_ items: [[String: Any]], provider: AIProvider) -> [String] {
        var known: [String] = [], unsure: [String] = []
        for item in items {
            guard let raw = (item["id"] as? String) ?? (item["name"] as? String) else { continue }
            let id = raw.replacingOccurrences(of: "models/", with: "")
            switch vision(of: item, provider: provider) {
            case .yes: known.append(id)
            case .unknown: unsure.append(id)
            case .no: break
            }
        }
        return known.isEmpty ? unsure : known
    }
}
