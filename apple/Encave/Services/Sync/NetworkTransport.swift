import CaveKit
import Foundation
import Network

/// A TCP connection carrying frames: a 4-byte big-endian length, then the bytes.
final class NetworkTransport: FrameTransport, @unchecked Sendable {
    let connection: NWConnection
    private let queue = DispatchQueue(label: "app.encave.sync.connection")

    init(connection: NWConnection) {
        self.connection = connection
    }

    /// Starts the connection and waits until it is ready (or fails within `timeout`).
    func start(timeout: Duration = .seconds(15)) async throws {
        try await withThrowingTaskGroup(of: Void.self) { group in
            group.addTask { try await self.waitUntilReady() }
            group.addTask {
                try await Task.sleep(for: timeout)
                throw SyncError.closed
            }
            defer { group.cancelAll() }
            try await group.next()
        }
    }

    private func waitUntilReady() async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let resumed = Once()
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready: resumed.run { continuation.resume() }
                case .failed(let error), .waiting(let error): resumed.run { continuation.resume(throwing: error) }
                case .cancelled: resumed.run { continuation.resume(throwing: SyncError.closed) }
                default: break
                }
            }
            connection.start(queue: queue)
        }
    }

    func send(_ frame: Data) async throws {
        guard frame.count <= SyncProtocol.maxFrameSize else { throw SyncError.frameTooLarge }
        var length = UInt32(frame.count).bigEndian
        let packet = Data(bytes: &length, count: 4) + frame
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.send(content: packet, completion: .contentProcessed { error in
                if let error { continuation.resume(throwing: error) } else { continuation.resume() }
            })
        }
    }

    func receive() async throws -> Data {
        let header = try await read(4)
        let length = header.reduce(0) { $0 << 8 | Int($1) }
        guard length <= SyncProtocol.maxFrameSize else { throw SyncError.frameTooLarge }
        return length == 0 ? Data() : try await read(length)
    }

    private func read(_ count: Int) async throws -> Data {
        var buffer = Data()
        while buffer.count < count {
            let chunk: Data = try await withCheckedThrowingContinuation { continuation in
                connection.receive(minimumIncompleteLength: 1, maximumLength: count - buffer.count) { data, _, isComplete, error in
                    if let error { continuation.resume(throwing: error) }
                    else if let data, !data.isEmpty { continuation.resume(returning: data) }
                    else if isComplete { continuation.resume(throwing: SyncError.closed) }
                    else { continuation.resume(returning: Data()) }
                }
            }
            buffer.append(chunk)
        }
        return buffer
    }

    func close() {
        connection.cancel()
    }
}

/// Runs a closure at most once, from any thread.
final class Once: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false

    func run(_ body: () -> Void) {
        lock.lock()
        let first = !done
        done = true
        lock.unlock()
        if first { body() }
    }
}
