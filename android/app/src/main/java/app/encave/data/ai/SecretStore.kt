package app.encave.data.ai

import android.content.Context
import androidx.core.content.edit
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small secrets (the AI API key) encrypted with an AES-GCM key that never leaves
 * the Android Keystore. The ciphertext is excluded from backups and transfers.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun get(name: String): String? = runCatching { read(name) }.getOrNull()

    /**
     * The secret, or null if none is stored. Throws if one is stored but cannot be read (the
     * Keystore failed), so a caller can tell a missing secret from one it could not read.
     */
    fun read(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
        return String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
    }

    /** [commit]: written to disk before returning (a key moved from its old place). */
    fun set(name: String, value: String?, commit: Boolean = false) {
        if (value.isNullOrEmpty()) {
            prefs.edit(commit = commit) { remove(name) }
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit(commit = commit) { putString(name, Base64.encodeToString(encrypted, Base64.NO_WRAP)) }
    }

    companion object {
        /**
         * Saves made while typing a key run here, one after the other and in order, so the
         * last key typed is the one kept (and leaving the screen does not drop it).
         */
        val writes: java.util.concurrent.Executor = java.util.concurrent.Executors.newSingleThreadExecutor()

        private const val FILE = "secrets"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "encave.secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
    }
}
