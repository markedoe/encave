import Foundation
import Testing
@testable import CaveKit

/// Web search: which connections may search, what the requests carry, and the pages cited
/// (`fixtures/ai-web-source-vectors.json`, as `AiWebSearchTest.kt`).
@Suite struct AIWebSearchTests {
    private func sources(_ value: Any?) -> [WineWebSource] {
        ((value as? [[String: Any]]) ?? []).map { WineWebSource(url: $0["url"] as? String ?? "", title: $0["title"] as? String) }
    }

    @Test func sourceVectors() throws {
        let vectors = try SharedFixture.object("ai-web-source-vectors.json")
        for row in vectors["claude"] as? [[String: Any]] ?? [] {
            #expect(AIWebSearch.claude(row["blocks"] as? [[String: Any]] ?? []) == sources(row["sources"]), "\(row["note"] ?? "")")
        }
        for row in vectors["chatCompletions"] as? [[String: Any]] ?? [] {
            #expect(AIWebSearch.chatCompletions(row["message"] as? [String: Any] ?? [:]) == sources(row["sources"]), "\(row["note"] ?? "")")
        }
        for row in vectors["responses"] as? [[String: Any]] ?? [] {
            let read = AIWebSearch.responses(row["json"] as? [String: Any] ?? [:])
            #expect(read.text == row["text"] as? String, "\(row["note"] ?? "")")
            #expect(read.sources == sources(row["sources"]), "\(row["note"] ?? "")")
        }
    }

    @Test func onlyProvidersThatSearchOfferIt() {
        #expect(AIConnection(provider: .anthropic).webSearch == .anthropicTool)
        #expect(AIConnection(provider: .openRouter).webSearch == .openRouterPlugin)
        #expect(AIConnection(provider: .openAI).webSearch == .responsesTool)
        #expect(AIConnection(provider: .xai).webSearch == .responsesTool)
        for provider in [AIProvider.google, .mistral, .deepseek, .qwen, .kimi, .zhipu, .minimax, .ollama, .vllm, .lmStudio, .local] {
            #expect(AIConnection(provider: provider).webSearch == nil, "\(provider)")
        }
        // The same API at another address may not search.
        #expect(AIConnection(provider: .openAI, baseURL: "https://llm.example.org/v1").webSearch == nil)
        // Off by default, and read as off from settings saved before it existed.
        #expect(!AIConnection(provider: .anthropic).searchesWeb)
        #expect(AIConnection(provider: .anthropic, allowsWebSearch: true).searchesWeb)
        #expect(!AIConnection(provider: .ollama, allowsWebSearch: true).searchesWeb)
        let saved = #"{"id":"6F0C5D47-3F4B-4E1E-9E36-0B9E6A1D3C11","provider":"anthropic"}"#
        #expect((try? JSONDecoder().decode(AIConnection.self, from: Data(saved.utf8)))?.allowsWebSearch == false)
    }

    @Test func requestBodiesAddTheSearchOnlyWhenAsked() throws {
        let schema: [String: Any] = ["type": "object"]
        let plain = AIRequestBody.claude(model: "m", prompt: "p", schema: schema, jpeg: Data([1, 2]), webSearch: false, effort: true, fallbacks: false)
        #expect(plain["tools"] == nil)
        #expect((plain["output_config"] as? [String: Any])?["format"] != nil)
        let searching = AIRequestBody.claude(model: "m", prompt: "p", schema: schema, jpeg: nil, webSearch: true, effort: false, fallbacks: false)
        let tool = try #require((searching["tools"] as? [[String: Any]])?.first)
        #expect(tool["type"] as? String == "web_search_20250305")
        #expect(searching["output_config"] == nil)
        let text = String(data: try JSONSerialization.data(withJSONObject: searching), encoding: .utf8) ?? ""
        #expect(!text.contains("\"image\""))
        let openRouter = AIConnection(provider: .openRouter)
        #expect(AIRequestBody.chatCompletions(connection: openRouter, model: "m", prompt: "p", jpeg: nil, format: nil)["plugins"] == nil)
        #expect(AIRequestBody.chatCompletions(connection: openRouter, model: "m", prompt: "p", jpeg: nil, format: nil, webSearch: true)["plugins"] != nil)
        #expect(AIRequestBody.chatCompletions(connection: AIConnection(provider: .mistral), model: "m", prompt: "p", jpeg: nil,
                                              format: nil, webSearch: true)["plugins"] == nil)
        // Without a photo, no image part at all.
        let content = ((AIRequestBody.chatCompletions(connection: openRouter, model: "m", prompt: "p", jpeg: nil, format: nil)["messages"]
            as? [[String: Any]])?.first?["content"] as? [[String: Any]]) ?? []
        #expect(content.count == 1 && content.first?["type"] as? String == "text")
        let responses = AIRequestBody.responses(model: "m", prompt: "p", schema: schema)
        #expect((responses["tools"] as? [[String: Any]])?.first?["type"] as? String == "web_search")
    }
}
