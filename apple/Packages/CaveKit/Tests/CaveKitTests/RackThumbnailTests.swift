import Testing
@testable import CaveKit

@Suite struct RackThumbnailTests {
    /// Layers [3, 3, 3, 1]: shelves 1 and 2 fit in eight rows; shelf 4 is not shown without shelf 3.
    @Test func stopsAtTheFirstShelfThatDoesNotFit() {
        let rack = Rack(name: "F", kind: .staggered, rows: 10, columns: 6, shelfLayers: [3, 3, 3, 1])
        #expect(rack.thumbnailShelves() == [0..<3, 3..<6])
    }

    @Test func showsAllShelvesThatFit() {
        let rack = Rack(name: "F", kind: .staggered, rows: 4, columns: 6, shelfLayers: [2, 1, 1])
        #expect(rack.thumbnailShelves() == [0..<2, 2..<3, 3..<4])
    }

    @Test func keepsTheTopShelfWhenNoneFits() {
        let rack = Rack(name: "F", kind: .staggered, rows: 4, columns: 6, shelfLayers: [3, 1])
        #expect(rack.thumbnailShelves(layers: 2) == [0..<3])
    }
}
