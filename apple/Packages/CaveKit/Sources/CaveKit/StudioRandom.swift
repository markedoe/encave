import Foundation

/// The random numbers that place the lights of a wine's studio backdrop:
/// SplitMix64 seeded with the FNV-1a hash of the lowercased identifier, so a wine
/// keeps the same backdrop at every launch and on every platform (the same in
/// StudioRandom.kt; shared test vector in the tests of both).
public struct StudioRandom: Sendable {
    private var state: UInt64

    public init(seed: String) {
        var hash: UInt64 = 0xCBF2_9CE4_8422_2325
        for byte in seed.lowercased().utf8 {
            hash ^= UInt64(byte)
            hash = hash &* 0x0000_0100_0000_01B3
        }
        state = hash
    }

    /// A number from 0 up to 1.
    public mutating func next() -> Double {
        state = state &+ 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        z ^= z >> 31
        return Double(z >> 11) / 9_007_199_254_740_992
    }
}
