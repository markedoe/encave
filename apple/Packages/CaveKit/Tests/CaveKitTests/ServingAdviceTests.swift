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
}
