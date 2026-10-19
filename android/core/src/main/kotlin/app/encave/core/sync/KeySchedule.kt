package app.encave.core.sync

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// The primitives of docs/SYNC.md §3, byte for byte as CaveKit computes them with
// CryptoKit. Only the system's encryption is used (see docs/ENCRYPTION.md): the
// standard Java names, served by Conscrypt on Android (X25519 since Android 13)
// and by the JDK in the unit tests.

internal val random = SecureRandom()

/** An X25519 private key (32 raw bytes, clamped when used, as CryptoKit does). */
class X25519Key(private val raw: ByteArray) {
    init {
        require(raw.size == 32) { "X25519 keys are 32 bytes" }
    }

    /** X25519(private, 9): the agreement with the base point is the public key. */
    val publicKey: ByteArray by lazy { Xdh.agree(raw, BASE_POINT) }

    /** The raw private key, to store it (encrypted) on the device. */
    val rawRepresentation: ByteArray get() = raw.copyOf()

    companion object {
        /** 32 random bytes: X25519 clamps any of them into a valid private key (RFC 7748). */
        fun generate(): X25519Key = X25519Key(ByteArray(32).also { random.nextBytes(it) })

        private val BASE_POINT = ByteArray(32).also { it[0] = 9 }
    }

    /** `DH(this, publicKey)`; an all-zero result (low-order point) is refused. */
    fun agree(publicKey: ByteArray): ByteArray {
        if (publicKey.size != 32) throw SyncException(SyncError.ProtocolViolation("bad key"))
        val shared = try {
            Xdh.agree(raw, publicKey)
        } catch (_: GeneralSecurityException) {
            throw SyncException(SyncError.AuthenticationFailed)
        } catch (_: IllegalStateException) {
            throw SyncException(SyncError.AuthenticationFailed) // some providers refuse low-order points this way
        }
        if (shared.all { it == 0.toByte() }) throw SyncException(SyncError.AuthenticationFailed)
        return shared
    }
}

/** X25519 through the system's "XDH" key agreement, with keys as 32 raw bytes. */
internal object Xdh {
    // DER prefixes of an X25519 key (OID 1.3.101.110) followed by its 32 raw bytes.
    private val PUBLIC_PREFIX = "302a300506032b656e032100".hexBytes()
    private val PRIVATE_PREFIX = "302e020100300506032b656e04220420".hexBytes()

    fun agree(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val factory = KeyFactory.getInstance("XDH")
        val private = factory.generatePrivate(PKCS8EncodedKeySpec(PRIVATE_PREFIX + privateKey))
        val public = factory.generatePublic(X509EncodedKeySpec(PUBLIC_PREFIX + publicKey))
        return KeyAgreement.getInstance("XDH").run {
            init(private)
            doPhase(public, true)
            generateSecret()
        }
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
        // ASCII digits whatever the language of the device (Persian or Arabic digits otherwise).
        val digits = "%06d".format(Locale.ROOT, value % 1_000_000)
        return digits.take(3) + " " + digits.takeLast(3)
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}

/** ChaCha20-Poly1305 with a 32-byte key and a 12-byte nonce; output and input are ciphertext ‖ tag. */
object ChaChaPoly {
    fun seal(plaintext: ByteArray, key: ByteArray, nonce: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plaintext)

    /** Null if the tag does not match. */
    fun open(sealed: ByteArray, key: ByteArray, nonce: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray? {
        if (sealed.size < 16) return null
        return try {
            cipher(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(sealed)
        } catch (_: AEADBadTagException) {
            null
        }
    }

    /** A new cipher for each message (a key and nonce are never reused on one instance). */
    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher {
        require(key.size == 32 && nonce.size == 12) { "ChaCha20-Poly1305 takes a 32-byte key and a 12-byte nonce" }
        return newCipher().apply {
            init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
            if (aad.isNotEmpty()) updateAAD(aad)
        }
    }

    /** Android (Conscrypt) knows the cipher under its first name, the JDK (unit tests) under the second. */
    private fun newCipher(): Cipher = try {
        Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
    } catch (_: GeneralSecurityException) {
        Cipher.getInstance("ChaCha20-Poly1305")
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
    /** Strict, like Foundation's `Data(base64Encoded:)`: padded, no line breaks or other characters. */
    override fun deserialize(decoder: Decoder): ByteArray {
        val text = decoder.decodeString()
        if (text.length % 4 != 0) throw SerializationException("Invalid Base64")
        return try {
            Base64.getDecoder().decode(text)
        } catch (_: IllegalArgumentException) {
            throw SerializationException("Invalid Base64")
        }
    }
}
