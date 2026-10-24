import Foundation
import Testing
@testable import CaveKit

@Suite struct StorageSetupTests {
    @Test func gridRack() {
        var settings = StorageSettings(type: .rack)
        settings.rows = 6; settings.columns = 8; settings.depth = 2
        let rack = settings.rack(name: "Rack")
        #expect(rack.kind == .grid && rack.rows == 6 && rack.columns == 8 && rack.depth == 2)
        #expect(rack.capacity == 96 && rack.shelfLayers == nil && rack.backReversed == nil)
        #expect(rack.storageType == .rack && rack.storageCount == 6)
    }

    @Test func honeycombRackIsContinuous() {
        var settings = StorageSettings(type: .rack)
        settings.layout = .honeycomb; settings.rows = 5; settings.columns = 8; settings.depth = 3
        let rack = settings.rack(name: "Honeycomb")
        #expect(rack.kind == .staggered && rack.shelfLayers == nil)
        #expect(rack.depth == 2, "a honeycomb holds two bottles deep at most")
        #expect(rack.storageType == .rack)
        #expect(StorageSettings(rack: rack).layout == .honeycomb)
    }

    @Test func diamondBinsRack() {
        var settings = StorageSettings(type: .rack)
        settings.layout = .diamond; settings.rows = 2; settings.columns = 3; settings.bottlesPerBin = 10
        let rack = settings.rack(name: "Diamonds")
        #expect(rack.kind == .bins && rack.binCapacity == 10 && rack.capacity == 60)
        // Diamond bins stay a rack once saved, counted in bins, and edit as they were made.
        #expect(rack.storageType == .rack && rack.storageCount == 6)
        #expect(StorageSettings(rack: rack) == settings)
        var defaults = StorageSettings(type: .rack)
        defaults.layout = .diamond
        #expect(defaults.rack(name: "").storageType == .rack)
    }

    @Test func cratesStayCrates() {
        for bins in 1...StorageSettings.maxCount {
            var settings = StorageSettings(type: .crates)
            settings.bins = bins
            let rack = settings.rack(name: "Crates")
            #expect(rack.storageType == .crates && rack.storageCount == bins, "\(bins) bins")
        }
    }

    @Test func fridgeWithOneRowPerShelfIsShelves() {
        var settings = StorageSettings(type: .wineFridge)
        settings.shelves = 4; settings.bottlesPerShelf = 7; settings.layersPerShelf = 1; settings.depth = 2
        let rack = settings.rack(name: "Fridge")
        #expect(rack.kind == .shelves && rack.rows == 4 && rack.columns == 7 && rack.depth == 2)
        #expect(rack.backReversed == nil, "head-to-tail needs layers")
        #expect(rack.storageType == .wineFridge && rack.storageCount == 4)
        #expect(StorageSettings(rack: rack) == settings.with { $0.headToTail = false })
    }

    @Test func fridgeInLayersIsAHoneycombOnShelves() {
        var settings = StorageSettings(type: .wineFridge)
        settings.shelves = 4; settings.bottlesPerShelf = 6; settings.layersPerShelf = 2; settings.depth = 2
        let rack = settings.rack(name: "Fridge")
        #expect(rack.kind == .staggered && rack.shelfLayers == [2, 2, 2, 2] && rack.rows == 8)
        // Each shelf: 5 on top resting in the gaps of 6, two deep.
        #expect((0..<rack.rows).map(rack.columns(inRow:)) == [5, 6, 5, 6, 5, 6, 5, 6])
        #expect(rack.capacity == 88 && rack.isBackReversed)
        #expect(rack.storageType == .wineFridge && rack.storageCount == 4)
        #expect(StorageSettings(rack: rack) == settings)
    }

    @Test func threeLayersFollowTheQuincunx() {
        var settings = StorageSettings(type: .wineFridge)
        settings.shelves = 1; settings.bottlesPerShelf = 6; settings.layersPerShelf = 3; settings.depth = 1
        let rack = settings.rack(name: "Fridge")
        #expect((0..<rack.rows).map(rack.columns(inRow:)) == [6, 5, 6])
    }

    @Test func headToTailOnlyTwoDeepWithLayers() {
        var settings = StorageSettings(type: .wineFridge)
        settings.headToTail = true
        settings.layersPerShelf = 2; settings.depth = 1
        #expect(!settings.offersHeadToTail && settings.rack(name: "").backReversed == nil)
        settings.layersPerShelf = 1; settings.depth = 2
        #expect(!settings.offersHeadToTail && settings.rack(name: "").backReversed == nil)
        settings.layersPerShelf = 2; settings.depth = 2
        #expect(settings.offersHeadToTail && settings.rack(name: "").backReversed == true)
        settings.headToTail = false
        #expect(settings.rack(name: "").backReversed == nil)
    }

    @Test func cratesFillRowsOfFour() {
        var settings = StorageSettings(type: .crates)
        settings.bins = 10; settings.bottlesPerBin = 6
        let rack = settings.rack(name: "Crates")
        #expect(rack.kind == .bins && rack.rows == 3 && rack.columns == 4 && rack.rowColumns == [4, 4, 2])
        #expect(rack.cellCount == 10 && rack.capacity == 60)
        #expect(rack.storageType == .crates && rack.storageCount == 10)
        settings.bins = 8
        #expect(settings.rack(name: "").rowColumns == nil && settings.rack(name: "").rows == 2)
        settings.bins = 3
        #expect(settings.rack(name: "").rows == 1 && settings.rack(name: "").columns == 3)
    }

    @Test func edgeValuesAreBounded() {
        var crates = StorageSettings(type: .crates)
        crates.bins = 0; crates.bottlesPerBin = 0
        #expect(crates.rack(name: "").cellCount == 1 && crates.rack(name: "").binCapacity == 1)
        crates.bins = 1000
        #expect(crates.rack(name: "").cellCount == StorageSettings.maxCount)
        var fridge = StorageSettings(type: .wineFridge)
        fridge.shelves = 500; fridge.layersPerShelf = 9; fridge.depth = 5; fridge.bottlesPerShelf = -3
        let rack = fridge.rack(name: "")
        #expect(rack.shelfLayers?.count == StorageSettings.maxShelves && rack.shelfLayers?.allSatisfy { $0 == 3 } == true)
        #expect(rack.rows <= Rack.maxSize && rack.depth == 2 && rack.columns == 1)
        var grid = StorageSettings(type: .rack)
        grid.rows = 0; grid.columns = 99; grid.depth = 3
        let small = grid.rack(name: "")
        #expect(small.rows == 1 && small.columns == StorageSettings.maxCount && small.depth == 2)
    }

    @Test func defaultsPerType() {
        #expect(StorageSettings(type: .wineFridge).rack(name: "").isBackReversed)
        #expect(StorageSettings(type: .crates).rack(name: "").binCapacity == 12)
        #expect(StorageSettings(type: .rack).rack(name: "").capacity == 48)
    }

    @Test func uniqueNames() {
        #expect(StorageSettings.uniqueName("Rack", among: []) == "Rack")
        #expect(StorageSettings.uniqueName("Rack", among: ["rack", "Rack 2"]) == "Rack 3")
        #expect(StorageSettings.uniqueName("Crates", among: ["Rack"]) == "Crates")
    }
}

private extension StorageSettings {
    func with(_ change: (inout StorageSettings) -> Void) -> StorageSettings {
        var copy = self
        change(&copy)
        return copy
    }
}
