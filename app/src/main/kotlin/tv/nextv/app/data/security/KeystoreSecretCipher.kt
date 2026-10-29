package tv.nextv.app.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import tv.nextv.core.model.Credentials
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Chiffrement AES-256-GCM avec une clé non exportable de l'Android Keystore.
 *
 * Format : Base64(iv ‖ texte chiffré). Si la clé disparaît (réinitialisation de l'appareil,
 * restauration sur un autre appareil), [decrypt] renvoie `null` : l'utilisateur devra ressaisir
 * ses identifiants, mais aucune donnée n'est jamais exposée en clair.
 */
class KeystoreSecretCipher(private val alias: String = DEFAULT_ALIAS) {

    @Synchronized
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String? = try {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        if (bytes.size <= IV_LENGTH) {
            null
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_LENGTH_BITS, bytes, 0, IV_LENGTH))
            String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        null
    }

    fun encryptCredentials(credentials: Credentials): String =
        encrypt(credentials.username + SEPARATOR + credentials.password)

    fun decryptCredentials(encoded: String): Credentials? {
        val plain = decrypt(encoded) ?: return null
        val separator = plain.indexOf(SEPARATOR)
        if (separator < 0) return null
        return Credentials(plain.substring(0, separator), plain.substring(separator + 1))
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "nextv_secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BITS = 128

        /** Les identifiants validés ne contiennent jamais de caractère de contrôle. */
        const val SEPARATOR = '\u0000'
    }
}
