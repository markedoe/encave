import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// A response body over its limit: a server that sends more is not read into memory.
public struct ResponseTooLarge: Error, Equatable, Sendable {
    public let limit: Int
}

/// Response bodies read with a size limit, so that a hostile server (a relay, a WebDAV, AI or
/// search server) cannot fill the memory. The same limits as the Kotlin core's `CappedRead`.
public enum CappedRead {
    /// Sync files (relay, WebDAV): a frame holds at most 16 MiB (docs/SYNC.md).
    public static let syncLimit = 16 << 20
    /// AI and search answers (JSON).
    public static let jsonLimit = 4 << 20

    /// Sends `request` (with `body`, if any) and reads the answer up to `limit` bytes:
    /// `ResponseTooLarge` as soon as it is over, or at once when its Content-Length says so.
    public static func data(_ session: URLSession, for request: URLRequest, body: Data? = nil, limit: Int,
                            delegate: (any URLSessionTaskDelegate)? = nil) async throws -> (Data, URLResponse) {
        #if canImport(Darwin)
        var request = request
        if let body { request.httpBody = body }
        let (bytes, response) = try await session.bytes(for: request, delegate: delegate)
        guard response.expectedContentLength <= Int64(limit) else {
            bytes.task.cancel()
            throw ResponseTooLarge(limit: limit)
        }
        var data = Data()
        data.reserveCapacity(Int(max(0, min(response.expectedContentLength, Int64(min(limit, 1 << 20))))))
        var chunk: [UInt8] = []
        chunk.reserveCapacity(64 * 1024)
        for try await byte in bytes {
            chunk.append(byte)
            if chunk.count == 64 * 1024 {
                guard data.count + chunk.count <= limit else { bytes.task.cancel(); throw ResponseTooLarge(limit: limit) }
                data.append(contentsOf: chunk)
                chunk.removeAll(keepingCapacity: true)
            }
        }
        guard data.count + chunk.count <= limit else { throw ResponseTooLarge(limit: limit) }
        data.append(contentsOf: chunk)
        return (data, response)
        #else
        // swift-corelibs-foundation (where the tests run) has no streamed body: checked once read.
        let (data, response) = if let body {
            try await session.upload(for: request, from: body)
        } else {
            try await session.data(for: request)
        }
        guard response.expectedContentLength <= Int64(limit), data.count <= limit else { throw ResponseTooLarge(limit: limit) }
        return (data, response)
        #endif
    }
}
