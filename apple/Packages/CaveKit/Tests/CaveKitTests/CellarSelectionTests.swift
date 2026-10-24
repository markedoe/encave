import Foundation
import Testing
@testable import CaveKit

/// Select mode in the cellar: a wine the search hides leaves the selection.
@Suite struct CellarSelectionTests {
    let cellar = SampleData.cellar(year: 2026)

    @Test func searchUnpicksHiddenWines() throws {
        let all = cellar.groups(filter: CellarFilter(), sort: .name, year: 2026)
        let picked = Set(all.map(\.wine.id))
        let red = try #require(all.first { $0.wine.color == .red })
        let searched = cellar.groups(filter: CellarFilter(query: red.wine.name), sort: .name, year: 2026)
        let visible = searched.visible(picked)
        #expect(visible.contains(red.wine.id))
        #expect(visible.count < picked.count)
        #expect(visible.isSubset(of: Set(searched.map(\.wine.id))))
    }

    @Test func noGroupsUnpicksEverything() {
        let groups: [WineGroup] = []
        #expect(groups.visible([UUID()]).isEmpty)
    }
}
