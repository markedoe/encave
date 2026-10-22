import Testing
@testable import CaveKit

struct StudioRandomTests {
    /// The same vector is in StudioRandomTest.kt: a wine's backdrop is the same on both platforms.
    @Test func sameNumbersOnEveryPlatform() {
        var random = StudioRandom(seed: "8F0E3C1A-0000-4000-8000-000000000001")
        let expected = [0.086439285462, 0.119454450003, 0.49856282913]
        for value in expected { #expect(abs(random.next() - value) < 1e-9) }
        var lower = StudioRandom(seed: "8f0e3c1a-0000-4000-8000-000000000001")
        #expect(abs(lower.next() - expected[0]) < 1e-9)
    }
}
