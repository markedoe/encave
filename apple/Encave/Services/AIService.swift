import CaveKit
import Foundation

/// The Keychain of this device, for the connections' keys (never synced, exported or backed up).
struct AIKeychain: AIKeyStore {
    func readKey(_ account: String) -> AIKeyRead {
        switch Keychain.read(account) {
        case .found(let data): String(data: data, encoding: .utf8).map(AIKeyRead.found) ?? .failed
        case .missing: .missing
        case .failed: .failed
        }
    }

    @discardableResult
    func writeKey(_ value: String?, account: String) -> Bool {
        Keychain.setData(value.flatMap { $0.isEmpty ? nil : Data($0.utf8) }, for: account)
    }
}

/// Optional AI label recognition, off by default: the connections and the ordered models
/// the user sets up (`AIConfiguration`). Only the label photo is sent, never the cellar.
enum AISettings {
    static func load() -> AIConfiguration { AIConfigurationStore.load(keys: AIKeychain()) }

    static func save(_ configuration: AIConfiguration) { AIConfigurationStore.save(configuration) }

    /// Pasted keys often carry spaces, line breaks or quotes, which providers reject as invalid.
    static func cleanKey(_ key: String) -> String { AIKeys.clean(key) }
}

extension AIProvider {
    /// The name shown: the brand, or "Other server" in the user's language.
    var title: String { self == .local ? String(localized: "Other server (OpenAI-compatible)") : name }
}

extension AIConnection {
    /// Its own name, or the provider's.
    var title: String {
        let own = name.trimmingCharacters(in: .whitespaces)
        return own.isEmpty ? provider.title : own
    }
}

/// One model of one connection, ready to be asked: its key read from the Keychain, and
/// the time left for the reading.
struct AIEndpoint: Sendable {
    let connection: AIConnection
    let model: String
    let apiKey: String?
    /// When this model's turn ends, whatever number of requests it takes (another format,
    /// another try without options): each one gets only the time left.
    var deadline: Date = .distantFuture

    var provider: AIProvider { connection.provider }
    var baseURL: String { connection.effectiveBaseURL }

    /// Local models can take a while, especially the first time they load.
    var requestTimeout: TimeInterval { max(1, min(provider.isLocal ? 300 : 120, deadline.timeIntervalSinceNow)) }
}

/// A label read: what it says, and which model read it.
struct AIReading: Sendable {
    var analysis: LabelAnalysis
    var model: AIModelChoice
    var connection: AIConnection

    /// "model · connection", as shown after a reading.
    var modelTitle: String { "\(model.model) · \(connection.title)" }
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
    /// Alcohol by volume printed on the label, in percent; 0 when not printed. Older answers
    /// and providers may leave it out (read as 0).
    var abv: Double = 0
    /// A classification printed on the label ("Grand Cru"); "" when none (or left out).
    var classification: String = ""
    /// Where the whole front label is in the photo ("label_box" and "label_angle"), checked
    /// when the answer is read; nil when absent or invalid, which never fails the answer.
    var placement: AILabelPlacement? = nil
    /// The details the model gave from its own knowledge rather than read on the label
    /// (`LabelRequest.sourceKeys`), read from "sources" when the answer is read; the form
    /// marks them "Estimated by AI" until they are edited.
    var estimated: Set<String> = ["drinkWindow"]
    /// What to expect from the wine, when the request asked it (`LabelRequest.expectedLanguage`).
    var expected: ExpectedProfile? = nil

    private enum CodingKeys: String, CodingKey {
        case name, producer, vintage, color, appellation, region, country, grapes, drinkFrom, drinkUntil
        case abv, classification
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
        var touched: Set<String> = []
        func fill(_ field: WritableKeyPath<WineDraft, String>, _ key: String, _ value: String) {
            let value = value.trimmingCharacters(in: .whitespaces)
            let current = draft[keyPath: field].trimmingCharacters(in: .whitespaces)
            if !value.isEmpty, current.isEmpty || current == filled[keyPath: field] {
                draft[keyPath: field] = value
                filled[keyPath: field] = value
                touched.insert(key)
            }
        }
        fill(\.name, "name", name)
        fill(\.producer, "producer", producer)
        fill(\.appellation, "appellation", appellation)
        fill(\.region, "region", region)
        fill(\.country, "country", country)
        fill(\.grapes, "grapes", grapes.joined(separator: ", "))
        fill(\.classification, "classification", classification)
        // A plausible strength only (a misread "135" is not one).
        if abv > 0, abv <= 100, let value = Decimal(string: String(format: "%.2f", locale: Locale(identifier: "en_US_POSIX"), abv)),
           draft.abv == nil || draft.abv == filled.abv {
            draft.abv = value
            filled.abv = value
            touched.insert("abv")
        }
        if (1900...2100).contains(vintage), draft.vintage == nil || draft.vintage == filled.vintage {
            draft.vintage = vintage
            filled.vintage = vintage
            touched.insert("vintage")
        }
        if let wineColor, draft.color == nil || draft.color == filled.color {
            draft.color = wineColor
            filled.color = wineColor
            touched.insert("color")
        }
        if draft.windowIsAutomatic, drinkFrom > 1900, drinkUntil >= drinkFrom {
            draft.windowIsAutomatic = false
            draft.drinkFrom = drinkFrom
            draft.drinkUntil = drinkUntil
            touched.insert("drinkWindow")
        }
        // What to expect, unless the wine has one this reading did not fill.
        if let expected, draft.expectedProfile == nil || draft.expectedProfile == filled.expectedProfile {
            draft.expectedProfile = expected
            filled.expectedProfile = expected
        }
        // What this answer filled is marked when the model estimated it, unmarked when read on the label.
        for key in touched {
            draft.aiEstimates[key] = estimated.contains(key) ? draft.aiValue(key) : nil
        }
        return draft != before
    }
}

extension LabelAnalysis {
    /// The details asked since the wine details came (alcohol, classification) may be
    /// missing or of another type in an answer: they never fail it.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = try c.decode(String.self, forKey: .name)
        producer = try c.decode(String.self, forKey: .producer)
        vintage = try c.decode(Int.self, forKey: .vintage)
        color = try c.decode(String.self, forKey: .color)
        appellation = try c.decode(String.self, forKey: .appellation)
        region = try c.decode(String.self, forKey: .region)
        country = try c.decode(String.self, forKey: .country)
        grapes = try c.decode([String].self, forKey: .grapes)
        drinkFrom = try c.decode(Int.self, forKey: .drinkFrom)
        drinkUntil = try c.decode(Int.self, forKey: .drinkUntil)
        abv = (try? c.decodeIfPresent(Double.self, forKey: .abv)) ?? 0
        classification = (try? c.decodeIfPresent(String.self, forKey: .classification)) ?? ""
    }
}

typealias JSONObject = [String: Any]

/// One question for a model: the text, the schema of the answer, and the label photo when
/// there is one (a question about the wine's identity sends none). With `webSearch`, the
/// model may search the web where its connection allows it.
struct AIQuery: Sendable {
    var prompt: String
    /// The schema, as JSON (a dictionary is not Sendable).
    var schemaJSON: Data
    var schemaName: String
    var jpeg: Data?
    var webSearch = false

    init(prompt: String, schema: JSONObject, schemaName: String, jpeg: Data?, webSearch: Bool = false) throws {
        self.prompt = prompt
        self.schemaJSON = try JSONSerialization.data(withJSONObject: schema)
        self.schemaName = schemaName
        self.jpeg = jpeg
        self.webSearch = webSearch
    }

    var schema: JSONObject { ((try? JSONSerialization.jsonObject(with: schemaJSON)) as? JSONObject) ?? [:] }
}

/// A model's answer: its text, and the pages it cited when it searched the web.
struct AIAnswer: Sendable {
    var text: String
    var sources: [WineWebSource] = []
}

enum AIServiceError: LocalizedError {
    case notConfigured
    case missingKey
    case noModel
    case refused
    case server(status: Int, message: String)
    case invalidResponse
    /// Not an https:// address, or plain http:// to a server outside the local network.
    case insecureURL
    /// Every model in the list failed: a line per model.
    case allModelsFailed([String])

    var errorDescription: String? {
        switch self {
        case .notConfigured: String(localized: "Add a model in Settings › AI.")
        case .missingKey: String(localized: "Add your API key in Settings.")
        case .noModel: String(localized: "Choose a model in Settings.")
        case .refused: String(localized: "The model declined to read this photo.")
        case .server(let status, let message):
            message.isEmpty ? String(localized: "The AI service returned error \(status).") : message
        case .invalidResponse: String(localized: "The answer from the AI service could not be read.")
        case .insecureURL:
            String(localized: "Enter the full server address, starting with https:// (http:// only for a server on your local network).")
        case .allModelsFailed(let lines):
            ([String(localized: "No model could read the label:")] + lines.map { "• \($0)" }).joined(separator: "\n")
        }
    }

    /// What failed, per model, when every one did; the error itself when there was only one.
    static func describing(_ error: AIAllModelsFailed, in configuration: AIConfiguration) -> Error {
        if error.failures.count == 1, error.untried.isEmpty { return error.failures[0].error }
        func name(_ model: AIModelChoice) -> String {
            configuration.connection(for: model).map { "\(model.model) (\($0.title))" } ?? model.model
        }
        let failed = error.failures.map { failure in
            let modelName = name(failure.model), errorLabel = failure.error.localizedDescription
            return String(localized: "\(modelName): \(errorLabel)")
        }
        let untried = error.untried.map { model in
            let modelName = name(model)
            return String(localized: "\(modelName): not tried, the time for a reading ran out")
        }
        return AIServiceError.allModelsFailed(failed + untried)
    }
}

enum AIService {
    /// The studio look is on (the default): the same request also asks where the label is.
    static var findsLabel: Bool {
        UserDefaults.standard.object(forKey: StudioPhoto.settingKey) as? Bool ?? true
    }

    /// The language of the app ("en", "fr"), for the short text of what to expect.
    static var language: String {
        (Bundle.main.preferredLocalizations.first ?? "en").hasPrefix("fr") ? "fr" : "en"
    }

    /// Sends the label photo (a resized JPEG) to the first model of the list; if it fails,
    /// to the next, one at a time, within `AIFallback.totalBudget`. With `only`, to that model.
    static func analyzeLabel(jpeg: Data, configuration: AIConfiguration = AISettings.load(), only modelID: UUID? = nil,
                             request: LabelRequest = LabelRequest(findsLabel: findsLabel, expectedLanguage: language)) async throws -> AIReading {
        let order = configuration.readingOrder(only: modelID)
        guard !order.isEmpty else { throw AIServiceError.notConfigured }
        let keys = AIKeychain()
        do {
            let (model, analysis) = try await AIFallback.read(order) { model, left in
                guard let connection = configuration.connection(for: model) else { throw AIServiceError.notConfigured }
                let endpoint = AIEndpoint(connection: connection, model: model.model, apiKey: keys.key(for: connection),
                                          deadline: Date().addingTimeInterval(left))
                return try await analyze(jpeg: jpeg, endpoint: endpoint, request: request)
            }
            guard let connection = configuration.connection(for: model) else { throw AIServiceError.notConfigured }
            return AIReading(analysis: analysis, model: model, connection: connection)
        } catch let error as AIAllModelsFailed {
            throw AIServiceError.describing(error, in: configuration)
        }
    }

    /// One model, one request (two or three for a service that does not take every format).
    static func analyze(jpeg: Data, endpoint: AIEndpoint, request: LabelRequest) async throws -> LabelAnalysis {
        let query = try AIQuery(prompt: request.prompt, schema: request.schema, schemaName: "wine_label", jpeg: jpeg)
        let answer = try await ask(query, endpoint: endpoint)
        var analysis = try decode(answer.text)
        // Not asked for: an answer that gives it anyway is not used.
        if !request.findsLabel { analysis.placement = nil }
        if let language = request.expectedLanguage {
            analysis.expected = ExpectedRequest.profile(fromAnswer: answer.text, source: ExpectedProfile.ai, model: endpoint.model,
                                                        language: language, date: Date())
        }
        return analysis
    }

    /// Sends a query to one model: the photo when there is one, the schema of the answer,
    /// and nothing else.
    static func ask(_ query: AIQuery, endpoint: AIEndpoint) async throws -> AIAnswer {
        if endpoint.provider.requiresKey, (endpoint.apiKey ?? "").isEmpty { throw AIServiceError.missingKey }
        guard !endpoint.model.trimmingCharacters(in: .whitespaces).isEmpty else { throw AIServiceError.noModel }
        if query.webSearch, query.jpeg == nil, endpoint.connection.webSearch == .responsesTool {
            return try await responses(query, endpoint: endpoint)
        }
        if endpoint.provider.usesAnthropicAPI { return try await claude(query, endpoint: endpoint) }
        if endpoint.provider.usesOllamaAPI { return try await ollama(query, endpoint: endpoint) }
        return try await openAICompatible(query, endpoint: endpoint)
    }

    /// What to expect from a wine, from its identity alone (producer, name, appellation,
    /// vintage, colour, grapes): never the photo, the cellar, notes, prices or places. The
    /// models in order, as for a label. Nil when the model did not know the wine.
    static func expectedProfile(for identity: WineIdentity, language: String, configuration: AIConfiguration = AISettings.load(),
                                only modelID: UUID? = nil) async throws -> (profile: ExpectedProfile?, model: AIModelChoice) {
        let order = configuration.readingOrder(only: modelID)
        guard !order.isEmpty else { throw AIServiceError.notConfigured }
        let query = try AIQuery(prompt: ExpectedRequest.prompt(for: identity, language: language),
                                schema: ExpectedRequest.answerSchema, schemaName: "wine_expected", jpeg: nil)
        let keys = AIKeychain()
        do {
            let (model, answer) = try await AIFallback.read(order) { model, left in
                guard let connection = configuration.connection(for: model) else { throw AIServiceError.notConfigured }
                let endpoint = AIEndpoint(connection: connection, model: model.model, apiKey: keys.key(for: connection),
                                          deadline: Date().addingTimeInterval(left))
                return try await ask(query, endpoint: endpoint)
            }
            let profile = ExpectedRequest.profile(fromAnswer: answer.text, source: ExpectedProfile.ai, model: model.model,
                                                  language: language, date: Date())
            return (profile, model)
        } catch let error as AIAllModelsFailed {
            throw AIServiceError.describing(error, in: configuration)
        }
    }

    /// Completes a saved wine from the web: the models whose connection allows web search,
    /// in order, are sent only the wine's identity and may search the web with it. Never a
    /// photo, the cellar, notes, prices or places. Nil when the answer could not be read.
    static func completeFromWeb(_ identity: WineIdentity, language: String,
                                configuration: AIConfiguration = AISettings.load()) async throws
        -> (completion: WebCompletion, sources: [WineWebSource], model: String)? {
        let order = configuration.webSearchOrder
        guard !order.isEmpty else { throw AIServiceError.notConfigured }
        let query = try AIQuery(prompt: WebCompletion.prompt(for: identity, language: language), schema: WebCompletion.schema,
                                schemaName: "wine_web", jpeg: nil, webSearch: true)
        let keys = AIKeychain()
        do {
            let (model, answer) = try await AIFallback.read(order) { model, left in
                guard let connection = configuration.connection(for: model) else { throw AIServiceError.notConfigured }
                let endpoint = AIEndpoint(connection: connection, model: model.model, apiKey: keys.key(for: connection),
                                          deadline: Date().addingTimeInterval(left))
                return try await ask(query, endpoint: endpoint)
            }
            guard let completion = WebCompletion.parse(answer: answer.text, model: model.model, language: language, date: Date()) else {
                return nil
            }
            return (completion, answer.sources, model.model)
        } catch let error as AIAllModelsFailed {
            throw AIServiceError.describing(error, in: configuration)
        }
    }

    /// The "Test" of a model: its key works and it answers on a picture of a made-up label
    /// bundled with the app. No photo or data of the user is sent.
    static func test(connection: AIConnection, model: String, apiKey: String?) async throws {
        guard let url = Bundle.main.url(forResource: "ai-test-label", withExtension: "jpg"),
              let jpeg = try? Data(contentsOf: url) else { throw AIServiceError.invalidResponse }
        let endpoint = AIEndpoint(connection: connection, model: model, apiKey: apiKey)
        _ = try await analyze(jpeg: jpeg, endpoint: endpoint, request: LabelRequest(findsLabel: false))
    }

    /// Checks the key by listing the models it can use. Returns their identifiers, sorted.
    /// Through OpenRouter, only the models that read images.
    static func models(connection: AIConnection, apiKey: String?) async throws -> [String] {
        let provider = connection.provider
        if provider.requiresKey, (apiKey ?? "").isEmpty { throw AIServiceError.missingKey }
        if provider.usesOllamaAPI { return try await ollamaModels(baseURL: connection.effectiveBaseURL) }
        guard let url = URL(string: connection.effectiveBaseURL + "/models" + (provider.usesAnthropicAPI ? "?limit=100" : "")) else {
            throw AIServiceError.notConfigured
        }
        var request = URLRequest(url: url)
        request.timeoutInterval = 30
        if provider.usesAnthropicAPI {
            request.setValue(apiKey, forHTTPHeaderField: "x-api-key")
            request.setValue("2023-06-01", forHTTPHeaderField: "anthropic-version")
        } else if let apiKey, !apiKey.isEmpty {
            request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        }
        let json = try await send(request)
        let items = (json["data"] as? [[String: Any]]) ?? (json["models"] as? [[String: Any]]) ?? []
        if provider == .openRouter {
            let vision = AIRequestBody.openRouterVisionModels(items)
            // A server at another address that does not describe its models: all of them.
            if !vision.isEmpty || items.contains(where: { $0["architecture"] != nil }) {
                return vision.sorted { $0.localizedStandardCompare($1) == .orderedAscending }
            }
        }
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

    private static func claude(_ query: AIQuery, endpoint: AIEndpoint) async throws -> AIAnswer {
        let options = supportsEffort(endpoint.model) || supportsFallbacks(endpoint.model)
        do {
            return try await claude(query, endpoint: endpoint, withOptions: options)
        } catch AIServiceError.server(let status, _) where status == 400 && options {
            // A model or server that does not take these options yet: once more without them.
            return try await claude(query, endpoint: endpoint, withOptions: false)
        }
    }

    private static func claude(_ query: AIQuery, endpoint: AIEndpoint, withOptions: Bool) async throws -> AIAnswer {
        guard let url = URL(string: endpoint.baseURL + "/messages") else { throw AIServiceError.notConfigured }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = endpoint.requestTimeout
        request.setValue(endpoint.apiKey ?? "", forHTTPHeaderField: "x-api-key")
        request.setValue("2023-06-01", forHTTPHeaderField: "anthropic-version")
        request.setValue("application/json", forHTTPHeaderField: "content-type")
        let body = AIRequestBody.claude(model: endpoint.model, prompt: query.prompt, schema: query.schema, jpeg: query.jpeg,
                                        webSearch: query.webSearch, effort: withOptions && supportsEffort(endpoint.model),
                                        fallbacks: withOptions && supportsFallbacks(endpoint.model))
        if withOptions, supportsFallbacks(endpoint.model) {
            // Re-runs a declined request on another model instead of returning a refusal.
            request.setValue("server-side-fallback-2026-07-01", forHTTPHeaderField: "anthropic-beta")
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let json = try await send(request)
        if json["stop_reason"] as? String == "refusal" { throw AIServiceError.refused }
        let blocks = json["content"] as? [[String: Any]] ?? []
        // With web search, the answer is the last text, after the searches.
        let texts = blocks.filter { $0["type"] as? String == "text" }.compactMap { $0["text"] as? String }
        guard let text = query.webSearch ? texts.last(where: { $0.contains("{") }) ?? texts.last : texts.first else {
            throw AIServiceError.invalidResponse
        }
        return AIAnswer(text: text, sources: AIWebSearch.claude(blocks))
    }

    // MARK: Responses API (OpenAI and xAI, with their web search tool)

    private static func responses(_ query: AIQuery, endpoint: AIEndpoint) async throws -> AIAnswer {
        guard let url = URL(string: endpoint.baseURL + "/responses") else { throw AIServiceError.notConfigured }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = endpoint.requestTimeout
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let apiKey = endpoint.apiKey, !apiKey.isEmpty {
            request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: AIRequestBody.responses(
            model: endpoint.model, prompt: query.prompt, schema: query.schema))
        let json = try await send(request)
        let (text, sources) = AIWebSearch.responses(json)
        guard let text else { throw AIServiceError.invalidResponse }
        return AIAnswer(text: text, sources: sources)
    }

    // MARK: OpenAI-compatible (Chat Completions)

    private static func openAICompatible(_ query: AIQuery, endpoint: AIEndpoint) async throws -> AIAnswer {
        // Not every service supports a JSON schema: fall back to plain JSON, then to no format.
        let formats: [JSONObject?] = [
            ["type": "json_schema",
             "json_schema": ["name": query.schemaName, "strict": true, "schema": query.schema] as JSONObject],
            ["type": "json_object"],
            nil,
        ]
        var lastError: Error = AIServiceError.invalidResponse
        for format in formats {
            do {
                return try await openAICompatible(query, endpoint: endpoint, format: format)
            } catch AIServiceError.server(let status, let message) where status == 400 || status == 422 {
                lastError = AIServiceError.server(status: status, message: message)
            }
        }
        throw lastError
    }

    private static func openAICompatible(_ query: AIQuery, endpoint: AIEndpoint, format: JSONObject?) async throws -> AIAnswer {
        guard let url = URL(string: endpoint.baseURL + "/chat/completions") else {
            throw AIServiceError.notConfigured
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = endpoint.requestTimeout
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let apiKey = endpoint.apiKey, !apiKey.isEmpty {
            request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        }
        // Through OpenRouter, with the provider preferences that refuse providers keeping the data.
        let body = AIRequestBody.chatCompletions(connection: endpoint.connection, model: endpoint.model,
                                                 prompt: query.prompt, jpeg: query.jpeg, format: format, webSearch: query.webSearch)
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let json = try await send(request)
        let choices = json["choices"] as? [[String: Any]] ?? []
        let message = choices.first?["message"] as? [String: Any]
        if let refusal = message?["refusal"] as? String, !refusal.isEmpty { throw AIServiceError.refused }
        guard let text = message?["content"] as? String else { throw AIServiceError.invalidResponse }
        return AIAnswer(text: text, sources: AIWebSearch.chatCompletions(message ?? [:]))
    }

    // MARK: Ollama (native API)

    /// "http://host:11434", whether the user typed it with "/v1" or "/api" or not.
    private static func ollamaBase(_ baseURL: String) -> String {
        var base = baseURL
        for suffix in ["/v1", "/api"] where base.hasSuffix(suffix) { base = String(base.dropLast(suffix.count)) }
        return base
    }

    /// Structured output with the answer's JSON schema, and the photo in "images".
    private static func ollama(_ query: AIQuery, endpoint: AIEndpoint) async throws -> AIAnswer {
        guard let url = URL(string: ollamaBase(endpoint.baseURL) + "/api/chat") else { throw AIServiceError.notConfigured }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = endpoint.requestTimeout
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        var message: JSONObject = ["role": "user", "content": query.prompt]
        if let jpeg = query.jpeg { message["images"] = [jpeg.base64EncodedString()] }
        let body: JSONObject = [
            "model": endpoint.model,
            "stream": false,
            "format": query.schema,
            // A photo needs more room than Ollama's default context.
            "options": ["temperature": 0, "num_ctx": 8192] as JSONObject,
            "messages": [message],
        ]
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let json = try await send(request)
        guard let text = (json["message"] as? [String: Any])?["content"] as? String else {
            throw AIServiceError.invalidResponse
        }
        return AIAnswer(text: text, sources: [])
    }

    /// Installed models, the ones that read images first (Ollama reports "vision" for them).
    private static func ollamaModels(baseURL: String) async throws -> [String] {
        let base = ollamaBase(baseURL)
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
    /// A name over http is checked and replaced by its local address in `send` (`LocalName`).
    static func isAllowed(_ url: URL?) -> Bool {
        guard let url, let host = url.host, !host.isEmpty else { return false }
        switch url.scheme?.lowercased() {
        case "https": return true
        case "http": return LocalName.mayBeLocal(host: host)
        default: return false
        }
    }

    /// No cookies, cache or saved credentials: the label photo is never kept on disk.
    private static let session = URLSession(configuration: WebSearchClient.privateConfiguration())
    /// The same for plain HTTP to a server on the local network, without a proxy.
    private static let localSession: URLSession = {
        let configuration = WebSearchClient.privateConfiguration()
        configuration.connectionProxyDictionary = [:]
        return URLSession(configuration: configuration)
    }()

    /// Every request goes through here.
    private static func send(_ request: URLRequest) async throws -> [String: Any] {
        guard let url = request.url, isAllowed(url) else { throw AIServiceError.insecureURL }
        var request = request
        request.url = try await LocalName.resolve(url)
        // Plain HTTP (only ever local) never goes through a proxy, which would read it in clear text.
        let session = request.url?.scheme?.lowercased() == "http" ? localSession : Self.session
        let (data, response) = try await CappedRead.data(session, for: request, limit: CappedRead.jsonLimit, delegate: SameHostRedirects.shared)
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
        analysis.estimated = LabelSources.estimated(inAnswer: text)
        return analysis
    }
}
