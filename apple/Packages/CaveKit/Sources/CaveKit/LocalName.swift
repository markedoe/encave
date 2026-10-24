import Foundation
#if canImport(Darwin)
import Darwin
#elseif canImport(Glibc)
import Glibc
#endif

/// Why plain HTTP to a server name was refused.
public enum LocalNetworkError: Error, Equatable, Sendable {
    /// The name points to an address outside the local network (or to some outside it):
    /// use https, or a local address.
    case notLocal
    /// The name could not be found on this network (away from home, a typo).
    case notFound
}

/// Plain HTTP to a server on the local network reached by a name of the home network
/// ("http://nas.lan:8080", a name the router or a local DNS gives), not only by the names
/// and addresses `LocalAddress` recognizes without asking.
///
/// The name is resolved before each request (the answer is kept a minute): it is accepted
/// only if every address it has is local, and the request then goes to that address, not
/// to the name, so a later DNS answer cannot send it elsewhere (DNS rebinding), and
/// App Transport Security treats it as a local address. HTTPS is left as it is: by name,
/// with the certificate checked.
///
/// A server that tells sites apart by their name (virtual hosts) may answer differently
/// by address; SearXNG, Ollama and WebDAV on a NAS answer the same.
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

    /// The URL to send `url` to: unchanged for HTTPS and for a host `LocalAddress` already
    /// knows as local; for plain HTTP to another name, the same URL with the name replaced by
    /// its local address. Throws `LocalNetworkError` if the name is not found or is not
    /// only on the local network.
    public static func resolve(_ url: URL, resolver: Resolver? = nil) async throws -> URL {
        guard url.scheme?.lowercased() == "http", let host = url.host?.lowercased(), !host.isEmpty,
              !LocalAddress.isLocal(host: host) else { return url }
        guard isName(host) else { throw LocalNetworkError.notLocal }
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
        if address.contains(":") {
            components.percentEncodedHost = "[" + address.replacingOccurrences(of: "%", with: "%25") + "]"
        } else {
            components.host = address
        }
        guard let resolved = components.url else { throw LocalNetworkError.notFound }
        return resolved
    }

    /// The address to use, if all of them are local: IPv4 first, then IPv6 (link-local last).
    static func choose(_ addresses: [String]) throws -> String {
        guard !addresses.isEmpty else { throw LocalNetworkError.notFound }
        // A link-local IPv6 address carries its interface ("fe80::1%en0"): checked without it.
        func bare(_ address: String) -> String { String(address.split(separator: "%", maxSplits: 1).first ?? "") }
        guard addresses.allSatisfy({ LocalAddress.isLocal(host: bare($0)) && !isName(bare($0)) }) else {
            throw LocalNetworkError.notLocal
        }
        func rank(_ address: String) -> Int { !address.contains(":") ? 0 : address.contains("%") ? 2 : 1 }
        return addresses.min { rank($0) < rank($1) }!
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
                let text = String(cString: buffer)
                if !addresses.contains(text) { addresses.append(text) }
            }
            next = info.pointee.ai_next
        }
        return addresses
    }
}
