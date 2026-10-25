import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import Testing
@testable import CaveKit

/// Answers every request to big.example with as many bytes as the last path component says,
/// with a Content-Length only when the host is "sized.big.example".
final class OversizedProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host?.hasSuffix("big.example") == true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url, let count = Int(url.lastPathComponent) else { return }
        let headers = url.host == "sized.big.example" ? ["Content-Length": String(count)] : [:]
        let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: headers)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(count: count))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}

    static let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [OversizedProtocol.self]
        return URLSession(configuration: configuration)
    }()
}

struct CappedReadTests {
    @Test func bodiesOverTheLimitAreRefused() async throws {
        for host in ["big.example", "sized.big.example"] {
            let fits = URLRequest(url: URL(string: "https://\(host)/1000")!)
            #expect(try await CappedRead.data(OversizedProtocol.session, for: fits, limit: 1000).0.count == 1000)
            let over = URLRequest(url: URL(string: "https://\(host)/1001")!)
            await #expect(throws: ResponseTooLarge(limit: 1000)) {
                _ = try await CappedRead.data(OversizedProtocol.session, for: over, limit: 1000)
            }
        }
    }

    /// A relay that sends one byte more than a sync file may hold is not read.
    @Test func relayFilesAreCapped() async throws {
        let store = RelayMailboxStore(baseURL: URL(string: "https://big.example/r")!, session: OversizedProtocol.session)
        let mailbox = store.mailbox(id: "c" + String(repeating: "d", count: 32), token: "t")
        await #expect(throws: RemoteSyncError.self) { _ = try await mailbox.get("snap/\(CappedRead.syncLimit + 1)") }
    }

    /// No file is written that a device would refuse to read back.
    @Test func writesOverTheLimitAreRefused() async throws {
        let big = Data(count: CappedRead.syncLimit + 1)
        let relay = RelayMailboxStore(baseURL: URL(string: "https://big.example/r")!, session: OversizedProtocol.session)
            .mailbox(id: "c" + String(repeating: "d", count: 32), token: "t")
        await #expect(throws: RemoteSyncError.fileTooLarge) { _ = try await relay.create("snap/x", data: big) }
        let server = FakeWebDAV()
        let webDAV = server.store().mailbox(id: "c" + String(repeating: "d", count: 32), token: "")
        await #expect(throws: RemoteSyncError.fileTooLarge) { _ = try await webDAV.create("snap/x", data: big) }
        #expect(server.requests.isEmpty)
    }
}
