import Foundation
import Testing
@testable import CaveKit

@Suite struct ServingAdviceTests {
    @Test func youngFullRedIsDecanted() throws {
        let wine = Wine(name: "W", vintage: 2022, color: .red, region: "Bordeaux")
        let advice = try #require(ServingAdvice.advice(for: wine, year: 2026))
        #expect(advice.minTemperature == 16)
        #expect(advice.decantMinutes == 60)
    }

    @Test func lightRedIsCooler() throws {
        let advice = try #require(ServingAdvice.advice(for: Wine(name: "W", color: .red, grapes: ["Pinot Noir"]), year: 2026))
        #expect(advice.maxTemperature == 16)
        #expect(advice.decantMinutes == nil)
    }

    @Test func sparklingIsColdAndUnknownStyleHasNoAdvice() throws {
        #expect(try #require(ServingAdvice.advice(for: Wine(name: "W", color: .sparkling), year: 2026)).maxTemperature == 8)
        #expect(ServingAdvice.advice(for: Wine(name: "W"), year: 2026) == nil)
    }

    #if canImport(Darwin)
    /// The unit once, after the range, abbreviated ("16 – 18 °C", not "16° – 18°").
    @Test(arguments: [("fr_FR", "16", "18", "C"), ("en_GB", "16", "18", "C"), ("en_US", "61", "64", "F")])
    func temperatureRangeShowsTheUnitOnce(locale: String, low: String, high: String, unit: String) {
        let text = ServingAdvice(minTemperature: 16, maxTemperature: 18).temperatureRange(locale: Locale(identifier: locale))
        #expect(text.filter { $0 == "°" }.count == 1, "\(text)")
        #expect(text.hasPrefix(low + " – "), "\(text)")
        #expect(text.contains(high), "\(text)")
        #expect(text.hasSuffix(unit), "\(text)")
    }
    #endif
}
