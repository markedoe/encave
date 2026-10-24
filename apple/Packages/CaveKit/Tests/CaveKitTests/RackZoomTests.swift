import Foundation
import Testing
@testable import CaveKit

@Suite struct RackZoomTests {
    /// A storage that scrolls is already at the minimum: zooming out has no effect,
    /// so the zoom cannot go below 1 (Zoom Out disabled).
    @Test func scrollingStorageCannotZoomOut() {
        let low = RackZoom.minimum(base: 44, minimum: 44)
        #expect(low == 1)
        #expect(RackZoom.clamped(0.6, minimum: low) == 1)
        #expect(RackZoom.cellSize(base: 44, minimum: 44, zoom: 0.85) == RackZoom.cellSize(base: 44, minimum: 44, zoom: 1))
    }

    /// A fitted storage with large places zooms out until its places reach the minimum.
    @Test func fittedStorageZoomsOutToTheMinimum() {
        let low = RackZoom.minimum(base: 60, minimum: 44)
        #expect(abs(low - 44.0 / 60.0) < 0.0001)
        #expect(RackZoom.cellSize(base: 60, minimum: 44, zoom: low) == 44)
        // Every zoom from the minimum up changes the drawing.
        #expect(RackZoom.cellSize(base: 60, minimum: 44, zoom: low + RackZoom.step) > 44)
        // Very large places: no further than the lowest zoom.
        #expect(RackZoom.minimum(base: 200, minimum: 44) == RackZoom.lowest)
        #expect(RackZoom.clamped(5, minimum: low) == RackZoom.maximum)
    }
}
