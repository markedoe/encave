import Foundation
import Testing
@testable import CaveKit

/// Leaving a cellar, joining with Replace, sync health and retries (the same cases are
/// tested in the Kotlin core).
@Suite struct SyncDevicesTests {
    private func identity() -> DeviceIdentity { DeviceIdentity.generate().identity }

    private func members() -> (DeviceIdentity, DeviceIdentity, Membership) {
        let a = identity(), b = identity()
        var membership = Membership()
        var clock: UInt64 = 0
        membership.add(Member(id: a.id, name: "A", publicKey: a.publicKey, platform: "ios"), clock: &clock, by: a.id)
        membership.add(Member(id: b.id, name: "B", publicKey: b.publicKey, platform: "android"), clock: &clock, by: a.id)
        return (a, b, membership)
    }

    // MARK: Leaving

    @Test func aDeviceThatLeavesIsRemovedOnTheOtherDeviceItMeets() async throws {
        let (a, b, membership) = members()
        // B removes itself, then pushes: the handshake uses the list from before the removal.
        var left = membership
        var clock: UInt64 = 10
        left.remove(b.id, clock: &clock, by: b.id)
        let snapshotB = SyncSnapshot(cellar: Cellar(), state: SyncState(device: b.id, clock: clock), membership: left)
        let snapshotA = SyncSnapshot(cellar: Cellar(), state: SyncState(device: a.id, clock: 2), membership: membership)
        let (endB, endA) = Pipe.pair()
        let push = Task {
            let (channel, _) = try await SyncHandshake.initiate(over: endB, identity: b, membership: membership)
            return try await SyncExchange.run(over: channel, isInitiator: true, local: snapshotB, files: .none)
        }
        let (channel, peer) = try await SyncHandshake.respond(over: endA, identity: a, membership: membership)
        #expect(peer.id == b.id)
        let resultA = try await SyncExchange.run(over: channel, isInitiator: false, local: snapshotA, files: .none)
        _ = try await push.value
        #expect(resultA.membership.members[b.id] == nil)
        #expect(resultA.membership.members[a.id] != nil)
        #expect(resultA.membership.stamps[b.id]?.deleted == true)
    }

    @Test func aDeviceThatLeavesIsRemovedThroughStorage() async throws {
        let store = MemoryMailboxStore()
        var devices = household(2)
        try await RemoteSync.grant(devices[0].key!, to: devices[0].snapshot.membership.active, store: store,
                                   identity: devices[0].identity, storage: nil)
        try await devices[1].sync(store)
        // Device 1 leaves: removes itself, then one round.
        let leaving = devices[1].identity.id
        var clock = devices[1].snapshot.state.clock
        devices[1].snapshot.membership.remove(leaving, clock: &clock, by: leaving)
        devices[1].snapshot.state.clock = clock
        try await devices[1].sync(store)
        try await devices[0].sync(store)
        #expect(devices[0].snapshot.membership.members[leaving] == nil)
        #expect(devices[0].snapshot.membership.active.count == 1)
    }

    // MARK: Joining with Replace

    @Test func replaceSendsOnlyChangesMadeSinceJoining() throws {
        var cellar = Cellar()
        let rack = Rack(name: "Fridge", rows: 1, columns: 2)
        cellar.addRack(rack)
        let old = try cellar.addBottles(of: Wine(name: "Old"), quantity: 2)
        // Joined: the stamps start empty; one bottle added since, in the rack.
        var state = SyncState(device: UUID(), clock: 40)
        let before = cellar
        let added = try cellar.addBottles(of: Wine(name: "New"), quantity: 1, slots: [Slot(rackID: rack.id, row: 0, column: 0)])
        state.record(from: before, to: cellar)

        let sent = CellarMerge.onlyStamped(cellar, by: state)
        #expect(sent.bottles.keys.sorted() == added.map(\.id).sorted())
        #expect(sent.bottles[old[0].id] == nil)
        #expect(sent.wines.values.map(\.name) == ["New"])
        // The rack it is in comes along, the settings too.
        #expect(sent.racks.map(\.id) == [rack.id])
        #expect(sent.settings == cellar.settings)
    }

    @Test func replaceTakesTheOtherCellarOnceItArrives() throws {
        var theirs = Cellar()
        try theirs.addBottles(of: Wine(name: "Theirs"), quantity: 3)
        let theirState = SyncState.starting(with: theirs, device: UUID())
        var mine = Cellar()
        try mine.addBottles(of: Wine(name: "Mine"), quantity: 5)
        let myState = SyncState(device: UUID(), clock: theirState.clock)

        let merge = CellarMerge.merge(local: CellarMerge.onlyStamped(mine, by: myState), myState,
                                      remote: theirs, theirState)
        #expect(merge.cellar.bottles.count == 3)
        #expect(merge.cellar.wines.values.map(\.name) == ["Theirs"])
        // Nothing of this device's old bottles is stamped as deleted for the others.
        #expect(!merge.stamps.values.contains { $0.deleted })
    }

    // MARK: Health and retries

    @Test func syncHealth() {
        let now = Date(timeIntervalSinceReferenceDate: 1_000_000)
        func health(shared: Bool = true, removed: Bool = false, key: Bool = false, signIn: Bool = false,
                    network: Bool = false, local: TimeInterval? = nil, remote: TimeInterval? = nil,
                    problem: Bool = false) -> SyncHealth {
            SyncHealth.evaluate(isShared: shared, removed: removed, keyUnavailable: key, signInNeeded: signIn,
                                localNetworkOff: network, localFailingSince: local.map { now.addingTimeInterval(-$0) },
                                remoteFailingSince: remote.map { now.addingTimeInterval(-$0) },
                                remoteProblem: problem, now: now)
        }
        #expect(health() == .ok)
        #expect(health(shared: false, signIn: true, local: 7200) == .ok)
        #expect(health(shared: false, removed: true) == .needsAction(.removed))
        #expect(health(key: true) == .needsAction(.keyUnavailable))
        #expect(health(signIn: true, local: 7200) == .needsAction(.signInNeeded))
        #expect(health(network: true) == .needsAction(.localNetworkOff))
        // Failing for a few minutes is not shown; for over an hour it is.
        #expect(health(local: 600, remote: 600) == .ok)
        #expect(health(local: 3700) == .failing)
        #expect(health(remote: 3600) == .failing)
        #expect(health(problem: true) == .failing)
    }

    @Test func retriesComeAfter15SecondsThenAMinuteThenFive() {
        #expect(SyncRetry.delay(afterFailures: 0) == nil)
        #expect(SyncRetry.delay(afterFailures: 1) == 15)
        #expect(SyncRetry.delay(afterFailures: 2) == 60)
        #expect(SyncRetry.delay(afterFailures: 3) == 300)
        #expect(SyncRetry.delay(afterFailures: 4) == nil)
    }

    // MARK: Changes told in the notification

    private func summary(_ change: (inout Cellar) throws -> Void) rethrows -> CellarChangeSummary {
        let here = UUID(), marc = UUID()
        let before = Cellar()
        var after = before
        try change(&after)
        var state = SyncState(device: marc)
        state.record(from: before, to: after)
        let merged = CellarMerge.merge(local: before, SyncState(device: here), remote: after, state)
        return CellarChangeSummary.between(before, merged.cellar, stamps: merged.stamps, localDevice: here)
    }

    @Test func aNewWineWithBottlesIsToldByItsBottles() throws {
        let told = try summary { try $0.addBottles(of: Wine(name: "Barolo"), quantity: 2) }
        #expect(told.bottlesAdded == 2)
        #expect(told.winesAdded == 0)
        #expect(told.otherChanges == 0)
    }

    @Test func aWineToBuyIsAddedToTheShoppingList() {
        let told = summary { _ = $0.addDiscovery(Discovery(wineID: UUID(), wantsToBuy: true), wine: Wine(name: "Riesling")) }
        #expect(told.winesAdded == 1)
        #expect(told.otherChanges == 0)
        let tasted = summary { _ = $0.addDiscovery(Discovery(wineID: UUID()), wine: Wine(name: "Riesling")) }
        #expect(tasted.winesAdded == 0)
        #expect(tasted.otherChanges == 1)
    }
}
