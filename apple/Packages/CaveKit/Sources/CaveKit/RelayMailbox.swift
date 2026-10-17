import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Mailboxes on an Encave relay (see `relay/`), a small HTTP service anyone can
/// host. It stores the encrypted files and nothing else:
///
///     GET    /v1/m/{mailbox}?prefix=…&after=…   → {"files": [...], "next": "…"?}
///     GET    /v1/m/{mailbox}/{path}             → 200 bytes, or 404
///     PUT    /v1/m/{mailbox}/{path}             If-None-Match: *  → 201, or 412 if it exists
///     DELETE /v1/m/{mailbox}/{path}             → 204
///
/// Requests carry `Authorization: Bearer <token>`. A mailbox ID is derived from its
/// token (`RelayAuth.mailboxID`), so the relay checks the token without storing
/// anything. Anyone may create a
/// `grant/…` file in an inbox (that is how a device receives a key), only the
/// inbox's owner may read or delete.
public struct RelayMailboxStore: MailboxStore {
    public let baseURL: URL
    let session: URLSession

    public init(baseURL: URL, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
    }

    public func mailbox(id: String, token: String) -> Mailbox {
        RelayMailbox(base: baseURL, id: id, token: token, session: session)
    }

    public func inbox(id: String, token: String) -> Mailbox {
        RelayMailbox(base: baseURL, id: id, token: token, session: session)
    }

    /// Checks that a URL points to an Encave relay (`GET /v1/health`).
    public func check() async throws {
        var request = URLRequest(url: baseURL.appendingPathComponent("v1/health"))
        request.timeoutInterval = 15
        let (data, response) = try await session.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200,
              let body = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              body["service"] as? String == "encave-relay" else {
            throw RemoteSyncError.unavailable("not an Encave relay")
        }
    }
}

struct RelayMailbox: Mailbox {
    let base: URL
    let id: String
    let token: String
    let session: URLSession

    private func url(_ path: String?) throws -> URL {
        var url = base.appendingPathComponent("v1/m/\(id)")
        if let path {
            guard RelayPath.isValid(path) else { throw RemoteSyncError.corrupted(path) }
            url.appendPathComponent(path)
        }
        return url
    }

    private func request(_ url: URL, method: String) -> URLRequest {
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 60
        if !token.isEmpty { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        return request
    }

    private func send(_ request: URLRequest, body: Data? = nil) async throws -> (Data, Int) {
        let (data, response): (Data, URLResponse)
        do {
            if let body {
                (data, response) = try await session.upload(for: request, from: body)
            } else {
                (data, response) = try await session.data(for: request)
            }
        } catch {
            throw RemoteSyncError.unavailable(error.localizedDescription)
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        switch status {
        case 401, 403: throw RemoteSyncError.unauthorized
        case 413: throw RemoteSyncError.fileTooLarge
        case 507: throw RemoteSyncError.quotaExceeded
        case 429, 500...: throw RemoteSyncError.unavailable("relay status \(status)")
        default: return (data, status)
        }
    }

    func list(prefix: String) async throws -> [String] {
        var files: [String] = []
        var after: String?
        repeat {
            var components = URLComponents(url: try url(nil), resolvingAgainstBaseURL: false)!
            components.queryItems = [URLQueryItem(name: "prefix", value: prefix)]
                + (after.map { [URLQueryItem(name: "after", value: $0)] } ?? [])
            let (data, status) = try await send(request(components.url!, method: "GET"))
            if status == 404 { return [] }
            guard status == 200, let page = try? JSONDecoder().decode(Page.self, from: data) else {
                throw RemoteSyncError.unavailable("bad list response")
            }
            files += page.files.filter { $0.hasPrefix(prefix) && RelayPath.isValid($0) }
            // A page must move forward, and a mailbox holds at most a few thousand
            // files: a relay that says otherwise is not followed for ever.
            guard let next = page.next else { break }
            guard next > (after ?? ""), files.count <= 10_000 else {
                throw RemoteSyncError.unavailable("bad list paging")
            }
            after = next
        } while true
        return files
    }

    struct Page: Decodable {
        var files: [String]
        var next: String?
    }

    func get(_ path: String) async throws -> Data? {
        let (data, status) = try await send(request(try url(path), method: "GET"))
        switch status {
        case 200: return data
        case 404: return nil
        default: throw RemoteSyncError.unavailable("relay status \(status)")
        }
    }

    func create(_ path: String, data: Data) async throws -> Bool {
        var request = request(try url(path), method: "PUT")
        request.setValue("*", forHTTPHeaderField: "If-None-Match")
        request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        let (_, status) = try await send(request, body: data)
        switch status {
        case 200, 201: return true
        case 409, 412: return false
        default: throw RemoteSyncError.unavailable("relay status \(status)")
        }
    }

    func delete(_ path: String) async throws {
        let (_, status) = try await send(request(try url(path), method: "DELETE"))
        guard [200, 204, 404].contains(status) else { throw RemoteSyncError.unavailable("relay status \(status)") }
    }
}
