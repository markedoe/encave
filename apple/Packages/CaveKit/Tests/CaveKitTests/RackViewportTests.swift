import Foundation
#if canImport(CoreGraphics)
import CoreGraphics
#endif
import Testing
@testable import CaveKit

/// Shared with the Kotlin RackViewportTest: the same cases and numbers.
@Suite struct RackViewportTests {
    @Test func everyStyleHasItsOwnSymbol() {
        let marks = WineColor.allCases.map(\.styleMark)
        #expect(Set(marks).count == WineColor.allCases.count, "two styles share a mark")
        #expect(Set(marks.map(\.systemImage)).count == WineColor.allCases.count, "two styles share a symbol")
        // The same choice as on Android.
        #expect(WineColor.red.styleMark == .glass)
        #expect(WineColor.white.styleMark == .sun)
        #expect(WineColor.rose.styleMark == .heart)
        #expect(WineColor.orange.styleMark == .leaf)
        #expect(WineColor.sparkling.styleMark == .bubbles)
        #expect(WineColor.dessert.styleMark == .drop)
        #expect(WineColor.fortified.styleMark == .shield)
    }

    @Test func placesAreNeverSmallerThanATouchTarget() {
        let grid = Rack(name: "G", rows: 4, columns: 6)
        #expect(RackViewport.minimumCellSize(for: grid, target: 44) == 44)
        // A fridge drawn front and back: its back places are 0.78 of a cell tall.
        let fridge = Rack(name: "F", kind: .staggered, rows: 2, columns: 4, depth: 2, shelfLayers: [2])
        #expect(abs(RackViewport.minimumCellSize(for: fridge, target: 44) - 44 / 0.78) < 0.001)
        #expect(abs(RackViewport.minimumCellSize(for: fridge, target: 48) - 48 / 0.78) < 0.001)
    }

    @Test func fitsWhenEveryPlaceReachesTheMinimumElseScrolls() {
        #expect(!RackViewport.scrolls(fitted: 60, minimum: 44, nearFit: 40))
        #expect(RackViewport.cellSize(fitted: 60, minimum: 44, nearFit: 40) == 60)
        #expect(!RackViewport.scrolls(fitted: 44, minimum: 44, nearFit: 40))
        #expect(RackViewport.scrolls(fitted: 30, minimum: 44, nearFit: 40))
        #expect(RackViewport.cellSize(fitted: 30, minimum: 44, nearFit: 40) == 44)
    }

    @Test func aStorageSlightlyTooLargeIsFittedRatherThanScrolledByAFewPoints() {
        // 8 cells of 48 in a little less: 7 % too wide at the minimum, places of 45: fitted.
        #expect(!RackViewport.scrolls(fitted: 45, minimum: 48, nearFit: 40))
        #expect(RackViewport.cellSize(fitted: 45, minimum: 48, nearFit: 40) == 45)
        // 10 % exactly: still fitted; more: scrolls.
        #expect(!RackViewport.scrolls(fitted: 40, minimum: 44, nearFit: 40))
        #expect(RackViewport.scrolls(fitted: 43, minimum: 48, nearFit: 40))
        // Places would be under 40: scrolls, however close.
        #expect(RackViewport.scrolls(fitted: 39.5, minimum: 42, nearFit: 40))
        #expect(RackViewport.cellSize(fitted: 39.5, minimum: 42, nearFit: 40) == 42)
    }

    @Test func aStorageThatScrollsOpensAtItsTopLeadingCorner() {
        let offset = RackViewport.offset(centering: RackViewport.start, content: CGSize(width: 1000, height: 800),
                                         view: CGSize(width: 300, height: 400))
        #expect(offset == .zero)
        #expect(RackViewport.visibleArea(content: CGSize(width: 1000, height: 800), view: CGSize(width: 300, height: 400), offset: offset)
            == CGRect(x: 0, y: 0, width: 0.3, height: 0.5))
    }

    @Test func visibleAreaIsAFractionOfTheDrawing() {
        let content = CGSize(width: 1000, height: 500)
        #expect(RackViewport.visibleArea(content: content, view: CGSize(width: 250, height: 500), offset: CGPoint(x: 500, y: 0))
            == CGRect(x: 0.5, y: 0, width: 0.25, height: 1))
        // Past the end (bouncing): clipped to the drawing.
        let past = RackViewport.visibleArea(content: content, view: CGSize(width: 250, height: 250), offset: CGPoint(x: 900, y: -50))
        #expect(abs(past.minX - 0.9) < 1e-9 && abs(past.width - 0.1) < 1e-9 && past.minY == 0 && abs(past.height - 0.4) < 1e-9)
        // Smaller than the view: all of it.
        #expect(RackViewport.visibleArea(content: CGSize(width: 100, height: 100), view: CGSize(width: 250, height: 250), offset: .zero)
            == CGRect(x: 0, y: 0, width: 1, height: 1))
        #expect(RackViewport.visibleArea(content: .zero, view: CGSize(width: 250, height: 250), offset: .zero)
            == CGRect(x: 0, y: 0, width: 1, height: 1))
    }

    @Test func tappingTheOverviewCentersThatSpot() {
        let content = CGSize(width: 1000, height: 500), view = CGSize(width: 250, height: 250)
        #expect(RackViewport.offset(centering: CGPoint(x: 0.5, y: 0.5), content: content, view: view) == CGPoint(x: 375, y: 125))
        // Near the edges: as far as the drawing goes.
        #expect(RackViewport.offset(centering: .zero, content: content, view: view) == .zero)
        #expect(RackViewport.offset(centering: CGPoint(x: 1, y: 1), content: content, view: view) == CGPoint(x: 750, y: 250))
        // An axis that fits does not move.
        #expect(RackViewport.offset(centering: CGPoint(x: 0.7, y: 0.7), content: CGSize(width: 1000, height: 200), view: view)
            == CGPoint(x: 575, y: 0))
    }

    @Test func centerOfAPlace() {
        let grid = Rack(name: "G", rows: 4, columns: 6)
        let cell = RackCell(rackID: grid.id, row: 1, column: 2)
        #expect(RackViewport.center(of: cell, in: grid) == CGPoint(x: 2.5 / 6, y: 1.5 / 4))
        // Head-to-tail fridge: shelf 1 (layers 0, 1) then shelf 2 (layer 2), each front then back,
        // the back half a place right.
        let fridge = Rack(name: "F", kind: .staggered, rows: 3, columns: 6, depth: 2, shelfLayers: [2, 1], backReversed: true)
        let back = RackViewport.center(of: RackCell(rackID: fridge.id, row: 1, column: 2), depth: 1, in: fridge)
        #expect(abs(back.x - 3.0 / 7) < 0.0001)
        #expect(abs(back.y - 3.5 / 6) < 0.0001)
        let front = RackViewport.center(of: RackCell(rackID: fridge.id, row: 2, column: 0), depth: 0, in: fridge)
        #expect(abs(front.x - 0.5 / 7) < 0.0001)
        #expect(abs(front.y - 4.5 / 6) < 0.0001)
    }
}
