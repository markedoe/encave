import Foundation
import Testing
@testable import CaveKit

/// Plain http to a server named on the home network. The Kotlin core (LocalNameTest.kt)
/// checks the same vectors, `fixtures/local-name-vectors.json`, so the two cannot drift.
struct LocalNameTests {
    struct Vectors: Decodable {
        struct Case: Decodable {
            var url: String
            var addresses: [String]
            var expect: String?
            var error: String?
        }
        struct Hosts: Decodable {
            var accepted: [String]
            var refused: [String]
        }
        struct Choice: Decodable {
            var addresses: [String]
            var expect: String?
            var error: String?
        }
        var resolve: [Case]
        var choose: [Choice]
        var mayBeLocal: Hosts
    }

    static func vectors() throws -> Vectors {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent() // CaveKitTests
            .deletingLastPathComponent().deletingLastPathComponent() // CaveKit
            .deletingLastPathComponent().deletingLastPathComponent() // apple
            .deletingLastPathComponent().appendingPathComponent("fixtures/local-name-vectors.json")
        return try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: file))
    }

    @Test func sharedVectors() async throws {
        for item in try Self.vectors().resolve {
            let addresses = item.addresses
            let url = try #require(URL(string: item.url))
            do {
                let resolved = try await LocalName.resolve(url) { _ in addresses }
                #expect(item.error == nil, "\(item.url) should fail")
                #expect(resolved.absoluteString == item.expect, "\(item.url)")
            } catch let error as LocalNetworkError {
                let name = error == .notLocal ? "notLocal" : "notFound"
                #expect(name == item.error, "\(item.url): \(error)")
            }
        }
    }

    @Test func sharedChoices() throws {
        for item in try Self.vectors().choose {
            do {
                let address = try LocalName.choose(item.addresses)
                #expect(item.error == nil && address == item.expect, "\(item.addresses)")
            } catch let error as LocalNetworkError {
                let name = error == .notLocal ? "notLocal" : "notFound"
                #expect(name == item.error, "\(item.addresses): \(error)")
            }
        }
    }

    @Test func mayBeLocal() throws {
        let hosts = try Self.vectors().mayBeLocal
        for host in hosts.accepted { #expect(LocalName.mayBeLocal(host: host), "\(host)") }
        for host in hosts.refused { #expect(!LocalName.mayBeLocal(host: host), "\(host)") }
    }

    /// The resolver is not asked for https, an address or localhost; .local and .home.arpa
    /// names are resolved like the others.
    @Test func resolvesOnlyNamesOverHTTP() async throws {
        for text in ["https://searxng.home/search", "http://localhost:11434/", "http://[fd12:3456::20]:8080/"] {
            let url = try #require(URL(string: text))
            let resolved = try await LocalName.resolve(url) { _ in
                Issue.record("resolved \(text)")
                return []
            }
            #expect(resolved == url)
        }
    }

    /// A link-local address with an interface is skipped; one without comes after the others.
    @Test func linkLocalLast() throws {
        #expect(try LocalName.choose(["fe80::1%en0", "fd12:3456::20"]) == "fd12:3456::20")
        #expect(try LocalName.choose(["fe80::1", "fd12:3456::20"]) == "fd12:3456::20")
        #expect(throws: LocalNetworkError.notFound) { try LocalName.choose(["fe80::1%en0"]) }
        #expect(throws: LocalNetworkError.notLocal) { try LocalName.choose(["2001:db8::7"]) }
    }

    /// The addresses that accept http for a name: settings fields accept such names.
    @Test func entryAcceptsHomeNames() throws {
        #expect(WebDAVMailboxStore.folderURL("http://nas.lan:5005/encave") != nil)
        #expect(WebDAVMailboxStore.folderURL("http://203.0.113.7/encave") == nil)
        #expect(RelayMailboxStore.isAllowed(URL(string: "http://relay.home:8787")!))
        #expect(!RelayMailboxStore.isAllowed(URL(string: "http://203.0.113.7:8787")!))
        #expect(throws: Never.self) { try SearXNGSearch(serverURL: "http://searxng.home:8888") }
        #expect(throws: WebSearchError.invalidServerURL) { try SearXNGSearch(serverURL: "http://203.0.113.7:8888") }
    }

    /// The system resolver finds this machine's own name for "localhost"-like names, and
    /// reports a name that cannot exist as not found.
    @Test func systemResolverNotFound() async {
        await #expect(throws: LocalNetworkError.notFound) {
            try await LocalName.system("no-such-host.invalid")
        }
    }
}
