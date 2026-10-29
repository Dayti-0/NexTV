package tv.nextv.app.data.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.nextv.app.data.db.CredentialDao
import tv.nextv.app.data.db.CredentialEntity
import tv.nextv.core.log.Logger
import tv.nextv.core.model.Credentials
import tv.nextv.core.repository.CredentialStore

class EncryptedCredentialStore(
    private val dao: CredentialDao,
    private val cipher: KeystoreSecretCipher,
    private val logger: Logger,
) : CredentialStore {

    override suspend fun get(sourceId: Long): Credentials? {
        val entity = dao.get(sourceId) ?: return null
        val credentials = withContext(Dispatchers.Default) { cipher.decryptCredentials(entity.ciphertext) }
        if (credentials == null) logger.warn(TAG, "Identifiants de la source $sourceId illisibles (clé du Keystore indisponible)")
        return credentials
    }

    override suspend fun put(sourceId: Long, credentials: Credentials) {
        val ciphertext = withContext(Dispatchers.Default) { cipher.encryptCredentials(credentials) }
        dao.put(CredentialEntity(sourceId, ciphertext))
    }

    override suspend fun delete(sourceId: Long) = dao.delete(sourceId)

    private companion object {
        const val TAG = "CredentialStore"
    }
}
