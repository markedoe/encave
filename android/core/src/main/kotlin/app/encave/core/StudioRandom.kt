package app.encave.core

/**
 * The random numbers that place the lights of a wine's studio backdrop:
 * SplitMix64 seeded with the FNV-1a hash of the lowercased identifier, so a wine
 * keeps the same backdrop at every launch and on every platform (the same in
 * StudioRandom.swift; shared test vector in the tests of both).
 */
class StudioRandom(seed: String) {
    private var state: ULong

    init {
        var hash = 0xCBF29CE484222325uL
        for (byte in seed.lowercase().toByteArray(Charsets.UTF_8)) {
            hash = hash xor byte.toUByte().toULong()
            hash *= 0x100000001B3uL
        }
        state = hash
    }

    /** A number from 0 up to 1. */
    fun next(): Double {
        state += 0x9E3779B97F4A7C15uL
        var z = state
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        z = z xor (z shr 31)
        return (z shr 11).toDouble() / 9007199254740992.0
    }
}
