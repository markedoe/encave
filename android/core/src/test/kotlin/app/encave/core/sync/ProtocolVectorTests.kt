package app.encave.core.sync

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The published test vectors of docs/SYNC.md §10, computed here from the same fixed
 * inputs: matching them byte for byte is what lets an Android phone pair and sync
 * with an iPhone. Mirrors CaveKit's ProtocolVectorTests.swift.
 */
class ProtocolVectorTests {
    private fun bytes(start: Int) = ByteArray(32) { (start + it).toByte() }
    private fun key(start: Int) = X25519Key(bytes(start))

    private val hostDevice = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val joinerDevice = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val cellar = UUID.fromString("33333333-3333-4333-8333-333333333333")

    @Test
    fun vectors() {
        val hostEphemeral = key(0x01)
        val joinerEphemeral = key(0x41)
        val hostNonce = ByteArray(32) { 0xA0.toByte() }
        val joinerNonce = ByteArray(32) { 0xB0.toByte() }
        val hostKey = hostEphemeral.publicKey
        val joinerKey = joinerEphemeral.publicKey
        val commitment = PairingCrypto.commitment(hostKey, hostNonce)
        val pairing = PairingCrypto.keys(
            hostEphemeral, joinerKey, hostKey, joinerKey, hostNonce, joinerNonce,
            PeerInfo(hostDevice, "", ""), PeerInfo(joinerDevice, "", ""),
        )
        val initiatorIdentity = key(0x81)
        val responderIdentity = key(0xC1)
        val session = SyncHandshake.sessionKeys(
            initiator = hostDevice, responder = joinerDevice, initiatorKey = hostKey, responderKey = joinerKey,
            initiatorNonce = hostNonce, responderNonce = joinerNonce, cellar = cellar,
            ee = hostEphemeral.agree(joinerKey),
            es = hostEphemeral.agree(responderIdentity.publicKey),
            se = initiatorIdentity.agree(joinerKey),
        )
        val frame = ChaChaPoly.seal("""{"type":"photos-end"}""".toByteArray(), session.i2r, SecureChannel.nonce(0))

        val values = mapOf(
            "host ephemeral public" to hostKey.hex(),
            "joiner ephemeral public" to joinerKey.hex(),
            "commitment" to commitment.hex(),
            "confirmation code" to pairing.code,
            "pair host to joiner" to pairing.h2j.hex(),
            "pair joiner to host" to pairing.j2h.hex(),
            "sync auth" to session.auth.hex(),
            "sync initiator to responder" to session.i2r.hex(),
            "sync responder to initiator" to session.r2i.hex(),
            "first frame" to frame.hex(),
            "rendezvous 123456" to PairingCode("123456").rendezvous,
            "network tag" to Membership(cellarId = cellar).networkTag,
        )
        for ((name, value) in values) assertEquals(expected[name], value, name)
        // The frame a Kotlin device sends is the one the Swift code computed.
        assertEquals("""{"type":"photos-end"}""", WireMessage("photos-end").encoded().toString(Charsets.UTF_8))
    }

    /** Remote sync (SYNC.md §8): mailbox and inbox IDs, tokens, the data key, an envelope and a grant key. */
    @Test
    fun remoteVectors() {
        val cellarKey = CellarKey(cellar, 1, bytes(0x10))
        val sender = DeviceIdentity(hostDevice, bytes(0x81))
        val recipient = key(0xC1)
        val envelope = Envelope.seal("hello".toByteArray(), Envelope.Kind.Photo, "photo/x", cellarKey.dataKey, ByteArray(12) { 7 })
        val ephemeral = key(0x01)
        val grantPath = "grant/$cellar-00000002-$hostDevice-0a1b2c3d"
        val grantKey = KeyGrant.key(
            grantPath, ephemeral.publicKey, recipient.publicKey,
            ephemeral.agree(recipient.publicKey), sender.privateKey.agree(recipient.publicKey),
        )
        val values = mapOf(
            "relay token" to cellarKey.token,
            "mailbox" to cellarKey.mailboxId,
            "data key" to cellarKey.dataKey.hex(),
            "inbox token" to sender.inboxToken,
            "inbox" to sender.inboxId,
            "envelope" to envelope.hex(),
            "grant key" to grantKey.hex(),
        )
        for ((name, value) in values) assertEquals(remoteExpected[name], value, name)
        // Opening the envelope gives the plaintext back.
        assertEquals("hello", Envelope.open(envelope, Envelope.Kind.Photo, "photo/x", cellarKey.dataKey).decodeToString())
    }

    private val remoteExpected = mapOf(
        "relay token" to "zyJIksEyVCrPawgL8q0UGr5GMi1KewaCGSzwHGOLFno",
        "mailbox" to "cace8487402ad2a081f417653a8443def",
        "data key" to "d9bdb64332f3da0ef2e703d1cbdf3be079c75095ce76a6818f07fdfa2b802f54",
        "inbox token" to "K9Ij7YuyprGlxKKF0rXF1bTbBNHtk2czQHE87LzJvx8",
        "inbox" to "i2a17946aea22c15127c53bac03a59f8a",
        "envelope" to "454e435601020707070707070707070707078f6f64053663e6ba095fca5586e637cebda8c6dfbf",
        "grant key" to "b2ecfff6ab54c6b39ae9edbdd44abffc2dbc361e2faa6571cd4abc5fba9d517d",
    )

    private val expected = mapOf(
        "host ephemeral public" to "07a37cbc142093c8b755dc1b10e86cb426374ad16aa853ed0bdfc0b2b86d1c7c",
        "joiner ephemeral public" to "64b101b1d0be5a8704bd078f9895001fc03e8e9f9522f188dd128d9846d48466",
        "commitment" to "0096fb81cae546de04c7851836d2c4f8e5e9b9c97a1eb42b2a4b01cb32a9b5e2",
        "confirmation code" to "141 953",
        "pair host to joiner" to "a79c1d1f93a7699a239e54c6a62a9321e53b3e81a8cb3710c505c3f2c61fb354",
        "pair joiner to host" to "47c1553f86cbcc6389e5a2562434f866f113a6b8268138ca5a9321e0bcb21019",
        "sync auth" to "b37137f432db51fbb80805e75d49ab80cd1e3bb990f3c559a8decf54c14c62de",
        "sync initiator to responder" to "bfe9c2c7723bdc71a54ed30da89382476a1f8abd8b90e6447767f4428bd56dfc",
        "sync responder to initiator" to "718fd5b20b9fed4805c0fa0f6efe235c7f476228f10741211a3a8acc0245ba5f",
        "first frame" to "8f7553c327cfefd5e3c77017246680dc74a070adfcdf6b09a06a2a8bf7c21dd2bbe39db85c",
        "rendezvous 123456" to "2bc4802d60f2f128",
        "network tag" to "aedcc6a68baa",
    )
}
