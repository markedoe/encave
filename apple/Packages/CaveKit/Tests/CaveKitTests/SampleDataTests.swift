import CaveKit
import Foundation
import Testing

@Suite struct SampleDataTests {
    /// The same vectors as SampleDataTest.kt: both apps give the sample the same IDs.
    @Test func idsAreNameBasedAndShared() {
        #expect(SampleData.id(.rack, 0) == UUID(uuidString: "422F32A7-FFA7-5722-A7A5-F3C9D551EC1E"))
        #expect(SampleData.id(.wine, 0) == UUID(uuidString: "32C7F6A8-BA2B-58E2-A2F4-9C88521301B0"))
        #expect(SampleData.id(.bottle, 46) == UUID(uuidString: "62DF80CD-D7CD-5B64-841D-B720F469ABD8"))
        #expect(SampleData.id(.discovery, 1) == UUID(uuidString: "7AEB571F-970E-561A-AB7A-4378771FC488"))
    }

    @Test func everySampleRecordHasAFixedID() {
        let sample = SampleData.cellar(year: 2026)
        #expect(sample.isOnlySample)
        #expect(sample.containsSample)
        #expect(sample.racks.first?.id == SampleData.id(.rack, 0))
        #expect(sample.racks.count == 8)
        #expect(sample.wines.count == 15)
        #expect(sample.bottles.count == 63)
        #expect(sample.discoveries.count == 2)
        // Built twice, the same records.
        let again = SampleData.cellar(year: 2026)
        #expect(Set(again.bottles.keys) == Set(sample.bottles.keys))
        #expect(Set(again.wines.keys) == Set(sample.wines.keys))
        #expect(!Cellar().isOnlySample)
        #expect(!Cellar().containsSample)
    }

    @Test func removingTheSampleKeepsTheUsersRecords() throws {
        var cellar = SampleData.cellar(year: 2026)
        let sampleRack = cellar.racks[0]
        let sampleWine = try #require(cellar.wines.values.first { $0.name == "Cuvée du Lac" })
        // The user's own wine, placed in a sample rack, and a bottle of a sample wine.
        let own = Wine(name: "Own wine")
        let slot = try #require(cellar.freeSlots(in: sampleRack).first)
        try cellar.addBottles(of: own, quantity: 1, slots: [slot])
        try cellar.addBottles(of: sampleWine, quantity: 1)
        let ownRack = Rack(name: "Own rack", rows: 2, columns: 2)
        cellar.addRack(ownRack)
        #expect(!cellar.isOnlySample)
        #expect(cellar.containsSample)

        let removed = cellar.removingSample()
        #expect(!removed.containsSample || removed.wines[sampleWine.id] != nil)
        #expect(removed.bottles.count == 2)
        #expect(removed.wines.keys.sorted { $0.uuidString < $1.uuidString }
            == [own.id, sampleWine.id].sorted { $0.uuidString < $1.uuidString })
        // The sample rack holding the user's bottle stays; the other sample racks go.
        #expect(removed.racks.map(\.id) == [sampleRack.id, ownRack.id])
        #expect(removed.discoveries.isEmpty)
        #expect(removed.settings == cellar.settings)

        #expect(SampleData.cellar(year: 2026).removingSample().isEmpty)
    }
}
