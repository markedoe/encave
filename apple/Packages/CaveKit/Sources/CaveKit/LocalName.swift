import Foundation
#if canImport(Darwin)
import Darwin
#elseif canImport(Glibc)
import Glibc
#endif

/// Why plain HTTP to a server name was refused.
public enum LocalNetworkError: Error, Equatable, Sendable {
    /// The name has no address on the local network: use https, or the server's local address.
    case notLocal
    /// The name could not be found on this network (away from home, a typo).
    case notFound
}

/// Plain HTTP to a server on the local network reached by a name of the home network
/// ("http://nas.lan:8080", "http://nas.local", a name the router or a local DNS gives).
///
/// The name is resolved before each request (the answer is kept a minute): it is accepted
/// only if it has a local address, and the request then goes to that address, not to the
/// name, so neither a public address of the same name nor a later DNS answer can receive it
/// (DNS rebinding), and App Transport Security treats it as a local address. Only addresses
/// and "localhost" are used as they are. HTTPS is left as it is: by name, with the
/// certificate checked.
///
/// A server that tells sites apart by their name (virtual hosts, Nextcloud's
/// trusted_domains) may answer differently by address; SearXNG, Ollama and WebDAV on a NAS
/// answer the same.
public enum LocalName {
    /// The addresses of a name, as numeric text (IPv4 dotted, or IPv6).
    public typealias Resolver = @Sendable (String) async throws -> [String]

    /// How long resolving a name may take.
    public static let timeout: TimeInterval = 5
    /// How long an answer is kept.
    public static let cacheLifetime: TimeInterval = 60

    /// True when plain HTTP to `host` may be accepted: a local name or address, or another
    /// name, which `resolve` checks before each request. False for an address outside the
    /// local network.
    public static func mayBeLocal(host: String) -> Bool {
        LocalAddress.isLocal(host: host) || isName(host)
    }

    /// The URL to send `url` to: unchanged for HTTPS, a local address and "localhost"; for
    /// plain HTTP to a name, the same URL with the name replaced by its best local address.
    /// Throws `LocalNetworkError` if the name is not found or has no local address.
    public static func resolve(_ url: URL, resolver: Resolver? = nil) async throws -> URL {
        guard url.scheme?.lowercased() == "http", let host = url.host?.lowercased(), !host.isEmpty else { return url }
        guard isName(host) else {
            if LocalAddress.isLocal(host: host) { return url }
            throw LocalNetworkError.notLocal
        }
        if host == "localhost" { return url }
        // "nas..lan" or ".lan" is no name of the network.
        guard !host.hasPrefix("."), !host.contains("..") else { throw LocalNetworkError.notLocal }
        let addresses: [String]
        if let resolver {
            addresses = try await resolver(host)
        } else if let cached = cache.addresses(of: host) {
            addresses = cached
        } else {
            addresses = try await system(host)
            cache.keep(addresses, for: host)
        }
        let address = try choose(addresses)
        guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            throw LocalNetworkError.notFound
        }
        // "http://nas.lan:8888" becomes "http://10.0.0.2:8888/", as in Kotlin (OkHttp).
        if components.percentEncodedPath.isEmpty { components.percentEncodedPath = "/" }
        if address.contains(":") {
            components.percentEncodedHost = "[" + address + "]"
        } else {
            components.host = address
        }
        guard let resolved = components.url else { throw LocalNetworkError.notFound }
        return resolved
    }

    /// How an address of a name may be used.
    enum Use: Equatable {
        /// Local; a lower rank is preferred.
        case local(rank: Int)
        /// Link-local with an interface ("fe80::1%en0"): not usable in a URL.
        case scoped
        /// Outside the local network.
        case outside
    }

    /// The best local address: private or loopback IPv4 first, then unique local or loopback
    /// IPv6, then link-local IPv4, then link-local IPv6 without an interface. Addresses
    /// outside the local network (a public IPv6 next to a local IPv4, CGNAT) are left
    /// out. `notLocal` if none is local, `notFound` if there is none, or only link-local
    /// ones with an interface.
    static func choose(_ addresses: [String]) throws -> String {
        var best: (rank: Int, address: String)?
        var scoped = false
        for address in addresses {
            switch use(of: address) {
            case .local(let rank): if best == nil || rank < best!.rank { best = (rank, address) }
            case .scoped: scoped = true
            case .outside: break
            }
        }
        if let best { return best.address }
        throw addresses.isEmpty || scoped ? LocalNetworkError.notFound : LocalNetworkError.notLocal
    }

    /// Classifies a numeric address by its bytes (IPv6 however written).
    static func use(of address: String) -> Use {
        let parts = address.split(separator: "%", maxSplits: 1, omittingEmptySubsequences: false)
        let bare = String(parts.first ?? "")
        if !bare.contains(":") {
            guard parts.count == 1, !isName(bare), LocalAddress.isLocal(host: bare) else { return .outside }
            return bare.hasPrefix("169.254.") ? .local(rank: 2) : .local(rank: 0)
        }
        var raw = in6_addr()
        guard inet_pton(AF_INET6, bare, &raw) == 1 else { return .outside }
        let bytes = withUnsafeBytes(of: &raw) { Array($0) }
        let linkLocal = bytes[0] == 0xFE && bytes[1] & 0xC0 == 0x80
        if parts.count == 2 { return linkLocal ? .scoped : .outside }
        if bytes[0] & 0xFE == 0xFC || (bytes[0..<15].allSatisfy { $0 == 0 } && bytes[15] == 1) { return .local(rank: 1) }
        return linkLocal ? .local(rank: 3) : .outside
    }

    /// A name rather than an address: not digits and dots only (IPv4, however written) and
    /// without ":" (IPv6).
    static func isName(_ host: String) -> Bool {
        var host = host.lowercased()
        if host.hasPrefix("["), host.hasSuffix("]") { host = String(host.dropFirst().dropLast()) }
        guard !host.isEmpty, !host.contains(":"), !host.contains("%") else { return false }
        return !host.allSatisfy { $0 == "." || ($0.isASCII && $0.isNumber) }
    }

    // MARK: System resolver

    private static let cache = Cache()

    private final class Cache: @unchecked Sendable {
        private let lock = NSLock()
        private var entries: [String: (addresses: [String], until: Date)] = [:]

        func addresses(of host: String) -> [String]? {
            lock.lock(); defer { lock.unlock() }
            guard let entry = entries[host], entry.until > Date() else { return nil }
            return entry.addresses
        }

        func keep(_ addresses: [String], for host: String) {
            lock.lock(); defer { lock.unlock() }
            entries[host] = (addresses, Date().addingTimeInterval(LocalName.cacheLifetime))
        }
    }

    /// getaddrinfo off the calling thread; `notFound` after `timeout` (the lookup itself
    /// cannot be stopped, its answer is then ignored).
    static func system(_ host: String) async throws -> [String] {
        final class Once: @unchecked Sendable {
            private let lock = NSLock()
            private var continuation: CheckedContinuation<[String], Error>?
            init(_ continuation: CheckedContinuation<[String], Error>) { self.continuation = continuation }
            func finish(_ result: Result<[String], Error>) {
                lock.lock()
                let continuation = self.continuation
                self.continuation = nil
                lock.unlock()
                continuation?.resume(with: result)
            }
        }
        return try await withCheckedThrowingContinuation { continuation in
            let once = Once(continuation)
            DispatchQueue.global(qos: .userInitiated).async {
                let addresses = lookUp(host)
                once.finish(addresses.isEmpty ? .failure(LocalNetworkError.notFound) : .success(addresses))
            }
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout) {
                once.finish(.failure(LocalNetworkError.notFound))
            }
        }
    }

    private static func lookUp(_ host: String) -> [String] {
        var hints = addrinfo()
        hints.ai_family = AF_UNSPEC
        #if canImport(Darwin)
        hints.ai_socktype = SOCK_STREAM
        #else
        hints.ai_socktype = Int32(SOCK_STREAM.rawValue)
        #endif
        var list: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(host, nil, &hints, &list) == 0, let first = list else { return [] }
        defer { freeaddrinfo(first) }
        var addresses: [String] = []
        var next: UnsafeMutablePointer<addrinfo>? = first
        while let info = next {
            var buffer = [CChar](repeating: 0, count: 1025) // NI_MAXHOST
            if let address = info.pointee.ai_addr,
               getnameinfo(address, info.pointee.ai_addrlen, &buffer, socklen_t(buffer.count), nil, 0, NI_NUMERICHOST) == 0 {
                let text = String(decoding: buffer.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }, as: UTF8.self)
                if !addresses.contains(text) { addresses.append(text) }
            }
            next = info.pointee.ai_next
        }
        return addresses
    }
}
