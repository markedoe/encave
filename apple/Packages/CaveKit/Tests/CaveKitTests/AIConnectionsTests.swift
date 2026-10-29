import Foundation
import Testing
@testable import CaveKit

/// A Keychain in memory; `failing` accounts cannot be read or written.
private final class MemoryKeys: AIKeyStore {
    var items: [String: String] = [:]
    var failing: Set<String> = []

    func readKey(_ account: String) -> AIKeyRead {
        if failing.contains(account) { return .failed }
        return items[account].map(AIKeyRead.found) ?? .missing
    }

    func writeKey(_ value: String?, account: String) -> Bool {
        if failing.contains(account) { return false }
        if let value, !value.isEmpty { items[account] = value } else { items[account] = nil }
        return true
    }
}

private struct HTTPStatus: Error { let status: Int }

private func freshDefaults() -> UserDefaults {
    let name = "ai-tests-\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: name)!
    defaults.removePersistentDomain(forName: name)
    return defaults
}

@Suite struct AIConnectionsTests {
    @Test func theProviderInUseBecomesTheFirstConnectionAndModel() throws {
        let defaults = freshDefaults()
        defaults.set("mistral", forKey: "ai.provider")
        defaults.set("pixtral-large-latest", forKey: "ai.model.mistral")
        defaults.set("https://example.test/v1", forKey: "ai.baseURL.mistral")
        let keys = MemoryKeys()
        keys.items["ai.apiKey.mistral"] = "test-key-mistral"
        keys.items["ai.apiKey.openRouter"] = "test-key-openrouter"

        let configuration = AIConfigurationStore.load(defaults: defaults, keys: keys)
        #expect(configuration.connections.map(\.provider) == [.mistral, .openRouter])
        let first = configuration.connections[0]
        #expect(first.baseURL == "https://example.test/v1")
        #expect(configuration.models.map(\.model) == ["pixtral-large-latest"])
        #expect(configuration.models.first?.connectionID == first.id)
        #expect(configuration.isEnabled)
        // Keys moved, not lost: at their connection, and gone from the old places.
        #expect(keys.key(for: first) == "test-key-mistral")
        #expect(keys.key(for: configuration.connections[1]) == "test-key-openrouter")
        #expect(keys.items["ai.apiKey.mistral"] == nil && keys.items["ai.apiKey.openRouter"] == nil)
        // Done once: loading again gives the same configuration.
        #expect(AIConfigurationStore.load(defaults: defaults, keys: keys) == configuration)
    }

    @Test func theFirstClaudeKeyAndSuggestedModelAreKept() {
        let defaults = freshDefaults()
        defaults.set("claude", forKey: "ai.provider")
        let keys = MemoryKeys()
        keys.items["ai.apiKey"] = "  \"test-key-claude\"\n"
        let configuration = AIConfigurationStore.load(defaults: defaults, keys: keys)
        #expect(configuration.connections.map(\.provider) == [.anthropic])
        #expect(configuration.models.map(\.model) == ["claude-opus-5-5"])
        #expect(keys.key(for: configuration.connections[0]) == "test-key-claude")
        #expect(keys.items["ai.apiKey"] == nil)
    }

    @Test func theFirstLocalOptionMovesToOllama() {
        let defaults = freshDefaults()
        defaults.set("local", forKey: "ai.provider")
        defaults.set("http://example.test:11434/v1", forKey: "ai.baseURL.local")
        defaults.set("gemma3", forKey: "ai.model")
        let configuration = AIConfigurationStore.load(defaults: defaults, keys: MemoryKeys())
        #expect(configuration.connections.first?.provider == .ollama)
        #expect(configuration.connections.first?.baseURL == "http://example.test:11434")
        #expect(configuration.models.map(\.model) == ["gemma3"])
    }

    @Test func aKeyThatCannotBeReadLeavesEverythingForNextTime() {
        let defaults = freshDefaults()
        defaults.set("openAI", forKey: "ai.provider")
        defaults.set("gpt-test", forKey: "ai.model.openAI")
        let keys = MemoryKeys()
        keys.items["ai.apiKey.openAI"] = "test-key-openai"
        keys.failing = ["ai.apiKey.xai"]
        #expect(AIConfigurationStore.load(defaults: defaults, keys: keys) == AIConfiguration())
        #expect(defaults.data(forKey: AIConfigurationStore.defaultsKey) == nil)
        #expect(keys.items["ai.apiKey.openAI"] == "test-key-openai")
        keys.failing = []
        let configuration = AIConfigurationStore.load(defaults: defaults, keys: keys)
        #expect(configuration.models.map(\.model) == ["gpt-test"])
        #expect(keys.key(for: configuration.connections[0]) == "test-key-openai")
    }

    @Test func aMoveRetriedLaterKeepsWhatWasSetUpMeanwhile() {
        let defaults = freshDefaults()
        defaults.set("openAI", forKey: "ai.provider")
        defaults.set("gpt-test", forKey: "ai.model.openAI")
        let keys = MemoryKeys()
        keys.items["ai.apiKey.openAI"] = "test-key-openai"
        keys.failing = ["ai.apiKey.xai"]
        // The move fails; the user adds a connection meanwhile.
        var meanwhile = AIConfigurationStore.load(defaults: defaults, keys: keys)
        let added = AIConnection(provider: .ollama)
        meanwhile.upsert(added)
        meanwhile.addModel("gemma3", connection: added.id)
        AIConfigurationStore.save(meanwhile, defaults: defaults)
        keys.failing = []
        let configuration = AIConfigurationStore.load(defaults: defaults, keys: keys)
        #expect(configuration.connections.map(\.provider) == [.openAI, .ollama])
        #expect(configuration.models.map(\.model) == ["gpt-test", "gemma3"])
        #expect(keys.key(for: configuration.connections[0]) == "test-key-openai")
        #expect(defaults.bool(forKey: AIConfigurationStore.migratedKey))
    }

    @Test func aMoveDoneTwiceWritesItsKeyAtTheSamePlace() {
        let first = AIConfigurationStore.migratedID(.mistral)
        #expect(first == AIConfigurationStore.migratedID(.mistral))
        #expect(first != AIConfigurationStore.migratedID(.openAI))
        #expect(first.uuidString.dropFirst(14).first == "5")
    }

    @Test func aConnectionPointedAtOpenRouterAlsoRefusesDataCollection() {
        #expect(AIConnection(provider: .local, baseURL: "https://openrouter.ai/api/v1").deniesDataCollection)
        #expect(!AIConnection(provider: .openAI).deniesDataCollection)
        #expect(!AIConnection(provider: .openRouter, allowsDataCollection: true).deniesDataCollection)
    }

    @Test func nothingSetUpStaysOff() {
        let configuration = AIConfigurationStore.load(defaults: freshDefaults(), keys: MemoryKeys())
        #expect(configuration.connections.isEmpty && !configuration.isEnabled)
    }

    @Test func keysAreNeverInTheSavedConfiguration() throws {
        let defaults = freshDefaults()
        defaults.set("openRouter", forKey: "ai.provider")
        defaults.set("vendor/vision-test", forKey: "ai.model.openRouter")
        let keys = MemoryKeys()
        keys.items["ai.apiKey.openRouter"] = "test-key-secret-value"
        let configuration = AIConfigurationStore.load(defaults: defaults, keys: keys)
        let saved = try #require(defaults.data(forKey: AIConfigurationStore.defaultsKey))
        #expect(!String(decoding: saved, as: UTF8.self).contains("test-key-secret-value"))
        #expect(!String(decoding: try JSONEncoder().encode(configuration), as: UTF8.self).contains("secret"))
        // Nor anywhere else in the settings this device keeps.
        let everything = defaults.dictionaryRepresentation().values.map { "\($0)" }.joined()
        #expect(!everything.contains("test-key-secret-value"))
    }

    @Test func modelsKeepTheirOrderAndGoWithTheirConnection() {
        var configuration = AIConfiguration()
        let a = AIConnection(provider: .openRouter, name: "OpenRouter perso")
        let b = AIConnection(provider: .ollama)
        configuration.upsert(a)
        configuration.upsert(b)
        let one = configuration.addModel("vendor/one", connection: a.id)!
        let two = configuration.addModel("gemma3", connection: b.id)!
        let three = configuration.addModel("vendor/three", connection: a.id)!
        #expect(configuration.addModel("vendor/one", connection: a.id) == one)
        #expect(configuration.addModel("  ", connection: a.id) == nil)
        configuration.moveModels(fromOffsets: IndexSet(integer: 2), toOffset: 0)
        #expect(configuration.models == [three, one, two])
        configuration.moveModels(fromOffsets: IndexSet(integer: 0), toOffset: 3)
        #expect(configuration.models == [one, two, three])
        #expect(configuration.readingOrder(only: two.id) == [two])
        configuration.removeConnection(a.id)
        #expect(configuration.models == [two])
        #expect(configuration.connections == [b])
        // Saved and read back the same, in order.
        let data = try! JSONEncoder().encode(configuration)
        #expect(try! JSONDecoder().decode(AIConfiguration.self, from: data) == configuration)
    }

    @Test func aConnectionOfANewerVersionIsLeftOut() throws {
        let known = UUID(), unknown = UUID()
        let json = """
        {"connections":[{"id":"\(known.uuidString)","provider":"mistral"},{"id":"\(unknown.uuidString)","provider":"future"}],
         "models":[{"id":"\(UUID().uuidString)","connectionID":"\(unknown.uuidString)","model":"x"},
                   {"id":"\(UUID().uuidString)","connectionID":"\(known.uuidString)","model":"y"}]}
        """
        let configuration = try JSONDecoder().decode(AIConfiguration.self, from: Data(json.utf8))
        #expect(configuration.connections.map(\.id) == [known])
        #expect(configuration.models.map(\.model) == ["y"])
    }

    @Test func aFailedModelHandsOverToTheNextOneAtATime() async throws {
        let connection = AIConnection(provider: .openRouter)
        let first = AIModelChoice(connectionID: connection.id, model: "first")
        let second = AIModelChoice(connectionID: connection.id, model: "second")
        let third = AIModelChoice(connectionID: connection.id, model: "third")
        var asked: [String] = []
        var running = 0, mostAtOnce = 0
        let result = try await AIFallback.read([first, second, third]) { model, _ in
            running += 1
            mostAtOnce = max(mostAtOnce, running)
            defer { running -= 1 }
            asked.append(model.model)
            try await Task.sleep(nanoseconds: 1_000_000)
            if model == first { throw HTTPStatus(status: 429) }
            return "read by \(model.model)"
        }
        #expect(result.model == second)
        #expect(result.answer == "read by second")
        #expect(asked == ["first", "second"])
        #expect(mostAtOnce == 1)
    }

    @Test func whenEveryModelFailsEachFailureIsListed() async {
        let connection = AIConnection(provider: .anthropic)
        let models = [401, 503, 0].map { AIModelChoice(connectionID: connection.id, model: "m\($0)") }
        do {
            _ = try await AIFallback.read(models) { model, _ -> String in
                throw HTTPStatus(status: Int(model.model.dropFirst())!)
            }
            Issue.record("Expected every model to fail")
        } catch let error as AIAllModelsFailed {
            #expect(error.failures.map(\.model) == models)
            #expect(error.failures.map { ($0.error as? HTTPStatus)?.status } == [401, 503, 0])
            #expect(error.untried.isEmpty)
        } catch {
            Issue.record("Unexpected \(error)")
        }
    }

    @Test func theTimeBudgetStopsBeforeTheNextModel() async {
        let connection = AIConnection(provider: .ollama)
        let models = ["slow", "next"].map { AIModelChoice(connectionID: connection.id, model: $0) }
        var clock = Date(timeIntervalSince1970: 0)
        var asked: [String] = []
        do {
            _ = try await AIFallback.read(models, budget: 100, now: { clock }) { model, left -> String in
                asked.append(model.model)
                #expect(left == 100)
                clock = clock.addingTimeInterval(95)
                throw HTTPStatus(status: 0)
            }
            Issue.record("Expected a failure")
        } catch let error as AIAllModelsFailed {
            #expect(asked == ["slow"])
            #expect(error.untried.map(\.model) == ["next"])
        } catch {
            Issue.record("Unexpected \(error)")
        }
    }

    @Test func cancellingStopsTheReading() async {
        let connection = AIConnection(provider: .openAI)
        let models = ["a", "b"].map { AIModelChoice(connectionID: connection.id, model: $0) }
        var asked = 0
        do {
            _ = try await AIFallback.read(models) { _, _ -> String in
                asked += 1
                throw CancellationError()
            }
        } catch {
            #expect(error is CancellationError)
        }
        #expect(asked == 1)
    }

    @Test func openRouterRequestsRefuseProvidersThatKeepData() throws {
        let jpeg = Data([0xFF, 0xD8, 0xFF])
        var connection = AIConnection(provider: .openRouter)
        let body = AIRequestBody.chatCompletions(connection: connection, model: "vendor/vision", prompt: "p", jpeg: jpeg, format: nil)
        #expect((body["provider"] as? [String: Any])?["data_collection"] as? String == "deny")
        let encoded = try JSONSerialization.data(withJSONObject: body)
        #expect(String(decoding: encoded, as: UTF8.self).contains("\"data_collection\":\"deny\""))

        connection.allowsDataCollection = true
        let allowed = AIRequestBody.chatCompletions(connection: connection, model: "vendor/vision", prompt: "p", jpeg: jpeg, format: nil)
        #expect(allowed["provider"] == nil)
        // Other services never get OpenRouter's field.
        let other = AIRequestBody.chatCompletions(connection: AIConnection(provider: .openAI), model: "m", prompt: "p",
                                                  jpeg: jpeg, format: ["type": "json_object"])
        #expect(other["provider"] == nil)
        #expect((other["response_format"] as? [String: String])?["type"] == "json_object")
    }

    @Test func openRouterListsOnlyModelsThatReadImages() {
        let items: [[String: Any]] = [
            ["id": "vendor/vision", "architecture": ["input_modalities": ["text", "image"]]],
            ["id": "vendor/text", "architecture": ["input_modalities": ["text"]]],
            ["id": "vendor/unknown"],
        ]
        #expect(AIRequestBody.openRouterVisionModels(items) == ["vendor/vision"])
    }

    @Test func connectionAddressesAndKeyAccounts() {
        let connection = AIConnection(provider: .local, baseURL: "http://example.test:8080/v1/")
        #expect(connection.effectiveBaseURL == "http://example.test:8080/v1")
        #expect(AIConnection(provider: .mistral).effectiveBaseURL == "https://api.mistral.ai/v1")
        #expect(connection.keyAccount.hasPrefix("ai.connection."))
        #expect(AIKeys.clean(" “sk-abc”\n") == "sk-abc")
        #expect(AIProvider.allCases.filter { $0.keyPage == nil } == [.local])
        #expect(!AIProvider.ollama.requiresKey && AIProvider.mistral.requiresKey)
    }
}
