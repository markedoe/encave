import Foundation
import Testing
@testable import CaveKit

/// Two devices that edit the same cellar offline, then sync.
private struct Device {
    var cellar: Cellar
    var state: SyncState

    init(_ cellar: Cellar, device: UUID = UUID()) {
        self.cellar = cellar
        state = SyncState.starting(with: cellar, device: device)
    }

    /// A local change, recorded as the app does after each edit.
    mutating func edit(_ change: (inout Cellar) throws -> Void) rethrows {
        let before = cellar
        try change(&cellar)
        state.record(from: before, to: cellar)
    }

    /// Receives another device's copy.
    mutating func sync(with other: Device) {
        let result = CellarMerge.merge(local: cellar, state, remote: other.cellar, other.state)
        cellar = result.cellar
        state.stamps = result.stamps
        state.clock = result.clock
    }
}

/// A and B start from the same cellar (B joined A), then both sync both ways.
private func pair(_ cellar: Cellar) -> (Device, Device) {
    let a = Device(cellar)
    var b = Device(Cellar())
    b.sync(with: a)
    return (a, b)
}

private func syncBoth(_ a: inout Device, _ b: inout Device) {
    let copyA = a
    a.sync(with: b)
    b.sync(with: copyA)
}

@Suite struct SyncTests {
    private func sample() throws -> (Cellar, Wine, Rack) {
        var cellar = Cellar()
        let rack = Rack(name: "Main", rows: 2, columns: 3)
        cellar.addRack(rack)
        let wine = Wine(name: "Clos", vintage: 2017, color: .red)
        try cellar.addBottles(of: wine, quantity: 2, slots: [Slot(rackID: rack.id, row: 0, column: 0)])
        return (cellar, wine, rack)
    }

    @Test func joiningDeviceGetsTheWholeCellar() throws {
        let (cellar, _, _) = try sample()
        let (a, b) = pair(cellar)
        #expect(b.cellar == a.cellar)
    }

    @Test func editsOnBothSidesMergeAndConverge() throws {
        let (cellar, wine, _) = try sample()
        var (a, b) = pair(cellar)
        try a.edit { var w = $0.wines[wine.id]!; w.region = "Bordeaux"; try $0.updateWine(w) }
        try b.edit { try $0.addBottles(of: Wine(name: "Other"), quantity: 1) }
        syncBoth(&a, &b)
        #expect(a.cellar == b.cellar)
        #expect(a.cellar.wines[wine.id]?.region == "Bordeaux")
        #expect(a.cellar.bottles.count == 3)
    }

    @Test func theLaterEditOfTheSameWineWins() throws {
        let (cellar, wine, _) = try sample()
        var (a, b) = pair(cellar)
        try a.edit { var w = $0.wines[wine.id]!; w.notes = "first"; try $0.updateWine(w) }
        // B has seen A's change, so its edit comes later.
        b.sync(with: a)
        try b.edit { var w = $0.wines[wine.id]!; w.notes = "second"; try $0.updateWine(w) }
        syncBoth(&a, &b)
        #expect(a.cellar.wines[wine.id]?.notes == "second")
        #expect(a.cellar == b.cellar)
    }

    @Test func concurrentEditsPickTheSameWinnerOnBothDevices() throws {
        let (cellar, wine, _) = try sample()
        var (a, b) = pair(cellar)
        try a.edit { var w = $0.wines[wine.id]!; w.notes = "from A"; try $0.updateWine(w) }
        try b.edit { var w = $0.wines[wine.id]!; w.notes = "from B"; try $0.updateWine(w) }
        syncBoth(&a, &b)
        #expect(a.cellar == b.cellar)
        #expect(["from A", "from B"].contains(a.cellar.wines[wine.id]?.notes ?? ""))
    }

    @Test func aDeletionSpreads() throws {
        let (cellar, wine, _) = try sample()
        var (a, b) = pair(cellar)
        a.edit { $0.deleteWine(wine.id) }
        syncBoth(&a, &b)
        #expect(b.cellar.wines[wine.id] == nil)
        #expect(b.cellar.bottles.isEmpty)
        // And does not come back on the next sync.
        syncBoth(&a, &b)
        #expect(a.cellar.wines.isEmpty && b.cellar.wines.isEmpty)
    }

    @Test func editingABottleOfADeletedWineDoesNotLeaveAnOrphan() throws {
        let (cellar, wine, _) = try sample()
        var (a, b) = pair(cellar)
        let bottle = try #require(cellar.bottles.values.first { $0.slot == nil })
        a.edit { $0.deleteWine(wine.id) }
        try b.edit { try $0.markGone(bottle.id) }
        syncBoth(&a, &b)
        #expect(a.cellar == b.cellar)
        #expect(a.cellar.bottles.values.allSatisfy { a.cellar.wines[$0.wineID] != nil })
    }

    @Test func theSameBottleDrunkOnBothDevicesStaysOneBottle() throws {
        let (cellar, _, _) = try sample()
        var (a, b) = pair(cellar)
        let bottle = try #require(cellar.bottles.values.first)
        try a.edit { try $0.drink(bottle.id, tasting: Tasting(rating: 8)) }
        try b.edit { try $0.drink(bottle.id, tasting: Tasting(rating: 6)) }
        syncBoth(&a, &b)
        #expect(a.cellar == b.cellar)
        #expect(a.cellar.consumedBottles.count == 1)
        #expect(a.cellar.bottlesInCellar.count == 1)
    }

    @Test func twoBottlesPutInOnePlaceKeepOnlyOneThere() throws {
        let (cellar, _, rack) = try sample()
        var (a, b) = pair(cellar)
        let place = Slot(rackID: rack.id, row: 1, column: 1)
        try a.edit { try $0.addBottles(of: Wine(name: "From A"), quantity: 1, slots: [place]) }
        try b.edit { try $0.addBottles(of: Wine(name: "From B"), quantity: 1, slots: [place]) }
        syncBoth(&a, &b)
        #expect(a.cellar == b.cellar)
        #expect(a.cellar.bottlesInCellar.filter { $0.slot == place }.count == 1)
        #expect(a.cellar.bottlesInCellar.count == 4, "the other bottle stays in the cellar, without a place")
    }

    @Test func bottlesOfARemovedRackLoseTheirPlace() throws {
        let (cellar, _, rack) = try sample()
        var (a, b) = pair(cellar)
        a.edit { $0.removeRack(rack.id) }
        // B moves a bottle within that rack meanwhile.
        let placed = try #require(cellar.bottles.values.first { $0.slot != nil })
        try b.edit { try $0.move(placed.id, to: Slot(rackID: rack.id, row: 1, column: 2)) }
        syncBoth(&a, &b)
        #expect(a.cellar == b.cellar)
        #expect(a.cellar.racks.isEmpty)
        #expect(a.cellar.bottles.values.allSatisfy { $0.slot == nil })
    }

    @Test func rackOrderAndSettingsFollowTheLaterChange() throws {
        let (cellar, _, _) = try sample()
        var (a, b) = pair(cellar)
        a.edit { $0.addRack(Rack(name: "Second", rows: 1, columns: 1)) }
        syncBoth(&a, &b)
        b.edit { $0.moveRacks(fromOffsets: IndexSet(integer: 1), toOffset: 0) }
        a.edit { $0.settings.currency = "CHF" }
        syncBoth(&a, &b)
        #expect(a.cellar.racks.map(\.name) == ["Second", "Main"])
        #expect(b.cellar.settings.currency == "CHF")
        #expect(a.cellar == b.cellar)
    }

    @Test func twoCellarsThatNeverSyncedCombine() throws {
        let (cellarA, _, _) = try sample()
        var cellarB = Cellar()
        try cellarB.addBottles(of: Wine(name: "Only on B"), quantity: 3)
        var a = Device(cellarA)
        var b = Device(cellarB)
        syncBoth(&a, &b)
        #expect(a.cellar == b.cellar)
        #expect(a.cellar.bottles.count == 5)
    }

    @Test func mergeIsTheSameWhicheverDeviceRunsIt() throws {
        let (cellar, wine, rack) = try sample()
        var (a, b) = pair(cellar)
        try a.edit { var w = $0.wines[wine.id]!; w.notes = "A"; try $0.updateWine(w) }
        try a.edit { try $0.addBottles(of: Wine(name: "A wine"), quantity: 1, slots: [Slot(rackID: rack.id, row: 0, column: 2)]) }
        try b.edit { try $0.addBottles(of: Wine(name: "B wine"), quantity: 1, slots: [Slot(rackID: rack.id, row: 0, column: 2)]) }
        b.edit { $0.settings.currency = "USD" }
        let ab = CellarMerge.merge(local: a.cellar, a.state, remote: b.cellar, b.state)
        let ba = CellarMerge.merge(local: b.cellar, b.state, remote: a.cellar, a.state)
        #expect(ab.cellar == ba.cellar)
        #expect(ab.stamps == ba.stamps)
    }

    @Test func syncStateRoundTripsThroughAFile() throws {
        let (cellar, _, _) = try sample()
        let state = SyncState.starting(with: cellar)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("sync-\(UUID()).json")
        let file = SyncStateFile(fileURL: url)
        try file.save(state)
        #expect(try file.load() == state)
        file.delete()
        #expect(try file.load() == nil)
    }

    @Test func aLocalChangeAfterASyncIsNewerThanEverythingReceived() throws {
        let (cellar, wine, _) = try sample()
        var (a, b) = pair(cellar)
        // B made many changes: its clock is far ahead.
        for index in 0..<20 {
            try b.edit { var w = $0.wines[wine.id]!; w.notes = "B \(index)"; try $0.updateWine(w) }
        }
        a.sync(with: b)
        try a.edit { var w = $0.wines[wine.id]!; w.notes = "A after sync"; try $0.updateWine(w) }
        let key = SyncKey(.wine, wine.id).string
        #expect(a.state.stamps[key]!.counter > b.state.clock)
        syncBoth(&a, &b)
        #expect(b.cellar.wines[wine.id]?.notes == "A after sync")
    }
}
