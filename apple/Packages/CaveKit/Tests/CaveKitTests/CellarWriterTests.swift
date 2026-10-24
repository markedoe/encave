import Foundation
import Testing
@testable import CaveKit

/// A store whose writes take a while, and can be made to fail.
private final class SlowStore: CellarStore, @unchecked Sendable {
    private let lock = NSLock()
    private var saved: [Cellar] = []
    private var failing = false
    let delay: TimeInterval

    init(delay: TimeInterval = 0.05) { self.delay = delay }

    var writes: [Cellar] { lock.withLock { saved } }
    func setFailing(_ value: Bool) { lock.withLock { failing = value } }

    func load() throws -> Cellar { lock.withLock { saved.last ?? Cellar() } }

    func save(_ cellar: Cellar) throws {
        Thread.sleep(forTimeInterval: delay)
        try lock.withLock {
            if failing { throw CocoaError(.fileWriteOutOfSpace) }
            saved.append(cellar)
        }
    }
}

/// A store whose first write waits until `release()`, so the test decides what comes in
/// during it.
private final class GatedStore: CellarStore, @unchecked Sendable {
    private let lock = NSLock()
    private var saved: [Cellar] = []
    private let started = DispatchSemaphore(value: 0)
    private let gate = DispatchSemaphore(value: 0)
    private var first = true

    var writes: [Cellar] { lock.withLock { saved } }

    func load() throws -> Cellar { lock.withLock { saved.last ?? Cellar() } }

    func save(_ cellar: Cellar) throws {
        let waits = lock.withLock { () -> Bool in
            defer { first = false }
            return first
        }
        if waits {
            started.signal()
            // Bounded, so a broken test fails instead of hanging.
            _ = gate.wait(timeout: .now() + 30)
        }
        lock.withLock { saved.append(cellar) }
    }

    /// Returns once the first write has started.
    func waitForTheFirstWrite() async {
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            DispatchQueue.global().async {
                _ = self.started.wait(timeout: .now() + 30)
                done.resume()
            }
        }
    }

    func release() { gate.signal() }
}

/// What the failure handler was told.
private actor Failures {
    var lastSaved: [Cellar] = []
    func record(_ cellar: Cellar) { lastSaved.append(cellar) }
}

private func cellar(named name: String) throws -> Cellar {
    var cellar = Cellar()
    try cellar.addBottles(of: Wine(name: name))
    return cellar
}

struct CellarWriterTests {
    @Test func coalescesChangesMadeDuringAWrite() async throws {
        let store = GatedStore()
        let writer = CellarWriter(store: store, saved: Cellar()) { _, _ in 0 }
        let versions = try (1...20).map { try cellar(named: "Wine \($0)") }
        await writer.submit(versions[0], generation: 1)
        await store.waitForTheFirstWrite()
        for (index, version) in versions.enumerated().dropFirst() {
            await writer.submit(version, generation: index + 1)
        }
        store.release()
        await writer.flush()
        // The 19 changes made during the first write are coalesced into one write of the newest.
        #expect(store.writes == [versions[0], versions[19]])
        #expect(await writer.lastSaved == versions.last)
        #expect(await writer.savedGeneration == 20)
    }

    @Test func ignoresAnOlderChangeThatArrivesLate() async throws {
        let store = SlowStore(delay: 0)
        let writer = CellarWriter(store: store, saved: Cellar()) { _, _ in 0 }
        let newer = try cellar(named: "Newer"), older = try cellar(named: "Older")
        await writer.submit(newer, generation: 2)
        await writer.submit(older, generation: 1)
        await writer.flush()
        #expect(store.writes == [newer])
    }

    @Test func reportsTheLastSavedCellarAndDropsWhatFollowedAFailure() async throws {
        let store = SlowStore(delay: 0.02)
        let first = try cellar(named: "Saved")
        let failures = Failures()
        let writer = CellarWriter(store: store, saved: Cellar()) { lastSaved, _ in
            await failures.record(lastSaved)
            // The caller went back to the last saved cellar after its change number 5.
            return 5
        }
        await writer.submit(first, generation: 1)
        await writer.flush()
        store.setFailing(true)
        await writer.submit(try cellar(named: "Fails"), generation: 2)
        // Sent before the caller heard of the failure: built on the failed change.
        await writer.submit(try cellar(named: "After the failure"), generation: 3)
        await writer.flush()
        #expect(await failures.lastSaved == [first])
        #expect(await writer.lastSaved == first)
        // Arrives late, from before the caller went back: dropped.
        store.setFailing(false)
        await writer.submit(try cellar(named: "Late"), generation: 4)
        await writer.flush()
        #expect(store.writes == [first])
        // A change made after going back is saved.
        let next = try cellar(named: "Next")
        await writer.submit(next, generation: 6)
        await writer.flush()
        #expect(store.writes == [first, next])
    }

    @Test func savesToTheFileOffTheCallersActor() async throws {
        let folder = try TemporaryFolder()
        let store = JSONFileCellarStore(fileURL: folder.file("cellar.json"))
        let writer = CellarWriter(store: store, saved: Cellar()) { _, _ in 0 }
        let saved = try cellar(named: "On disk")
        await writer.submit(saved, generation: 1)
        await writer.flush()
        #expect(try store.load() == saved)
        #expect(await writer.writeCount == 1)
    }

    /// A large cellar is encoded on every save: it must stay quick, even in a debug build.
    /// Timing depends on the machine, so it runs only when asked (`ENCAVE_PERF=1`).
    @Test(.enabled(if: ProcessInfo.processInfo.environment["ENCAVE_PERF"] != nil))
    func encodesFiveThousandBottlesWithinBudget() throws {
        var cellar = Cellar()
        for index in 0..<500 {
            let wine = Wine(name: "Château \(index)", producer: "Producer \(index % 50)", vintage: 1990 + index % 35,
                            color: .red, region: "Bordeaux", country: "France", appellation: "Pauillac",
                            grapes: ["Cabernet Sauvignon", "Merlot"], drinkFrom: 2025, drinkUntil: 2040,
                            notes: "Notes for wine \(index)", tags: ["tag\(index % 7)"])
            try cellar.addBottles(of: wine, quantity: 10,
                                  purchase: PurchaseInfo(price: Decimal(25 + index % 100), place: "Merchant \(index % 20)"))
        }
        #expect(cellar.bottles.count == 5_000)
        let encoder = JSONFileCellarStore.makeEncoder()
        _ = try encoder.encode(cellar)
        let clock = ContinuousClock()
        let elapsed = try clock.measure {
            for _ in 0..<3 { _ = try encoder.encode(cellar) }
        } / 3
        // About 0.1 s on a laptop; the budget leaves room for slow CI machines.
        #expect(elapsed < .seconds(1.5), "Encoding 5,000 bottles took \(elapsed)")
    }
}
