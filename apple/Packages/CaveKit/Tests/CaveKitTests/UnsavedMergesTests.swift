import Foundation
import Testing
@testable import CaveKit

/// A store whose writes fail while `failing` is set.
private final class FlakyStore: CellarStore, @unchecked Sendable {
    private let lock = NSLock()
    private var failing = true
    private var saved: [Cellar] = []

    func setFailing(_ value: Bool) { lock.withLock { failing = value } }
    var writes: [Cellar] { lock.withLock { saved } }

    func load() throws -> Cellar { lock.withLock { saved.last ?? Cellar() } }

    func save(_ cellar: Cellar) throws {
        try lock.withLock {
            if failing { throw CocoaError(.fileWriteOutOfSpace) }
            saved.append(cellar)
        }
    }
}

/// What the writer reported, as the app's model and sync service hear it.
private actor Reports {
    var merges = UnsavedMerges()
    var lastSaved: [Cellar] = []

    func add(before: [String: Stamp], after: [String: Stamp], generation: Int) {
        merges.add(before: before, after: after, generation: generation)
    }
    func saved(_ generation: Int) { merges.saved(through: generation) }
    func failed(_ cellar: Cellar) { lastSaved.append(cellar) }
    func reverted(_ state: SyncState) -> SyncState {
        var state = state
        merges.revert(&state)
        return state
    }
}

private let deviceA = UUID(uuidString: "11111111-1111-1111-1111-111111111111")!
private let deviceB = UUID(uuidString: "22222222-2222-2222-2222-222222222222")!

/// The merge, as a device takes in another one's copy.
private func merge(_ cellar: Cellar, _ state: inout SyncState, with remote: Cellar, _ remoteState: SyncState) -> Cellar {
    let result = CellarMerge.merge(local: cellar, state, remote: remote, remoteState)
    state.stamps = result.stamps
    state.clock = max(state.clock, result.clock)
    return result.cellar
}

@Suite struct UnsavedMergesTests {
    /// A shares a cellar with B; B renames the wine.
    private func setUp() throws -> (base: Cellar, a: SyncState, edited: Cellar, b: SyncState, wine: UUID) {
        var base = Cellar()
        let wine = Wine(name: "Old", vintage: 2018, color: .red)
        try base.addBottles(of: wine, quantity: 1)
        let a = SyncState.starting(with: base, device: deviceA)
        var b = SyncState(device: deviceB)
        let joined = merge(Cellar(), &b, with: base, a)
        var edited = joined
        var renamed = edited.wines[wine.id]!
        renamed.name = "New"
        try edited.updateWine(renamed)
        b.record(from: joined, to: edited)
        return (base, a, edited, b, wine.id)
    }

    @Test func aMergeThatCouldNotBeSavedIsTakenInAgain() async throws {
        let (base, startA, edited, stateB, wine) = try setUp()
        var stateA = startA
        let store = FlakyStore()
        let reports = Reports()
        let writer = CellarWriter(store: store, saved: base, onSaved: { await reports.saved($0) }) { lastSaved, _ in
            await reports.failed(lastSaved)
            return 1
        }
        // A takes in B's rename; the stamps are used at once, the write fails.
        let before = stateA.stamps
        let shown = merge(base, &stateA, with: edited, stateB)
        #expect(shown.wines[wine]?.name == "New")
        await reports.add(before: before, after: stateA.stamps, generation: 1)
        await writer.submit(shown, generation: 1)
        await writer.flush()
        #expect(await reports.lastSaved == [base])

        // The cellar went back to what was saved, and so do the stamps.
        stateA.recordRevert(from: shown, to: base)
        let withoutRevert = stateA
        stateA = await reports.reverted(stateA)
        #expect(await reports.merges.isEmpty)
        // Kept as they were, B's stamp would say A has the rename: the devices never converge.
        #expect(CellarMerge.merge(local: base, withoutRevert, remote: edited, stateB).cellar.wines[wine]?.name == "Old")

        // The next sync takes the rename in again, and both devices agree.
        store.setFailing(false)
        let cellarA = merge(base, &stateA, with: edited, stateB)
        await writer.submit(cellarA, generation: 2)
        await writer.flush()
        #expect(store.writes == [cellarA])
        var stateB2 = stateB
        let cellarB = merge(edited, &stateB2, with: cellarA, stateA)
        #expect(cellarA.wines[wine]?.name == "New")
        #expect(cellarA == cellarB)
        #expect(stateA.stamps == stateB2.stamps)
    }

    @Test func aSavedMergeKeepsItsStampsWhenALaterWriteFails() async throws {
        let (base, startA, edited, stateB, wine) = try setUp()
        var stateA = startA
        let store = FlakyStore()
        store.setFailing(false)
        let reports = Reports()
        let writer = CellarWriter(store: store, saved: base, onSaved: { await reports.saved($0) }) { lastSaved, _ in
            await reports.failed(lastSaved)
            return 2
        }
        let before = stateA.stamps
        let merged = merge(base, &stateA, with: edited, stateB)
        await reports.add(before: before, after: stateA.stamps, generation: 1)
        await writer.submit(merged, generation: 1)
        await writer.flush()
        #expect(await reports.merges.isEmpty)

        // A local change fails to save: only it goes back.
        store.setFailing(true)
        var local = merged
        try local.addBottles(of: Wine(name: "Local"), quantity: 1)
        let mergedStamps = stateA.stamps
        stateA.record(from: merged, to: local)
        await writer.submit(local, generation: 2)
        await writer.flush()
        stateA.recordRevert(from: local, to: merged)
        stateA = await reports.reverted(stateA)
        #expect(stateA.stamps[SyncKey(.wine, wine).string] == mergedStamps[SyncKey(.wine, wine).string])
        #expect(CellarMerge.merge(local: merged, stateA, remote: edited, stateB).cellar == merged)
    }

    @Test func revertingGivesBackTheOldestStampOfEachObject() {
        var merges = UnsavedMerges()
        let first = Stamp(counter: 1, device: deviceA), second = Stamp(counter: 4, device: deviceB)
        let third = Stamp(counter: 6, device: deviceB)
        merges.add(before: ["wine:x": first], after: ["wine:x": second, "bottle:y": second], generation: 1)
        merges.add(before: ["wine:x": second, "bottle:y": second], after: ["wine:x": third, "bottle:y": second],
                   generation: 2)
        // Nothing changed: nothing kept.
        merges.add(before: ["wine:x": third], after: ["wine:x": third], generation: 3)
        var state = SyncState(device: deviceA, clock: 6, stamps: ["wine:x": third, "bottle:y": second])
        merges.revert(&state)
        #expect(state.stamps == ["wine:x": first])
        #expect(state.clock == 6)
        #expect(merges.isEmpty)
    }
}
