package app.encave.core.sync

import app.encave.core.Cellar
import app.encave.core.InstantSerializer
import app.encave.core.Wine
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reads a cellar, sync state and device list written the way CaveKit's
 * `JSONEncoder.sync` writes them (sorted keys, escaped slashes, uppercase UUIDs,
 * dictionaries keyed by UUID as alternating arrays, dates as seconds since 2001),
 * merges them with an Android device's copy, and checks that what Android writes
 * back reads as the same JSON.
 */
class SwiftSyncInteropTest {
    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/$name")!!.readBytes()

    private val iphone = UUID.fromString("a1b2c3d4-0000-4000-8000-000000000001")
    private val android = UUID.fromString("0b2c3d4e-0000-4000-8000-000000000002")
    private val wine = UUID.fromString("5e1f0000-0000-4000-8000-000000000011")

    @Test
    fun readsWhatSwiftWrites() {
        val cellar = SyncJson.decodeCellar(resource("swift-sync-cellar.json"))
        val state = SyncJson.decode(SyncState.serializer(), resource("swift-sync-state.json"))
        val members = SyncJson.decode(Membership.serializer(), resource("swift-sync-members.json"))

        assertEquals(iphone, state.device)
        assertEquals(4L, state.clock)
        assertEquals(Stamp(1, iphone), state.stamps["wine:$wine"])
        assertEquals(listOf("3760123456789"), cellar.wines[wine]?.barcodes)
        assertEquals(listOf("Gift", "for summer"), cellar.wines[wine]?.tags)
        assertEquals(listOf("Gift", "x"), app.encave.core.Wine.normalizedTags(listOf(" Gift ", "", "gift", "x")))
        assertEquals("1/2 bottle", cellar.wines[wine]?.notes)
        assertEquals("Cave/left wall", cellar.racks.single().name)
        val ordered = cellar.bottles.values.single { it.status == app.encave.core.Bottle.Status.Ordered }
        assertEquals(InstantSerializer.fromReferenceSeconds(783_000_000.0), ordered.expectedAt)
        // Ordered bottles are not in the cellar yet.
        assertEquals(1, cellar.bottlesInCellar.size)
        val bottle = cellar.bottlesInCellar.single()
        assertEquals(InstantSerializer.fromReferenceSeconds(782_000_000.25), bottle.addedAt)
        assertEquals(Instant.parse("2025-10-12T22:13:20.250Z"), bottle.addedAt)
        assertArrayEquals(ByteArray(32) { -1 }, members.members[iphone]?.publicKey)
        assertEquals(UUID.fromString("c0ffee00-0000-4000-8000-0000000000aa"), members.cellarId)
    }

    @Test
    fun anUnknownBottleStatusIsRefused() {
        val text = resource("swift-sync-cellar.json").decodeToString().replace("\"ordered\"", "\"lost-in-transit\"")
        assertTrue(runCatching { SyncJson.decodeCellar(text.toByteArray()) }.isFailure)
    }

    @Test
    fun writesWhatSwiftReads() {
        for ((name, rewrite) in listOf<Pair<String, (ByteArray) -> ByteArray>>(
            "swift-sync-cellar.json" to { SyncJson.encodeCellar(SyncJson.decodeCellar(it)) },
            "swift-sync-state.json" to { SyncJson.encode(SyncState.serializer(), SyncJson.decode(SyncState.serializer(), it)) },
            "swift-sync-members.json" to { SyncJson.encode(Membership.serializer(), SyncJson.decode(Membership.serializer(), it)) },
        )) {
            val original = resource(name)
            val written = rewrite(original)
            // Same keys, values and numbers; only the escaping of "/" differs.
            assertEquals(Json.parseToJsonElement(original.decodeToString()), Json.parseToJsonElement(written.decodeToString()), name)
        }
    }

    @Test
    fun mergesWithAnAndroidCopy() {
        val swiftCellar = SyncJson.decodeCellar(resource("swift-sync-cellar.json"))
        val swiftState = SyncJson.decode(SyncState.serializer(), resource("swift-sync-state.json"))
        val swiftMembers = SyncJson.decode(Membership.serializer(), resource("swift-sync-members.json"))

        // The Android phone joined the cellar, then added a bottle of its own.
        val (identity, _) = DeviceIdentity.generate(android)
        val (members, clock) = swiftMembers.adding(Member(android, "Pixel", identity.publicKey, "android"), swiftState.clock, android)
        val androidCellar = Cellar()
        var androidState = SyncState(device = android, clock = clock)
        val before = androidCellar.copy()
        androidCellar.addBottles(Wine(name = "From Android"), 1)
        androidState = androidState.recording(before, androidCellar)

        val onAndroid = CellarMerge.merge(androidCellar, androidState, swiftCellar, swiftState)
        val oniPhone = CellarMerge.merge(swiftCellar, swiftState, androidCellar, androidState)
        assertEquals(onAndroid.cellar, oniPhone.cellar)
        assertEquals(onAndroid.stamps, oniPhone.stamps)
        assertEquals(2, onAndroid.cellar.wines.size)
        assertNotNull(onAndroid.cellar.wines[wine])
        assertTrue(onAndroid.cellar.bottles.values.any { it.slot != null })

        val merged = swiftMembers.merged(members)
        assertEquals(setOf(iphone, android), merged.members.keys)
        assertEquals(merged, members.merged(swiftMembers))
    }
}
