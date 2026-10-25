import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
#if canImport(FoundationXML)
import FoundationXML
#endif

/// Mailboxes in a WebDAV folder the user already has (a NAS, Nextcloud, pCloud…):
/// one subfolder per mailbox under the folder they chose, files as in
/// `FolderMailboxStore`.
///
///     PROPFIND Depth: 1                   list (folder by folder)
///     GET                                 read, 404 if missing
///     PUT  If-None-Match: *               create, 412 if it exists
///     DELETE                              404 is fine
///     MKCOL                               missing folders, 405 if it exists
///
/// The server's own login (Basic auth, ideally an app password) controls access,
/// so mailbox tokens are not sent. Credentials stay on each device; only the folder
/// URL travels to other devices. HTTPS, or HTTP only on the local network.
public struct WebDAVMailboxStore: MailboxStore {
    /// The folder, ending with "/".
    public let baseURL: URL
    let authorization: String
    let session: URLSession
    /// For plain HTTP (only ever local): the same, without a proxy (`NoRedirects.direct`).
    let localSession: URLSession

    public init(baseURL: URL, username: String, password: String, session: URLSession = WebDAVMailboxStore.session) {
        self.baseURL = Self.normalized(baseURL)
        authorization = "Basic " + Data("\(username):\(password)".utf8).base64EncodedString()
        self.session = session
        localSession = session === Self.session ? Self.localSession : NoRedirects.direct(session)
    }

    public func mailbox(id: String, token: String) -> Mailbox { WebDAVMailbox(store: self, id: id) }
    public func inbox(id: String, token: String) -> Mailbox { WebDAVMailbox(store: self, id: id) }

    /// Requests never follow redirects: one to another host, or to plain HTTP,
    /// would carry the password there.
    public static let session = NoRedirects.session()
    static let localSession = NoRedirects.direct(session)

    // MARK: Addresses

    /// What the user typed, as a folder URL: https:// added if no scheme is given,
    /// no query, a trailing "/". Nil if it is not an address the app accepts.
    public static func folderURL(_ text: String) -> URL? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let url = URL(string: trimmed.contains("://") ? trimmed : "https://" + trimmed),
              isAllowed(url) else { return nil }
        return normalized(url)
    }

    /// HTTPS, or HTTP only for a server on the local network (no certificate there):
    /// localhost, private and link-local addresses, and names (`.local`, `.home.arpa` and
    /// others), sent only to a local address of the name (checked before each request, `LocalName`).
    public static func isAllowed(_ url: URL) -> Bool {
        guard let host = url.host?.lowercased(), !host.isEmpty, url.user == nil, url.password == nil else { return false }
        switch url.scheme?.lowercased() {
        case "https": return true
        case "http": return LocalName.mayBeLocal(host: host)
        default: return false
        }
    }

    /// Lowercase scheme and host, no query or fragment, a path ending with "/".
    static func normalized(_ url: URL) -> URL {
        guard var components = URLComponents(url: url, resolvingAgainstBaseURL: true) else { return url }
        components.scheme = components.scheme?.lowercased()
        components.host = components.host?.lowercased()
        components.query = nil
        components.fragment = nil
        if !components.percentEncodedPath.hasSuffix("/") { components.percentEncodedPath += "/" }
        return components.url ?? url
    }

    // MARK: Checking

    /// Checks the folder and the login before turning sync on: creates the folder
    /// if needed, then writes, reads back and deletes a test file.
    public func check() async throws {
        // A name that is not on the local network says so, rather than "does not answer".
        let byAddress = try await LocalName.resolve(baseURL) != baseURL
        func refused(_ status: Int) -> Bool { byAddress && (status == 400 || status == 404) }
        // Look first: some servers refuse MKCOL (403) on a folder that exists, such as a share's root.
        let propfind = { try await self.send("PROPFIND", self.baseURL, headers: ["Depth": "0", "Content-Type": "application/xml; charset=utf-8"],
                                             body: Self.propfindBody, timeout: 15).1 }
        var found = try await propfind()
        if found == 404 {
            let (_, made) = try await send("MKCOL", baseURL, timeout: 15)
            guard [200, 201, 204, 405].contains(made) else {
                if refused(made) { throw WebDAVRefusedByAddress(status: made) }
                throw RemoteSyncError.unavailable("cannot create the folder (\(made))")
            }
            found = try await propfind()
        }
        guard found == 207 else {
            if refused(found) { throw WebDAVRefusedByAddress(status: found) }
            throw RemoteSyncError.unavailable("not a WebDAV folder (\(found))")
        }
        let name = "encave-check-" + Data((0..<4).map { _ in UInt8.random(in: .min ... .max) }).hexString
        let file = baseURL.appendingPathComponent(name)
        let content = Data("Encave checks that it can write here. This file can be deleted.\n".utf8)
        let (_, put) = try await send("PUT", file, headers: ["If-None-Match": "*", "Content-Type": "application/octet-stream"],
                                      body: content, timeout: 15)
        guard [200, 201, 204].contains(put) else { throw RemoteSyncError.unavailable("cannot write (\(put))") }
        let (read, got) = try await send("GET", file, timeout: 15)
        _ = try? await send("DELETE", file, timeout: 15)
        guard got == 200, read == content else { throw RemoteSyncError.unavailable("cannot read back (\(got))") }
    }

    // MARK: HTTP

    static let propfindBody = Data("""
        <?xml version="1.0" encoding="utf-8"?>
        <d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>
        """.utf8)

    /// Sends a request. Statuses that say the same thing on every server become errors:
    /// 401 login refused, 403 folder refused, 413 file too large, 507 full, redirects, 423, 429 and 5xx unavailable.
    func send(_ method: String, _ url: URL, headers: [String: String] = [:], body: Data? = nil,
              timeout: TimeInterval = 60) async throws -> (Data, Int) {
        let target: URL
        do {
            target = try await LocalName.resolve(url)
        } catch {
            throw RemoteSyncError.unavailable("not on the local network")
        }
        var request = URLRequest(url: target)
        request.httpMethod = method
        request.timeoutInterval = timeout
        request.setValue(authorization, forHTTPHeaderField: "Authorization")
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
        request.httpBody = body
        let (data, response): (Data, URLResponse)
        do {
            (data, response) = try await CappedRead.data(target.scheme?.lowercased() == "http" ? localSession : session,
                                                         for: request, limit: CappedRead.syncLimit)
        } catch {
            throw RemoteSyncError.unavailable(error.localizedDescription)
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        // Sent to the local address of a home name: a server that accepts only its name
        // (Nextcloud's trusted_domains) answers 400 to every request.
        if status == 400, target != url { throw WebDAVRefusedByAddress(status: status) }
        switch status {
        case 401: throw RemoteSyncError.unauthorized
        case 403: throw RemoteSyncError.forbidden
        case 413: throw RemoteSyncError.fileTooLarge
        case 507: throw RemoteSyncError.quotaExceeded
        case 300..<400: throw RemoteSyncError.unavailable("redirected (\(status)): use the final address")
        case 423, 429, 500...: throw RemoteSyncError.unavailable("WebDAV status \(status)")
        default: return (data, status)
        }
    }
}

/// A server named on the home network over plain http, reached at its local address
/// (`LocalName`), answered 400 or 404 when checked: some servers accept only their name
/// (Nextcloud's trusted_domains, a reverse proxy that tells sites apart by name).
public struct WebDAVRefusedByAddress: Error, Equatable, Sendable {
    public let status: Int
}

/// Refuses every redirect (see `WebDAVMailboxStore.session`).
final class NoRedirects: NSObject, URLSessionTaskDelegate, Sendable {
    /// The same settings as `session`, without a proxy: plain HTTP, only ever to the local
    /// network, must not go through one, which would read it in clear text. HTTPS keeps the
    /// system's proxy.
    static func direct(_ session: URLSession) -> URLSession {
        let configuration = session.configuration
        configuration.connectionProxyDictionary = [:]
        return URLSession(configuration: configuration, delegate: NoRedirects(), delegateQueue: nil)
    }

    /// A session that follows no redirect and keeps no cookies, cache or credentials.
    static func session(_ configuration: URLSessionConfiguration = .ephemeral) -> URLSession {
        configuration.httpShouldSetCookies = false
        configuration.urlCredentialStorage = nil
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: configuration, delegate: NoRedirects(), delegateQueue: nil)
    }

    #if canImport(Darwin)
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest) async -> URLRequest? {
        nil
    }
    #else
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
    #endif
}

struct WebDAVMailbox: Mailbox {
    let store: WebDAVMailboxStore
    let id: String

    /// The mailbox folder, or a folder or file in it. Only valid IDs and paths
    /// (lowercase letters, digits, "-", "_", "/") ever become part of a URL.
    private func url(_ path: String = "", folder: Bool = false) throws -> URL {
        guard RelayPath.isValidMailbox(id) else { throw RemoteSyncError.corrupted(id) }
        guard path.isEmpty || RelayPath.isValid(path) else { throw RemoteSyncError.corrupted(path) }
        let relative = id + "/" + path + (folder && !path.isEmpty ? "/" : "")
        guard let url = URL(string: relative, relativeTo: store.baseURL)?.absoluteURL else { throw RemoteSyncError.corrupted(path) }
        return url
    }

    /// Lists folder by folder (`Depth: infinity` is off on most servers), from the
    /// deepest folder named by the prefix, going only into folders the prefix can be in.
    func list(prefix: String) async throws -> [String] {
        var start = prefix.lastIndex(of: "/").map { String(prefix[..<$0]) } ?? ""
        if !start.isEmpty, !RelayPath.isValid(start) { start = "" }
        var files = Set<String>()
        var folders = [start]
        // Each folder once, even if a server lists a parent among the children.
        var visited: Set<String> = [start]
        while let folder = folders.popLast() {
            // A mailbox holds a few folders and at most a few thousand files.
            guard visited.count <= 1_000, files.count <= 10_000 else { throw RemoteSyncError.unavailable("bad list") }
            guard let entries = try await entries(in: folder) else { continue }
            for entry in entries where entry.path != folder && RelayPath.isValid(entry.path) {
                if entry.isFolder {
                    let inside = entry.path + "/"
                    if inside.hasPrefix(prefix) || prefix.hasPrefix(inside), visited.insert(entry.path).inserted {
                        folders.append(entry.path)
                    }
                } else if entry.path.hasPrefix(prefix) {
                    files.insert(entry.path)
                }
            }
        }
        return files.sorted()
    }

    /// The folder's own entry and its children, as paths in the mailbox. Nil if it does not exist.
    private func entries(in folder: String) async throws -> [WebDAVEntry]? {
        let (data, status) = try await store.send("PROPFIND", url(folder, folder: true),
                                                  headers: ["Depth": "1", "Content-Type": "application/xml; charset=utf-8"],
                                                  body: WebDAVMailboxStore.propfindBody)
        switch status {
        case 207: break
        case 404, 409: return nil
        default: throw RemoteSyncError.unavailable("WebDAV status \(status)")
        }
        guard let responses = WebDAVMultistatus.parse(data) else { throw RemoteSyncError.unavailable("bad list response") }
        let requested = try url(folder, folder: true), mailbox = try url(folder: true)
        return responses.compactMap { response in
            WebDAVMultistatus.relativePath(of: response.href, requested: requested, mailbox: mailbox, id: id)
                .map { WebDAVEntry(path: $0, isFolder: response.isCollection) }
        }
    }

    func get(_ path: String) async throws -> Data? {
        let (data, status) = try await store.send("GET", url(path))
        switch status {
        case 200: return data
        case 404: return nil
        default: throw RemoteSyncError.unavailable("WebDAV status \(status)")
        }
    }

    func create(_ path: String, data: Data) async throws -> Bool {
        // Never more than a device reads back (`CappedRead`).
        guard data.count <= CappedRead.syncLimit else { throw RemoteSyncError.fileTooLarge }
        let target = try url(path)
        for attempt in 0..<2 {
            let (_, status) = try await store.send("PUT", target, headers: ["If-None-Match": "*",
                                                                             "Content-Type": "application/octet-stream"],
                                                   body: data)
            switch status {
            case 200, 201, 204: return true
            case 412: return false
            // The folder is missing (409 by the standard, 404 on some servers): make it, then once more.
            case 404 where attempt == 0, 409 where attempt == 0: try await makeFolders(for: path)
            default: throw RemoteSyncError.unavailable("WebDAV status \(status)")
            }
        }
        throw RemoteSyncError.unavailable("cannot create \(path)")
    }

    /// Makes the folders above `path`, from the deepest one that is missing.
    private func makeFolders(for path: String) async throws {
        var parts = path.split(separator: "/").dropLast().map(String.init)
        var missing: [String] = []
        // Up from the file's folder until a folder can be made (409: its parent is missing too).
        while true {
            let folder = parts.joined(separator: "/")
            let target = folder.isEmpty ? try url(folder: true) : try url(folder, folder: true)
            let (_, status) = try await store.send("MKCOL", target)
            if [200, 201, 204, 405].contains(status) { break }
            guard status == 409 || status == 404 else { throw RemoteSyncError.unavailable("WebDAV status \(status)") }
            if parts.isEmpty {
                // The mailbox's folder needs the chosen folder.
                let (_, base) = try await store.send("MKCOL", store.baseURL)
                guard [200, 201, 204, 405].contains(base) else { throw RemoteSyncError.unavailable("WebDAV status \(base)") }
                missing.append("")
                break
            }
            missing.append(folder)
            parts.removeLast()
        }
        for folder in missing.reversed() {
            let target = folder.isEmpty ? try url(folder: true) : try url(folder, folder: true)
            let (_, status) = try await store.send("MKCOL", target)
            guard [200, 201, 204, 405].contains(status) else { throw RemoteSyncError.unavailable("WebDAV status \(status)") }
        }
    }

    func delete(_ path: String) async throws {
        let (_, status) = try await store.send("DELETE", url(path))
        guard [200, 204, 404].contains(status) else { throw RemoteSyncError.unavailable("WebDAV status \(status)") }
    }
}

struct WebDAVEntry: Equatable {
    var path: String
    var isFolder: Bool
}

/// Reads a `207 Multi-Status` answer to PROPFIND: each response's href and whether
/// it is a collection. Namespace prefixes vary by server (`d:`, `D:`, none).
enum WebDAVMultistatus {
    struct Response: Equatable {
        var href: String
        var isCollection: Bool
    }

    static func parse(_ data: Data) -> [Response]? {
        // A multistatus never has a DTD; refusing one rules out entity tricks.
        if data.range(of: Data("<!DOCTYPE".utf8)) != nil || data.range(of: Data("<!ENTITY".utf8)) != nil { return nil }
        let parser = XMLParser(data: data)
        let reader = Reader()
        parser.shouldProcessNamespaces = true
        parser.shouldResolveExternalEntities = false
        parser.delegate = reader
        guard parser.parse(), reader.sawMultistatus else { return nil }
        return reader.responses
    }

    /// The path of `href` (from the answer to a request for `requested`) inside the
    /// mailbox folder `mailbox`, without a trailing "/" ("" for the folder itself).
    /// Hrefs may be absolute URLs, absolute paths or relative to the request, and are
    /// percent-encoded, sometimes differently from the request. Nil if outside it.
    static func relativePath(of href: String, requested: URL, mailbox: URL, id: String) -> String? {
        let trimmed = href.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let resolved = URL(string: trimmed, relativeTo: requested)?.absoluteURL,
              let path = URLComponents(url: resolved, resolvingAgainstBaseURL: false)?.percentEncodedPath.removingPercentEncoding,
              let folder = URLComponents(url: mailbox, resolvingAgainstBaseURL: false)?.percentEncodedPath.removingPercentEncoding
        else { return nil }
        var inside: Substring
        if path.hasPrefix(folder) {
            inside = path.dropFirst(folder.count)
        } else if let range = path.range(of: "/\(id)/", options: .backwards) {
            // A server behind a proxy may answer with another base path: the mailbox ID is unique.
            inside = path[range.upperBound...]
        } else if path + "/" == folder || path.hasSuffix("/\(id)") {
            return ""
        } else {
            return nil
        }
        while inside.hasSuffix("/") { inside = inside.dropLast() }
        return String(inside)
    }

    private final class Reader: NSObject, XMLParserDelegate {
        var responses: [Response] = []
        var sawMultistatus = false
        private var href: String?
        private var text = ""
        private var inResponse = false
        private var inHref = false
        private var inResourceType = false
        private var isCollection = false

        private func isDAV(_ namespace: String?) -> Bool { namespace == "DAV:" || namespace == nil || namespace == "" }
        /// The name without a prefix, whichever form the parser gives.
        private static func localName(_ name: String) -> Substring { name.split(separator: ":").last ?? Substring(name) }

        func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
                    qualifiedName: String?, attributes: [String: String] = [:]) {
            guard isDAV(namespaceURI) else { return }
            switch Self.localName(elementName) {
            case "multistatus": sawMultistatus = true
            case "response": inResponse = true; href = nil; isCollection = false
            case "href" where inResponse && href == nil: inHref = true; text = ""
            case "resourcetype" where inResponse: inResourceType = true
            case "collection" where inResourceType: isCollection = true
            default: break
            }
        }

        func parser(_ parser: XMLParser, foundCharacters string: String) {
            if inHref { text += string }
        }

        func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName: String?) {
            guard isDAV(namespaceURI) else { return }
            switch Self.localName(elementName) {
            case "href" where inHref: inHref = false; href = text
            case "resourcetype": inResourceType = false
            case "response" where inResponse:
                inResponse = false
                if let href { responses.append(Response(href: href, isCollection: isCollection)) }
            default: break
            }
        }
    }
}

/// Addresses on this device or the local network, where a server has no certificate
/// and plain HTTP is accepted.
public enum LocalAddress {
    /// True for a local address, localhost, or a `.local` or `.home.arpa` name, without
    /// asking DNS. Never enough to send plain HTTP: a name can point anywhere, so requests go
    /// through `LocalName.resolve`, which checks the addresses.
    public static func isLocal(host: String) -> Bool {
        var host = host.lowercased()
        if host.hasPrefix("["), host.hasSuffix("]") { host = String(host.dropFirst().dropLast()) }
        if host == "localhost" || host.hasSuffix(".local") || host.hasSuffix(".home.arpa") { return true }
        // Decimal only, without leading zeros: "010" is 8 for some resolvers, a public address.
        let octets = host.split(separator: ".", omittingEmptySubsequences: false).map { part -> UInt8? in
            guard (1...3).contains(part.count), part.allSatisfy(\.isASCII), part.allSatisfy(\.isNumber),
                  part == "0" || part.first != "0" else { return nil }
            return UInt8(part)
        }
        if octets.count == 4, !octets.contains(nil) {
            let a = octets[0]!, b = octets[1]!
            return a == 10 || a == 127 || (a == 172 && (16...31).contains(b)) || (a == 192 && b == 168) || (a == 169 && b == 254)
        }
        guard host.contains(":"), host.allSatisfy({ $0.isHexDigit || $0 == ":" || $0 == "." || $0 == "%" }) else { return false }
        // IPv6: loopback, unique local (fc00::/7), link-local (fe80::/10).
        if host == "::1" { return true }
        let first = host.split(separator: ":", omittingEmptySubsequences: false).first.map(String.init) ?? ""
        guard first.count == 4, let value = UInt16(first, radix: 16) else { return false }
        return value & 0xFE00 == 0xFC00 || value & 0xFFC0 == 0xFE80
    }
}
