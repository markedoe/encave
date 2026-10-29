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

    public init(id: UUID = UUID(), provider: AIProvider, name: String = "", baseURL: String = "",
                allowsDataCollection: Bool = false) {
        self.id = id
        self.provider = provider
        self.name = name
        self.baseURL = baseURL
        self.allowsDataCollection = allowsDataCollection
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        provider = try c.decode(AIProvider.self, forKey: .provider)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        baseURL = try c.decodeIfPresent(String.self, forKey: .baseURL) ?? ""
        allowsDataCollection = try c.decodeIfPresent(Bool.self, forKey: .allowsDataCollection) ?? false
    }

    public var effectiveBaseURL: String {
        let trimmed = baseURL.trimmingCharacters(in: .whitespaces)
        let base = trimmed.isEmpty ? provider.defaultBaseURL : trimmed
        return base.hasSuffix("/") ? String(base.dropLast()) : base
    }

    /// Where its key is in the Keychain.
    public var keyAccount: String { "ai.connection.\(id.uuidString)" }

    /// Every request through OpenRouter refuses providers that keep or train on data,
    /// unless this connection allows it.
    public var deniesDataCollection: Bool { provider == .openRouter && !allowsDataCollection }
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

    /// The saved configuration; on first launch after the update, the earlier single
    /// provider, model, address and keys become connections and the first model.
    public static func load(defaults: UserDefaults = .standard, keys: AIKeyStore) -> AIConfiguration {
        if let data = defaults.data(forKey: defaultsKey) {
            return (try? JSONDecoder().decode(AIConfiguration.self, from: data)) ?? AIConfiguration()
        }
        return migrate(defaults: defaults, keys: keys) ?? AIConfiguration()
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
    public static func migrate(defaults: UserDefaults, keys: AIKeyStore) -> AIConfiguration? {
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
            let connection = AIConnection(provider: provider, baseURL: legacy.baseURL)
            configuration.connections.append(connection)
            let model = legacy.model.trimmingCharacters(in: .whitespaces)
            configuration.addModel(model.isEmpty ? provider.suggestedModel : model, connection: connection.id)
        }
        for provider in AIProvider.allCases where provider != legacy.provider && found[provider] != nil {
            let baseURL = defaults.string(forKey: "ai.baseURL.\(provider.rawValue)") ?? ""
            configuration.connections.append(AIConnection(provider: provider, baseURL: baseURL))
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
        save(configuration, defaults: defaults)
        for account in oldAccounts { keys.writeKey(nil, account: account) }
        return configuration
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

    /// A Chat Completions request with the prompt and the photo; `format` is the
    /// response_format, nil to send none.
    public static func chatCompletions(connection: AIConnection, model: String, prompt: String,
                                       jpeg: Data, format: [String: Any]?) -> [String: Any] {
        var body: [String: Any] = [
            "model": model,
            "messages": [[
                "role": "user",
                "content": [
                    ["type": "text", "text": prompt] as [String: Any],
                    ["type": "image_url",
                     "image_url": ["url": "data:image/jpeg;base64,\(jpeg.base64EncodedString())"]] as [String: Any],
                ],
            ] as [String: Any]],
        ]
        if let format { body["response_format"] = format }
        if let provider = openRouterProvider(for: connection) { body["provider"] = provider }
        return body
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
}
