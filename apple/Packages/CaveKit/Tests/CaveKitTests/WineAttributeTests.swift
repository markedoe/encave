import Foundation
import Testing
@testable import CaveKit

@Suite struct WineAttributeTests {
    let a = Wine(name: "Les Clos", producer: "Domaine X", vintage: 2015, color: .red, region: "Bourgogne",
                 country: "France", appellation: "Chablis", grapes: ["Pinot Noir", " Gamay "], tags: ["Gift"])
    let b = Wine(name: "Aubade", producer: "  domaine x ", vintage: 2015, color: .white, region: "bourgogne",
                 country: "FRANCE", appellation: "Meursault", grapes: ["gamay"], tags: ["gift "])
    let c = Wine(name: "Zéphyr", producer: "Domaine Y", vintage: 2018, color: .red, grapes: ["Syrah"])
    let ordered = Wine(name: "En primeur", producer: "Domaine X", vintage: 2022, color: .red)
    let drunk = Wine(name: "Bue", producer: "Domaine X", vintage: 2015, color: .red)

    var cellar: Cellar {
        Cellar(wines: [a, b, c, ordered, drunk], bottles: [
            Bottle(wineID: a.id), Bottle(wineID: a.id), Bottle(wineID: a.id, status: .ordered),
            Bottle(wineID: b.id),
            Bottle(wineID: c.id), Bottle(wineID: c.id),
            Bottle(wineID: ordered.id, status: .ordered), Bottle(wineID: ordered.id, status: .ordered),
            Bottle(wineID: drunk.id, status: .consumed), Bottle(wineID: drunk.id, status: .gone),
        ])
    }

    private func names(_ attribute: WineAttribute) -> [String] {
        cellar.wines(sharing: attribute).map(\.wine.name)
    }

    @Test func producerIgnoresCaseAndSpaces() {
        #expect(names(.producer("domaine X")) == ["Aubade", "En primeur", "Les Clos"])
        #expect(names(.producer(" Domaine Y")) == ["Zéphyr"])
    }

    @Test func regionCountryAppellation() {
        #expect(names(.region("BOURGOGNE")) == ["Aubade", "Les Clos"])
        #expect(names(.country("france ")) == ["Aubade", "Les Clos"])
        #expect(names(.appellation("chablis")) == ["Les Clos"])
    }

    @Test func eachGrapeOnItsOwn() {
        #expect(names(.grape("Gamay")) == ["Aubade", "Les Clos"])
        #expect(names(.grape("pinot noir")) == ["Les Clos"])
        #expect(names(.grape("Pinot")).isEmpty)
    }

    @Test func vintageColorAndTag() {
        #expect(names(.vintage(2015)) == ["Aubade", "Les Clos"])
        #expect(names(.color(.red)) == ["En primeur", "Les Clos", "Zéphyr"])
        #expect(names(.tag("GIFT")) == ["Aubade", "Les Clos"])
    }

    @Test func blankValueMatchesNothing() {
        #expect(names(.producer("  ")).isEmpty)
        #expect(names(.tag("")).isEmpty)
    }

    @Test func countsBottlesInTheCellarOrOnOrderOnly() {
        let groups = cellar.wines(sharing: .producer("Domaine X"))
        // Les Clos: its 2 bottles in the cellar (the one on order is left out of the row);
        // En primeur: its 2 bottles on order; Bue: no bottle left, not listed.
        #expect(groups.map(\.count) == [1, 2, 2])
        #expect(groups.reduce(0) { $0 + $1.count } == 5)
        let primeur = groups.first { $0.wine.id == ordered.id }
        #expect(primeur?.bottles.allSatisfy { $0.status == .ordered } == true)
        let clos = groups.first { $0.wine.id == a.id }
        #expect(clos?.bottles.allSatisfy { $0.status == .inCellar } == true)
    }

    @Test func wineHasAttribute() {
        #expect(a.has(.grape(" gamay")))
        #expect(!a.has(.vintage(2016)))
        #expect(!c.has(.region("Bourgogne")))
    }
}
