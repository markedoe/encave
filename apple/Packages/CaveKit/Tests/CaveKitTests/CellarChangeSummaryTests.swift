import Foundation
import Testing
@testable import CaveKit

/// What a sync brought in, as told in the notification of changes from other devices.
@Suite struct CellarChangeSummaryTests {
    let here = UUID()
    let marc = UUID()
    let julie = UUID()

    /// A cellar shared by this device and Marc's, both up to date.
    private func shared() throws -> (cellar: Cellar, local: SyncState, remote: SyncState, wine: Wine, bottles: [Bottle]) {
        var cellar = Cellar()
        let rack = Rack(name: "Main", rows: 2, columns: 3)
        cellar.addRack(rack)
        let wine = Wine(name: "Clos", vintage: 2017, color: .red)
        let bottles = try cellar.addBottles(of: wine, quantity: 3, slots: [Slot(rackID: rack.id, row: 0, column: 0)])
        let local = SyncState.starting(with: cellar, device: here)
        var remote = SyncState(device: marc)
        let joined = CellarMerge.merge(local: Cellar(), remote, remote: cellar, local)
        remote.stamps = joined.stamps
        remote.clock = joined.clock
        return (cellar, local, remote, wine, bottles)
    }

    /// Marc changes his copy, then this device merges it: the summary of what changed here.
    private func sync(_ cellar: Cellar, _ local: SyncState, remote: SyncState,
                      change: (inout Cellar) throws -> Void, by device: UUID? = nil) rethrows -> CellarChangeSummary {
        var theirs = cellar
        var theirState = remote
        if let device { theirState.device = device }
        try change(&theirs)
        theirState.record(from: cellar, to: theirs)
        let merged = CellarMerge.merge(local: cellar, local, remote: theirs, theirState)
        return CellarChangeSummary.between(cellar, merged.cellar, stamps: merged.stamps, localDevice: here)
    }

    @Test func countsBottlesOpenedAddedAndRemoved() throws {
        let s = try shared()
        let summary = try sync(s.cellar, s.local, remote: s.remote) { cellar in
            try cellar.drink(s.bottles[0].id, tasting: Tasting(rating: 8))
            try cellar.drink(s.bottles[1].id, tasting: Tasting(rating: 7))
            try cellar.markGone(s.bottles[2].id)
            try cellar.addBottles(of: s.wine, quantity: 3)
        }
        #expect(summary.bottlesOpened == 2)
        #expect(summary.bottlesAdded == 3)
        #expect(summary.bottlesRemoved == 1)
        #expect(summary.winesAdded == 0)
        #expect(summary.otherChanges == 0)
        #expect(summary.devices == [marc])
        #expect(!summary.isOnlyOpened)
    }

    @Test func aNewWineAndOtherChanges() throws {
        let s = try shared()
        let summary = try sync(s.cellar, s.local, remote: s.remote) { cellar in
            try cellar.addBottles(of: Wine(name: "Other"), quantity: 1, purchase: PurchaseInfo(isOrdered: true))
            var wine = cellar.wines[s.wine.id]!
            wine.notes = "Decant"
            try cellar.updateWine(wine)
            try cellar.move(s.bottles[0].id, to: nil)
            cellar.addRack(Rack(name: "Fridge", rows: 1, columns: 1))
        }
        // The new wine has a bottle: it is told by its bottle, not as a wine added.
        #expect(summary.winesAdded == 0)
        // An ordered bottle counts as added.
        #expect(summary.bottlesAdded == 1)
        // The wine edited, the bottle moved, the new rack.
        #expect(summary.otherChanges == 3)
    }

    @Test func deletingAWineRemovesItsBottles() throws {
        let s = try shared()
        let summary = sync(s.cellar, s.local, remote: s.remote) { $0.deleteWine(s.wine.id) }
        // The wine itself is not counted: its bottles are.
        #expect(summary.bottlesRemoved == 3)
        #expect(summary.otherChanges == 0)
        #expect(summary.devices == [marc])
    }

    @Test func aBottleGivenAwayOrDeletedIsRemoved() throws {
        let s = try shared()
        var opened = s.cellar
        try opened.drink(s.bottles[0].id, tasting: Tasting(rating: 8))
        var stamps = s.local.stamps
        let key = SyncKey(.bottle, s.bottles[0].id).string
        stamps[key] = Stamp(counter: 90, device: marc)
        // An opened bottle that disappears, or is then marked given away, is removed.
        var deleted = opened
        deleted.deleteBottle(s.bottles[0].id)
        #expect(CellarChangeSummary.between(opened, deleted, stamps: stamps, localDevice: here).bottlesRemoved == 1)
        var gone = opened
        try gone.markGone(s.bottles[0].id)
        #expect(CellarChangeSummary.between(opened, gone, stamps: stamps, localDevice: here).bottlesRemoved == 1)
        // A new bottle already given away is another change.
        var added = s.cellar
        let extra = try #require(try added.addBottles(of: s.wine, quantity: 1).first)
        try added.markGone(extra.id)
        stamps[SyncKey(.bottle, extra.id).string] = Stamp(counter: 91, device: marc)
        let summary = CellarChangeSummary.between(s.cellar, added, stamps: stamps, localDevice: here)
        #expect(summary.otherChanges == 1 && summary.bottlesAdded == 0 && summary.bottlesRemoved == 0)
    }

    @Test func onlyOpenedPointsToTheJournal() throws {
        let s = try shared()
        let summary = try sync(s.cellar, s.local, remote: s.remote) {
            try $0.drink(s.bottles[0].id, tasting: Tasting(rating: 9))
        }
        #expect(summary.isOnlyOpened)
        #expect(!summary.isEmpty)
    }

    @Test func nothingNewIsEmpty() throws {
        let s = try shared()
        let summary = sync(s.cellar, s.local, remote: s.remote) { _ in }
        #expect(summary.isEmpty)
        #expect(summary.devices.isEmpty)
        #expect(CellarChangeSummary.between(s.cellar, s.cellar, stamps: s.local.stamps, localDevice: here).isEmpty)
    }

    @Test func changesOfThisDeviceAreLeftOutAndUnstampedOnesHaveNoKnownDevice() throws {
        let s = try shared()
        var after = s.cellar
        try after.drink(s.bottles[0].id, tasting: Tasting(rating: 8))
        try after.addBottles(of: s.wine, quantity: 1)
        var stamps = s.local.stamps
        stamps[SyncKey(.bottle, s.bottles[0].id).string] = Stamp(counter: 50, device: here)
        // The opened bottle is this device's own change; the new bottle has no stamp at all.
        var summary = CellarChangeSummary.between(s.cellar, after, stamps: stamps, localDevice: here)
        #expect(summary.bottlesOpened == 0)
        #expect(summary.bottlesAdded == 1)
        #expect(summary.devices.isEmpty)
        #expect(summary.hasUnknownDevice)
        // A stamp from before sync was set up does not name a device either.
        stamps[SyncKey(.bottle, s.bottles[0].id).string] = .zero
        summary = CellarChangeSummary.between(s.cellar, after, stamps: stamps, localDevice: here)
        #expect(summary.bottlesOpened == 1)
        #expect(summary.devices.isEmpty && summary.hasUnknownDevice)
    }

    @Test func namesTheDeviceOnlyWhenThereIsOneKnownDevice() throws {
        var membership = Membership()
        var clock: UInt64 = 0
        membership.add(Member(id: marc, name: "Marc’s iPhone", publicKey: Data(count: 32), platform: "ios"),
                       clock: &clock, by: marc)
        var summary = CellarChangeSummary()
        summary.bottlesOpened = 1
        #expect(summary.device(in: membership) == nil)
        summary.devices = [marc]
        #expect(summary.device(in: membership)?.name == "Marc’s iPhone")
        #expect(summary.device(in: nil) == nil)
        // Several devices, or one the cellar does not know (removed): "your other devices".
        summary.devices = [marc, julie]
        #expect(summary.device(in: membership) == nil)
        summary.devices = [julie]
        #expect(summary.device(in: membership) == nil)
        // Marc's changes and some whose device is not known.
        summary.devices = [marc]
        summary.hasUnknownDevice = true
        #expect(summary.device(in: membership) == nil)
    }

    @Test func changesFromTwoDevicesAreBothCredited() throws {
        let s = try shared()
        var theirs = s.cellar
        var marcState = s.remote
        try theirs.drink(s.bottles[0].id, tasting: Tasting(rating: 8))
        marcState.record(from: s.cellar, to: theirs)
        // Julie's device saw Marc's change, then added a bottle.
        var julieState = marcState
        julieState.device = julie
        let before = theirs
        try theirs.addBottles(of: s.wine, quantity: 1)
        julieState.record(from: before, to: theirs)
        let merged = CellarMerge.merge(local: s.cellar, s.local, remote: theirs, julieState)
        let summary = CellarChangeSummary.between(s.cellar, merged.cellar, stamps: merged.stamps, localDevice: here)
        #expect(summary.devices == [marc, julie])
        #expect(summary.bottlesOpened == 1)
        #expect(summary.bottlesAdded == 1)
    }

    @Test func summariesAddUpAndSurviveEncoding() throws {
        var first = CellarChangeSummary()
        first.bottlesOpened = 1
        first.devices = [marc]
        var second = CellarChangeSummary()
        second.bottlesAdded = 2
        second.winesAdded = 1
        second.otherChanges = 3
        second.bottlesRemoved = 1
        second.devices = [julie]
        second.hasUnknownDevice = true
        first.add(second)
        #expect(first.bottlesOpened == 1 && first.bottlesAdded == 2 && first.winesAdded == 1)
        #expect(first.bottlesRemoved == 1 && first.otherChanges == 3)
        #expect(first.devices == [marc, julie])
        #expect(first.hasUnknownDevice)
        let decoded = try JSONDecoder().decode(CellarChangeSummary.self, from: JSONEncoder().encode(first))
        #expect(decoded == first)
    }
}
