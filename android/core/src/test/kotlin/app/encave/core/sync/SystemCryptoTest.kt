package app.encave.core.sync

import java.security.Security
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import org.conscrypt.Conscrypt
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The sync primitives on Conscrypt, the provider Android uses, with the RFC test
 * vectors (the other tests run them on the JDK's provider).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SystemCryptoTest {
    private val provider = Conscrypt.newProvider()

    @BeforeAll
    fun installConscrypt() {
        Security.insertProviderAt(provider, 1)
    }

    @AfterAll
    fun removeConscrypt() {
        Security.removeProvider(provider.name)
    }

    @Test
    fun conscryptServesBoth() {
        assertEquals(provider.name, KeyAgreement.getInstance("XDH").provider.name)
        assertEquals(provider.name, Cipher.getInstance("ChaCha20/Poly1305/NoPadding").provider.name)
    }

    @Test
    fun x25519MatchesRfc7748() {
        // RFC 7748 §6.1.
        val alice = X25519Key("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a".hexBytes())
        val bob = X25519Key("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb".hexBytes())
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", alice.publicKey.hex())
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", bob.publicKey.hex())
        val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(shared, alice.agree(bob.publicKey).hex())
        assertEquals(shared, bob.agree(alice.publicKey).hex())
    }

    @Test
    fun aLowOrderPointIsRefused() {
        val key = X25519Key.generate()
        val error = assertThrows(SyncException::class.java) { key.agree(ByteArray(32)) }
        assertEquals(SyncError.AuthenticationFailed, error.error)
    }

    @Test
    fun chaChaPolyMatchesRfc8439() {
        // RFC 8439 §2.8.2.
        val key = "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f".hexBytes()
        val nonce = "070000004041424344454647".hexBytes()
        val aad = "50515253c0c1c2c3c4c5c6c7".hexBytes()
        val plaintext = ("Ladies and Gentlemen of the class of '99: If I could offer you only one tip " +
            "for the future, sunscreen would be it.").toByteArray()
        val sealed = ChaChaPoly.seal(plaintext, key, nonce, aad)
        assertEquals("1ae10b594f09e26a7e902ecbd0600691", sealed.copyOfRange(sealed.size - 16, sealed.size).hex())
        assertEquals("d31a8d34648e60db7b86afbc53ef7ec2", sealed.copyOfRange(0, 16).hex())
        assertArrayEquals(plaintext, ChaChaPoly.open(sealed, key, nonce, aad))
        sealed[0] = (sealed[0].toInt() xor 1).toByte()
        assertNull(ChaChaPoly.open(sealed, key, nonce, aad))
    }
}
