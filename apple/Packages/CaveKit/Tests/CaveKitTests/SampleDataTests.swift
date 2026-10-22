import Foundation
import Testing
@testable import CaveKit

@Suite struct SampleDataTests {
    @Test func removingSampleKeepsOwnBottlesAndRacks() throws {
        var labels = SampleData.Labels()
        labels.mainRack = "Casier principal"
        var cellar = SampleData.cellar(year: 2026, labels: labels)
        #expect(cellar.containsSample)

        // The user's own rack and bottle, one of them in a sample rack.
        let own = Rack(name: "Garage", rows: 2, columns: 2)
        cellar.addRack(own)
        let mine = Wine(name: "Mon vin", producer: "Moi", color: .red)
        let main = try #require(cellar.racks.first { $0.name == "Casier principal" })
        let free = try #require(cellar.freeSlots(in: main).first)
        _ = try cellar.addBottles(of: mine, quantity: 1, slots: [free])
        _ = try cellar.addBottles(of: Wine(name: "Autre", producer: "Moi", color: .white), quantity: 1,
                                  slots: [Slot(rackID: own.id, row: 0, column: 0)])

        let cleaned = cellar.removingSample(labels: [labels])
        #expect(!cleaned.containsSample)
        #expect(Set(cleaned.wines.values.map(\.name)) == ["Mon vin", "Autre"])
        #expect(cleaned.bottles.count == 2)
        #expect(cleaned.discoveries.isEmpty)
        // The sample rack holding the user's bottle stays, with it; the empty ones go.
        #expect(Set(cleaned.racks.map(\.name)) == ["Casier principal", "Garage"])
        #expect(cleaned.bottles.values.allSatisfy { $0.slot != nil })
    }

    @Test func ownRackWithASampleNameButAnotherLayoutStays() {
        let rack = Rack(name: "Main rack", rows: 3, columns: 3)
        let cellar = Cellar(racks: [rack])
        #expect(!cellar.containsSample)
        #expect(cellar.removingSample().racks.map(\.id) == [rack.id])
    }
}
