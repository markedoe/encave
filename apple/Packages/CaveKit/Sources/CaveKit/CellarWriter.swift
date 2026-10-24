import Foundation

/// Saves the cellar off the main thread, one write at a time. Each change is numbered
/// (`generation`) by its caller, which keeps the cellar in memory and validates changes
/// synchronously: while a write runs, the changes that come in are coalesced into one
/// write of the newest. A change that arrives late (an older number) is ignored.
///
/// When a write fails, the changes waiting are dropped and `onFailure` is told the last
/// cellar saved; it returns the newest number it had given, so changes sent before it
/// went back to that cellar are dropped too.
public actor CellarWriter {
    /// The last cellar saved, and why the write failed. Returns the caller's newest generation.
    public typealias FailureHandler = @Sendable (_ lastSaved: Cellar, _ error: any Error) async -> Int
    /// Told the generation of each cellar saved, before the next write starts.
    public typealias SavedHandler = @Sendable (_ generation: Int) async -> Void

    private let store: any CellarStore
    private let onFailure: FailureHandler
    private let onSaved: SavedHandler?
    private var pending: (cellar: Cellar, generation: Int)?
    /// Newest generation received (or dropped after a failure).
    private var newest: Int
    private var writing: Task<Void, Never>?
    /// The cellar as it is in the store.
    public private(set) var lastSaved: Cellar
    /// Generation of `lastSaved`.
    public private(set) var savedGeneration: Int
    /// Writes made, for tests.
    public private(set) var writeCount = 0

    /// `saved`: the cellar as loaded from the store, numbered `generation`.
    public init(store: any CellarStore, saved: Cellar, generation: Int = 0, onSaved: SavedHandler? = nil,
                onFailure: @escaping FailureHandler) {
        self.store = store
        self.onFailure = onFailure
        self.onSaved = onSaved
        lastSaved = saved
        newest = generation
        savedGeneration = generation
    }

    /// Queues `cellar` to be saved. Returns at once; `flush()` waits for the write.
    public func submit(_ cellar: Cellar, generation: Int) {
        guard generation > newest else { return }
        newest = generation
        pending = (cellar, generation)
        if writing == nil {
            writing = Task { await self.drain() }
        }
    }

    /// Waits until everything received so far is saved (or has failed).
    public func flush() async {
        while let writing {
            await writing.value
        }
    }

    private func drain() async {
        while let next = pending {
            pending = nil
            do {
                // Encoding and writing happen off this actor, so new changes can come in
                // meanwhile and be coalesced.
                try await Self.write(next.cellar, to: store)
                writeCount += 1
                lastSaved = next.cellar
                savedGeneration = next.generation
                await onSaved?(next.generation)
            } catch {
                // Later changes were built on the one that failed.
                pending = nil
                let reverted = await onFailure(lastSaved, error)
                newest = max(newest, reverted)
                if let waiting = pending, waiting.generation <= newest { pending = nil }
            }
        }
        writing = nil
    }

    /// Runs on the global executor, not on this actor.
    private nonisolated static func write(_ cellar: Cellar, to store: any CellarStore) async throws {
        try store.save(cellar)
    }
}
