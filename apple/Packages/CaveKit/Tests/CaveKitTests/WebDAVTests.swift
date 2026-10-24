import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import Testing
@testable import CaveKit

/// A WebDAV server in memory, reached through `FakeWebDAVProtocol`. Behaves like
/// Nextcloud: PUT and PROPFIND need the parent folder (409), MKCOL answers 405 for
/// an existing folder, If-None-Match: * gives 412.
final class FakeWebDAV: @unchecked Sendable {
    enum HrefStyle { case absolutePath, absoluteURL, relative }

    let host = "nas-\(UUID().uuidString.prefix(8).lowercased()).local"
    /// Decoded; a space and an "@" exercise percent-encoding.
    let basePath = "/remote.php/dav/files/jean dupont@example.com/Encave/"
    var hrefStyle = HrefStyle.absolutePath
    var honoursIfNoneMatch = true
    /// Answers every request with this status.
    var forcedStatus: Int?
    let username = "jean", password = "app-password"

    private let lock = NSLock()
    private var folders: Set<String> = []
    private(set) var files: [String: Data] = [:]
    private(set) var log: [String] = []

    private static let lock = NSLock()
    nonisolated(unsafe) private static var servers: [String: FakeWebDAV] = [:]
    static func server(for host: String) -> FakeWebDAV? { lock.withLock { servers[host] } }

    static let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [FakeWebDAVProtocol.self]
        return URLSession(configuration: configuration)
    }()

    init() {
        // The folders above the chosen one exist; the chosen one does not yet.
        var path = "/"
        folders.insert(path)
        for part in basePath.split(separator: "/").dropLast() {
            path += part + "/"
            folders.insert(path)
        }
        Self.lock.withLock { Self.servers[host] = self }
    }

    var baseURL: URL {
        URL(string: "http://\(host)" + basePath.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(["@"]))!)!
    }

    func store(password: String? = nil) -> WebDAVMailboxStore {
        WebDAVMailboxStore(baseURL: baseURL, username: username, password: password ?? self.password, session: Self.session)
    }

    var allFiles: [String: Data] { lock.withLock { files } }
    var requests: [String] { lock.withLock { log } }

    func handle(_ request: URLRequest, body: Data) -> (Int, Data) {
        lock.withLock {
            let method = request.httpMethod ?? "GET"
            let url = request.url!
            var path = URLComponents(url: url, resolvingAgainstBaseURL: false)!.percentEncodedPath.removingPercentEncoding!
            log.append("\(method) \(path)")
            let expected = "Basic " + Data("\(username):\(password)".utf8).base64EncodedString()
            guard request.value(forHTTPHeaderField: "Authorization") == expected else { return (401, Data()) }
            if let forcedStatus { return (forcedStatus, Data()) }
            let isFolderPath = path.hasSuffix("/")
            if isFolderPath, path.count > 1 { path.removeLast() }
            let folder = path == "/" ? "/" : path + "/"
            let parent = String(path[...path.lastIndex(of: "/")!])
            switch method {
            case "MKCOL":
                if folders.contains(folder) || files[path] != nil { return (405, Data()) }
                guard folders.contains(parent) else { return (409, Data()) }
                folders.insert(folder)
                return (201, Data())
            case "PUT":
                guard folders.contains(parent), !folders.contains(folder) else { return (409, Data()) }
                if files[path] != nil, honoursIfNoneMatch, request.value(forHTTPHeaderField: "If-None-Match") == "*" {
                    return (412, Data())
                }
                files[path] = body
                return (201, Data())
            case "GET":
                return files[path].map { (200, $0) } ?? (404, Data())
            case "DELETE":
                if files.removeValue(forKey: path) != nil { return (204, Data()) }
                guard folders.contains(folder) else { return (404, Data()) }
                folders = folders.filter { !$0.hasPrefix(folder) }
                files = files.filter { !$0.key.hasPrefix(folder) }
                return (204, Data())
            case "PROPFIND":
                var entries: [(String, Bool)]
                if folders.contains(folder) {
                    entries = [(folder, true)]
                    if request.value(forHTTPHeaderField: "Depth") != "0" {
                        let children = { (name: String) in name.hasPrefix(folder) && name != folder
                            && !name.dropFirst(folder.count).dropLast(name.hasSuffix("/") ? 1 : 0).contains("/") }
                        entries += folders.filter(children).sorted().map { ($0, true) }
                        entries += files.keys.filter(children).sorted().map { ($0, false) }
                    }
                } else if files[path] != nil {
                    entries = [(path, false)]
                } else {
                    return (404, Data())
                }
                return (207, multistatus(entries, requested: folders.contains(folder) ? folder : path))
            default:
                return (405, Data())
            }
        }
    }

    private func multistatus(_ entries: [(String, Bool)], requested: String) -> Data {
        let encode = { (path: String) in path.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed)! }
        let responses = entries.map { path, isFolder -> String in
            let href: String
            switch hrefStyle {
            case .absolutePath: href = encode(path)
            case .absoluteURL: href = "http://\(host)" + encode(path).replacingOccurrences(of: "@", with: "%40")
            case .relative: href = path == requested ? "./" : encode(String(path.dropFirst(requested.count)))
            }
            let type = isFolder ? "<d:resourcetype><d:collection/></d:resourcetype>" : "<d:resourcetype/>"
            return "<d:response><d:href>\(href)</d:href><d:propstat><d:prop>\(type)<d:getetag>\"1\"</d:getetag></d:prop>"
                + "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
        }
        return Data(("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\">"
            + responses.joined() + "</d:multistatus>").utf8)
    }
}

final class FakeWebDAVProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool {
        request.url?.host.flatMap(FakeWebDAV.server(for:)) != nil
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url, let server = url.host.flatMap(FakeWebDAV.server(for:)) else { return }
        var body = request.httpBody ?? Data()
        if body.isEmpty, let stream = request.httpBodyStream {
            stream.open()
            var buffer = [UInt8](repeating: 0, count: 65_536)
            while stream.hasBytesAvailable {
                let count = stream.read(&buffer, maxLength: buffer.count)
                if count <= 0 { break }
                body.append(buffer, count: count)
            }
            stream.close()
        }
        let (status, data) = server.handle(request, body: body)
        let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1",
                                       headerFields: ["Content-Type": status == 207 ? "application/xml" : "application/octet-stream"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

@Suite struct WebDAVTests {
    // MARK: Multistatus

    @Test func hrefsAreReadWhateverTheirForm() throws {
        let mailbox = URL(string: "https://cloud.example.com/remote.php/dav/files/jean%40example.com/Encave/c0123456789abcdef0123456789abcdef/")!
        let id = "c0123456789abcdef0123456789abcdef"
        let xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:href>/remote.php/dav/files/jean@example.com/Encave/\(id)/</D:href>
                <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>
              <D:response><D:href>https://cloud.example.com/remote.php/dav/files/jean%40example.com/Encave/\(id)/snap/</D:href>
                <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>
              <D:response><D:href>photo%2Dlike</D:href>
                <D:propstat><D:prop><D:resourcetype/></D:prop></D:propstat></D:response>
              <D:response><D:href>/proxied/base/\(id)/grant/x-00000001-y</D:href>
                <D:propstat><D:prop><D:resourcetype/></D:prop></D:propstat></D:response>
              <D:response><D:href>/elsewhere/file</D:href>
                <D:propstat><D:prop><D:resourcetype/></D:prop></D:propstat></D:response>
            </D:multistatus>
            """
        let responses = try #require(WebDAVMultistatus.parse(Data(xml.utf8)))
        #expect(responses.map(\.isCollection) == [true, true, false, false, false])
        let paths = responses.map { WebDAVMultistatus.relativePath(of: $0.href, requested: mailbox, mailbox: mailbox, id: id) }
        #expect(paths == ["", "snap", "photo-like", "grant/x-00000001-y", nil])
    }

    @Test func defaultNamespaceAndOtherPrefixesParse() throws {
        let xml = """
            <multistatus xmlns="DAV:"><response><href>/a/</href><propstat><prop>
            <resourcetype><collection/></resourcetype></prop></propstat></response>
            <response><href>/a/b</href><propstat><prop><resourcetype/></prop></propstat></response></multistatus>
            """
        #expect(WebDAVMultistatus.parse(Data(xml.utf8)) == [.init(href: "/a/", isCollection: true),
                                                            .init(href: "/a/b", isCollection: false)])
        // A collection of another namespace is not a folder.
        let other = """
            <d:multistatus xmlns:d="DAV:" xmlns:x="urn:x"><d:response><d:href>/a</d:href><d:propstat><d:prop>
            <d:resourcetype><x:collection/></d:resourcetype></d:prop></d:propstat></d:response></d:multistatus>
            """
        #expect(WebDAVMultistatus.parse(Data(other.utf8)) == [.init(href: "/a", isCollection: false)])
    }

    @Test func badListAnswersAreRefused() {
        #expect(WebDAVMultistatus.parse(Data("<html><body>Login</body></html>".utf8)) == nil)
        #expect(WebDAVMultistatus.parse(Data("not xml".utf8)) == nil)
        let entity = """
            <?xml version="1.0"?><!DOCTYPE d [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <d:multistatus xmlns:d="DAV:"><d:response><d:href>&x;</d:href></d:response></d:multistatus>
            """
        #expect(WebDAVMultistatus.parse(Data(entity.utf8)) == nil)
    }

    // MARK: Addresses

    @Test func addressesMustBeHttpsOrOnTheLocalNetwork() {
        #expect(WebDAVMailboxStore.folderURL("nas.example.com/encave")?.absoluteString == "https://nas.example.com/encave/")
        #expect(WebDAVMailboxStore.folderURL(" https://webdav.example.com/Encave?x=1 ")?.absoluteString
                == "https://webdav.example.com/Encave/")
        // Example private addresses, not real hosts.
        let privateIPs = ["http://192.168.1.20/dav", "http://10.0.0.2/", "http://172.16.4.1/"] // public-safety: allow
        for local in ["http://nas.local:5005/encave", "http://localhost:8080", "http://[fd12::1]/dav", "http://[fe80::1]",
                      "http://box.home.arpa", "http://nas.lan:5005/encave"] + privateIPs {
            #expect(WebDAVMailboxStore.folderURL(local) != nil, "\(local)")
        }
        // A name over http is checked before each request (LocalNameTests); an address now.
        for refused in ["http://8.8.8.8", "http://172.32.0.1", "http://[2001:db8::1]",
                        "ftp://nas.local", "https://jean:secret@nas.example.com/", "", "https://"] {
            #expect(WebDAVMailboxStore.folderURL(refused) == nil, "\(refused)")
        }
    }

    // MARK: Operations

    @Test func checkMakesTheFolderAndLeavesNothing() async throws {
        let server = FakeWebDAV()
        try await server.store().check()
        #expect(server.allFiles.isEmpty)
        #expect(server.requests.prefix(2) == ["PROPFIND \(server.basePath)", "MKCOL \(server.basePath)"])
        // Twice is fine: the folder exists.
        try await server.store().check()
    }

    @Test func aWrongPasswordIsRefused() async throws {
        let server = FakeWebDAV()
        await #expect(throws: RemoteSyncError.unauthorized) { try await server.store(password: "wrong").check() }
    }

    @Test func filesAreCreatedOnceWithTheirFolders() async throws {
        let server = FakeWebDAV()
        let mailbox = server.store().mailbox(id: "c" + String(repeating: "a", count: 32), token: "ignored")
        #expect(try await mailbox.list(prefix: "snap/").isEmpty)
        #expect(try await mailbox.create("snap/dev/0001-ab", data: Data([1])))
        #expect(try await !mailbox.create("snap/dev/0001-ab", data: Data([2])), "412: never overwritten")
        #expect(try await mailbox.get("snap/dev/0001-ab") == Data([1]))
        #expect(try await mailbox.get("snap/dev/missing") == nil)
        #expect(try await mailbox.create("photo/sha256-12", data: Data([3])))
        #expect(try await mailbox.list(prefix: "snap/") == ["snap/dev/0001-ab"])
        #expect(try await mailbox.list(prefix: "") == ["photo/sha256-12", "snap/dev/0001-ab"])
        #expect(try await mailbox.list(prefix: "snap/dev/0") == ["snap/dev/0001-ab"])
        try await mailbox.delete("snap/dev/0001-ab")
        try await mailbox.delete("snap/dev/0001-ab")
        #expect(try await mailbox.list(prefix: "snap/").isEmpty)
        // No mailbox token went to the server: its own login controls access.
        #expect(!server.requests.contains { $0.contains("ignored") })
    }

    @Test func aServerIgnoringIfNoneMatchOverwrites() async throws {
        // The protocol tolerates it: every name is written by one device, with the same content.
        let server = FakeWebDAV()
        server.honoursIfNoneMatch = false
        let mailbox = server.store().mailbox(id: "i" + String(repeating: "b", count: 32), token: "")
        #expect(try await mailbox.create("grant/a", data: Data([1])))
        #expect(try await mailbox.create("grant/a", data: Data([1])))
    }

    @Test func statusesBecomeErrors() async throws {
        let server = FakeWebDAV()
        let mailbox = server.store().mailbox(id: "c" + String(repeating: "c", count: 32), token: "")
        let cases: [(Int, RemoteSyncError)] = [(401, .unauthorized), (403, .forbidden), (507, .quotaExceeded),
                                               (413, .fileTooLarge), (500, .unavailable("WebDAV status 500")),
                                               (423, .unavailable("WebDAV status 423"))]
        for (status, error) in cases {
            server.forcedStatus = status
            await #expect(throws: error) { _ = try await mailbox.create("photo/x", data: Data([1])) }
        }
        server.forcedStatus = 302
        await #expect(throws: RemoteSyncError.self) { _ = try await mailbox.get("photo/x") }
        server.forcedStatus = 400
        await #expect(throws: RemoteSyncError.unavailable("WebDAV status 400")) { _ = try await mailbox.list(prefix: "") }
    }

    @Test func invalidNamesNeverReachTheServer() async throws {
        let server = FakeWebDAV()
        let store = server.store()
        await #expect(throws: RemoteSyncError.self) { _ = try await store.mailbox(id: "../x", token: "").list(prefix: "") }
        let mailbox = store.mailbox(id: "c" + String(repeating: "d", count: 32), token: "")
        await #expect(throws: RemoteSyncError.self) { _ = try await mailbox.create("../escape", data: Data()) }
        await #expect(throws: RemoteSyncError.self) { _ = try await mailbox.get("Snap/%2e%2e") }
        await #expect(throws: RemoteSyncError.self) { try await mailbox.delete("a//b") }
        #expect(server.requests.isEmpty)
    }

    // MARK: The whole protocol

    @Test(arguments: [FakeWebDAV.HrefStyle.absolutePath, .absoluteURL, .relative])
    func devicesSyncThroughAWebDAVFolder(style: FakeWebDAV.HrefStyle) async throws {
        let server = FakeWebDAV()
        server.hrefStyle = style
        let store = server.store()
        try await store.check()
        var devices = household()
        try await RemoteSync.grant(devices[0].key!, to: devices[0].snapshot.membership.active, store: store,
                                   identity: devices[0].identity, storage: "webdav:" + store.baseURL.absoluteString)
        let label = Data("label".utf8)
        let photo = PhotoID.make(for: label)
        devices[0].photos.access.save(photo, .photo, label)
        try devices[0].edit { try $0.addBottles(of: Wine(name: "Château WebDAV", photoID: photo), quantity: 2) }
        try await devices[0].sync(store)
        try await devices[1].sync(store)
        try devices[1].edit { try $0.addBottles(of: Wine(name: "From B"), quantity: 1) }
        try await devices[1].sync(store)
        try await devices[2].sync(store)
        try await devices[0].sync(store)
        #expect(devices[0].snapshot.cellar == devices[1].snapshot.cellar)
        #expect(devices[2].snapshot.cellar.bottles.count == 3)
        #expect(devices[2].photos.stored[photo] == label)
        // Only ciphertext, and only under the chosen folder.
        let files = server.allFiles
        #expect(!files.isEmpty)
        for (path, data) in files where !path.contains("/grant/") {
            #expect(path.hasPrefix(server.basePath))
            #expect(data.prefix(4) == Data("ENCV".utf8))
            #expect(data.range(of: Data("WebDAV".utf8)) == nil)
        }
        // A removed device is left behind.
        let removed = devices[2].identity.id
        var members = devices[0].snapshot.membership
        members.remove(removed, clock: &devices[0].snapshot.state.clock, by: devices[0].identity.id)
        devices[0].snapshot.membership = members
        let next = CellarKey.random(cellarID: members.cellarID, epoch: 2)
        try await RemoteSync.rotate(store: store, identity: devices[0].identity, from: devices[0].key!, to: next,
                                    local: devices[0].snapshot, storage: nil)
        devices[0].key = next
        try await devices[1].sync(store)
        #expect(devices[1].key == next)
    }
}
