package app.encave.core.sync

import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The published test vectors of docs/SYNC.md §10, computed here from the fixed inputs of
 * `fixtures/sync/protocol-vectors.json`: matching them byte for byte is what lets an Android
 * phone pair and sync with an iPhone. Mirrors CaveKit's ProtocolVectorTests.swift, which
 * reads the same file.
 */
class ProtocolVectorTests {
    private val root = Json.parseToJsonElement(File("../../fixtures/sync/protocol-vectors.json").readText()).jsonObject
    private val inputs = root.obj("inputs")

    private fun JsonObject.obj(key: String) = getValue(key).jsonObject
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.bytes(key: String) = unhex(text(key))
    private fun JsonObject.uuid(key: String): UUID = UUID.fromString(text(key))
    private fun JsonObject.values(key: String) = obj(key).mapValues { it.value.jsonPrimitive.content }

    private val hostDevice = inputs.uuid("hostDevice")
    private val joinerDevice = inputs.uuid("joinerDevice")
    private val cellar = inputs.uuid("cellar")
    private fun cellarKey(name: String) = inputs.obj(name).let { CellarKey(cellar, it.text("epoch").toLong(), it.bytes("key")) }

    @Test
    fun vectors() {
        val hostEphemeral = X25519Key(inputs.bytes("hostEphemeral"))
        val joinerEphemeral = X25519Key(inputs.bytes("joinerEphemeral"))
        val hostNonce = inputs.bytes("hostNonce")
        val joinerNonce = inputs.bytes("joinerNonce")
        val hostKey = hostEphemeral.publicKey
        val joinerKey = joinerEphemeral.publicKey
        val commitment = PairingCrypto.commitment(hostKey, hostNonce)
        val pairing = PairingCrypto.keys(
            hostEphemeral, joinerKey, hostKey, joinerKey, hostNonce, joinerNonce,
            PeerInfo(hostDevice, "", ""), PeerInfo(joinerDevice, "", ""),
        )
        val initiatorIdentity = X25519Key(inputs.bytes("initiatorIdentity"))
        val responderIdentity = X25519Key(inputs.bytes("responderIdentity"))
        val session = SyncHandshake.sessionKeys(
            initiator = hostDevice, responder = joinerDevice, initiatorKey = hostKey, responderKey = joinerKey,
            initiatorNonce = hostNonce, responderNonce = joinerNonce, cellar = cellar,
            ee = hostEphemeral.agree(joinerKey),
            es = hostEphemeral.agree(responderIdentity.publicKey),
            se = initiatorIdentity.agree(joinerKey),
        )

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
            "rendezvous of 123456" to PairingCode(inputs.text("rendezvousCode")).rendezvous,
            "network tag of the cellar" to Membership(cellarId = cellar).networkTag,
        )
        assertEquals(root.values("pairing"), values)

        // Frames: the nonce is the counter, each direction has its key.
        for (element in root.getValue("frames").jsonArray) {
            val frame = element.jsonObject
            val key = if (frame.text("direction") == "i2r") session.i2r else session.r2i
            val counter = frame.text("counter").toLong()
            val plaintext = frame.text("plaintext").toByteArray()
            val sealed = ChaChaPoly.seal(plaintext, key, SecureChannel.nonce(counter))
            assertEquals(frame.text("expected"), sealed.hex(), frame.text("name"))
            assertArrayEquals(plaintext, ChaChaPoly.open(unhex(frame.text("expected")), key, SecureChannel.nonce(counter)))
        }
        // The frame a Kotlin device sends is the one the Swift code computed.
        assertEquals("""{"type":"photos-end"}""", WireMessage("photos-end").encoded().toString(Charsets.UTF_8))
    }

    /** Remote sync (SYNC.md §8): mailbox and inbox IDs, tokens, data keys, an envelope and a grant. */
    @Test
    fun remoteVectors() {
        val cellarKey = cellarKey("cellarKey")
        val epoch2 = cellarKey("epoch2CellarKey")
        val sender = DeviceIdentity(hostDevice, inputs.obj("grant").bytes("sender"))
        val envelopeInput = inputs.obj("envelope")
        val envelope = Envelope.seal(
            envelopeInput.text("plaintext").toByteArray(), kind(envelopeInput.text("kind").toInt()), envelopeInput.text("path"),
            cellarKey.dataKey, envelopeInput.bytes("nonce"),
        )

        val grantInput = inputs.obj("grant")
        val grantPath = grantInput.text("path")
        assertEquals("grant/$cellar-00000002-$hostDevice-0a1b2c3d", grantPath)
        val ephemeral = X25519Key(grantInput.bytes("ephemeral"))
        val recipient = DeviceIdentity(joinerDevice, grantInput.bytes("recipient"))
        val grantKey = KeyGrant.key(
            grantPath, ephemeral.publicKey, recipient.publicKey,
            ephemeral.agree(recipient.publicKey), sender.privateKey.agree(recipient.publicKey),
        )
        // Built as KeyGrant.sealed does, with a fixed ephemeral key and envelope nonce.
        val grant = KeyGrant(epoch2, hostDevice, grantInput.text("storage"))
        val plaintext = SyncJson.encode(KeyGrant.serializer(), grant)
        val grantFile = ephemeral.publicKey + Envelope.seal(plaintext, Envelope.Kind.Grant, grantPath, grantKey, grantInput.bytes("nonce"))
        // KeyGrant.sealed gives the same path (apart from its random part) and the same plaintext.
        val (sealedPath, sealedFile) = grant.sealed(sender, recipient.publicKey, ephemeral)
        assertEquals(grantPath.dropLast(8), sealedPath.dropLast(8))
        assertArrayEquals(ephemeral.publicKey, sealedFile.copyOf(32))

        val values = mapOf(
            "relay token" to cellarKey.token,
            "mailbox ID" to cellarKey.mailboxId,
            "data key" to cellarKey.dataKey.hex(),
            "inbox token" to sender.inboxToken,
            "inbox ID" to sender.inboxId,
            "envelope (whole file)" to envelope.hex(),
            "grant key" to grantKey.hex(),
            "epoch 2 relay token" to epoch2.token,
            "epoch 2 mailbox ID" to epoch2.mailboxId,
            "epoch 2 data key" to epoch2.dataKey.hex(),
            "grant plaintext" to plaintext.decodeToString(),
            "grant (whole file)" to grantFile.hex(),
        )
        assertEquals(root.values("remote"), values)

        // The published grant file opens to the grant, for a recipient that knows the sender.
        val members = Membership(cellar, mapOf(hostDevice to Member(hostDevice, "Host", sender.publicKey, "ios")))
        assertEquals(grant, KeyGrant.open(unhex(values.getValue("grant (whole file)")), grantPath, recipient, members))
    }

    /** Envelopes that open, and ones a device must refuse (another kind, another path, a damaged tag). */
    @Test
    fun envelopeVectors() {
        val key = cellarKey("cellarKey").dataKey
        val cases = root.getValue("envelopes").jsonArray.map { it.jsonObject }
        assertTrue(cases.count { it.text("outcome") == "refused" } >= 4)
        for (case in cases) {
            val name = case.text("name")
            val file = case.bytes("file")
            val kind = kind(case.getValue("kind").jsonPrimitive.int)
            val path = case.text("path")
            when (val outcome = case.text("outcome")) {
                "refused" -> {
                    val error = assertThrows<RemoteSyncException>(name) { Envelope.open(file, kind, path, key) }
                    assertEquals(RemoteSyncError.Corrupted(path), error.error, name)
                }
                else -> assertEquals(outcome, Envelope.open(file, kind, path, key).decodeToString(), name)
            }
        }
    }

    /** The tables of docs/SYNC.md §10 give the values of the fixture, and all of them. */
    @Test
    fun theDocumentationMatchesTheFixture() {
        val text = File("../../docs/SYNC.md").readText()
        val section = text.substringAfter("\n## 10. Test vectors\n").substringBefore("\n## ")
        val rows = section.lines().filter { it.startsWith("| ") && !it.startsWith("| Value |") && !it.startsWith("| Case |") }
            .map { line -> line.trim().removePrefix("|").removeSuffix("|").split(" | ").map { it.trim().replace("`", "") } }

        val expected = root.values("pairing") + root.values("remote") +
            root.getValue("frames").jsonArray.associate { it.jsonObject.text("name") to it.jsonObject.text("expected") }
        val values = rows.filter { it.size == 2 }.associate { it[0] to it[1] }
        assertEquals(expected, values)

        val envelopes = root.getValue("envelopes").jsonArray.map { it.jsonObject }
            .map { listOf(it.text("name"), it.text("kind"), it.text("path"), it.text("file"), it.text("outcome")) }
        assertEquals(envelopes, rows.filter { it.size == 5 })
        assertEquals(rows.size, values.size + envelopes.size, "every row of §10 is a value or an envelope")
    }

    private fun kind(raw: Int) = Envelope.Kind.entries.single { it.raw.toInt() == raw }
}

/** Bytes from lowercase or uppercase hex. */
internal fun unhex(text: String): ByteArray {
    require(text.length % 2 == 0) { "odd hex length" }
    return ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
