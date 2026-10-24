import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import Testing
@testable import CaveKit

/// A relay that answers every request with a redirect to another host, which
/// answers 200. Records the host of each request it receives.
final class RedirectingRelayProtocol: URLProtocol {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var hosts: [String] = []
    static func requests(containing id: String) -> [String] { lock.withLock { hosts.filter { $0.contains(id) } } }

    override class func canInit(with request: URLRequest) -> Bool {
        request.url?.host?.hasSuffix(".relay.test") == true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url, let host = url.host else { return }
        Self.lock.withLock { Self.hosts.append(host) }
        let response: HTTPURLResponse
        if host.hasPrefix("elsewhere-") {
            response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: nil)!
        } else {
            let target = "https://elsewhere-" + host + url.path
            response = HTTPURLResponse(url: url, statusCode: 302, httpVersion: "HTTP/1.1",
                                       headerFields: ["Location": target])!
        }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data("{\"files\":[]}".utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

@Suite struct RelayMailboxTests {
    @Test func aRedirectIsNotFollowed() async throws {
        let id = UUID().uuidString.prefix(8).lowercased()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [RedirectingRelayProtocol.self]
        let store = RelayMailboxStore(baseURL: URL(string: "https://relay-\(id).relay.test")!,
                                      session: NoRedirects.session(configuration))
        let mailbox = store.mailbox(id: "c0123456789abcdef0123456789abcdef", token: "secret")
        await #expect(throws: RemoteSyncError.unavailable("redirected (302): use the final address")) {
            _ = try await mailbox.get("snap/0a-b/0000000000000001-ab12cd34")
        }
        await #expect(throws: RemoteSyncError.unavailable("redirected (302): use the final address")) {
            _ = try await mailbox.list(prefix: "snap/")
        }
        // Only the relay was asked: the token never reached the other host.
        #expect(RedirectingRelayProtocol.requests(containing: id) == ["relay-\(id).relay.test", "relay-\(id).relay.test"])
    }

    @Test func theDefaultSessionKeepsNothingAndRefusesRedirects() {
        let store = RelayMailboxStore(baseURL: URL(string: "https://relay.example.com")!)
        #expect(store.session === RelayMailboxStore.session)
        #expect(store.session.delegate is NoRedirects)
        #expect(!store.session.configuration.httpShouldSetCookies)
        #expect(store.session.configuration.requestCachePolicy == .reloadIgnoringLocalCacheData)
    }
}
