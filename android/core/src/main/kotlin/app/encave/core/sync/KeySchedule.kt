package app.encave.core.sync

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519

// The primitives of docs/SYNC.md §3, byte for byte as CaveKit computes them with
// CryptoKit. X25519 and ChaCha20-Poly1305 come from Bouncy Castle's lightweight
// API, which works on every Android version.

internal val random = SecureRandom()

/** An X25519 private key (32 raw bytes, clamped when used, as CryptoKit does). */
class X25519Key(private val raw: ByteArray) {
    init {
        require(raw.size == 32) { "X25519 keys are 32 bytes" }
    }

    val publicKey: ByteArray by lazy { ByteArray(32).also { X25519.scalarMultBase(raw, 0, it, 0) } }

    /** The raw private key, to store it (encrypted) on the device. */
    val rawRepresentation: ByteArray get() = raw.copyOf()

    companion object {
        fun generate(): X25519Key = X25519Key(ByteArray(32).also { X25519.generatePrivateKey(random, it) })
    }

    /** `DH(this, publicKey)`; an all-zero result (low-order point) is refused. */
    fun agree(publicKey: ByteArray): ByteArray {
        if (publicKey.size != 32) throw SyncException(SyncError.ProtocolViolation("bad key"))
        val shared = ByteArray(32)
        if (!X25519.calculateAgreement(raw, 0, publicKey, 0, shared, 0)) throw SyncException(SyncError.AuthenticationFailed)
        return shared
    }
}

object KeySchedule {
    /** `H(label, parts)`: SHA-256 of the label, then each part as a 4-byte big-endian length and its bytes. */
    fun hash(label: String, parts: List<ByteArray>): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(label.toByteArray(Charsets.UTF_8))
        for (part in parts) {
            digest.update(ByteBuffer.allocate(4).putInt(part.size).array())
            digest.update(part)
        }
        return digest.digest()
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // An empty HMAC key is the same as a block of zeros (RFC 2104); the JDK refuses empty keys.
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** HKDF-SHA256 (RFC 5869). */
    fun derive(secret: ByteArray, salt: ByteArray, info: String, count: Int = 32): ByteArray {
        val prk = hmac(salt, secret)
        val output = java.io.ByteArrayOutputStream()
        var previous = ByteArray(0)
        var counter = 1
        while (output.size() < count) {
            previous = hmac(prk, previous + info.toByteArray(Charsets.UTF_8) + byteArrayOf(counter.toByte()))
            output.write(previous)
            counter++
        }
        return output.toByteArray().copyOf(count)
    }

    fun randomNonce(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    /** The code both screens show: 6 digits, "123 456". */
    fun confirmationCode(key: ByteArray): String {
        val value = ByteBuffer.wrap(key, 0, 4).int.toLong() and 0xFFFF_FFFFL
        val digits = "%06d".format(value % 1_000_000)
        return digits.take(3) + " " + digits.takeLast(3)
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}

/** ChaCha20-Poly1305 with a 32-byte key and a 12-byte nonce; output and input are ciphertext ‖ tag. */
object ChaChaPoly {
    fun seal(plaintext: ByteArray, key: ByteArray, nonce: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        val written = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        cipher.doFinal(out, written)
        return out
    }

    /** Null if the tag does not match. */
    fun open(sealed: ByteArray, key: ByteArray, nonce: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray? {
        if (sealed.size < 16) return null
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(sealed.size))
        return try {
            val written = cipher.processBytes(sealed, 0, sealed.size, out, 0)
            cipher.doFinal(out, written)
            out
        } catch (_: InvalidCipherTextException) {
            null
        }
    }

    fun randomNonce(): ByteArray = ByteArray(12).also { random.nextBytes(it) }
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** Base64URL without padding, as in QR codes and relay tokens. */
fun ByteArray.base64Url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)

fun String.base64UrlBytes(): ByteArray? = runCatching { Base64.getUrlDecoder().decode(this) }.getOrNull()

/** Binary values in JSON: standard Base64, as Swift's JSONEncoder writes `Data`. */
object Base64Serializer : KSerializer<ByteArray> {
    override val descriptor = PrimitiveSerialDescriptor("app.encave.Base64", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ByteArray) = encoder.encodeString(Base64.getEncoder().encodeToString(value))
    override fun deserialize(decoder: Decoder): ByteArray = Base64.getMimeDecoder().decode(decoder.decodeString())
}
