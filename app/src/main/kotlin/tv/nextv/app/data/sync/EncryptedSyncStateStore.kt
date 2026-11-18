package tv.nextv.app.data.sync

import android.util.Log
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import tv.nextv.core.sync.SyncCodec
import tv.nextv.core.sync.SyncFormatException
import tv.nextv.core.sync.SyncState
import tv.nextv.core.sync.SyncStateCodec
import tv.nextv.core.sync.SyncStateStore
import java.io.File
import java.io.IOException
import java.security.SecureRandom

/**
 * État de la synchronisation gardé sur l'appareil, chiffré : il contient la clé du groupe et les identifiants des
 * playlists. Le fichier (jusqu'à quelques centaines de Ko) est chiffré par une clé à lui, elle-même protégée par le
 * trousseau de l'appareil ([protect] / [unprotect]) : le trousseau ne chiffre ainsi que quelques octets, rapide même
 * sur une petite Fire TV.
 */
class EncryptedSyncStateStore(
    private val folder: File,
    private val protect: (String) -> String,
    private val unprotect: (String) -> String?,
) : SyncStateStore {
    private val stateFile = File(folder, "state.bin")
    private val keyFile = File(folder, "key")

    private fun key(create: Boolean): ByteArray? {
        if (keyFile.exists()) {
            val key = runCatching { unprotect(keyFile.readText())?.decodeHex()?.toByteArray() }.getOrNull()
            if (key != null && key.size == KEY_BYTES) return key
            // Trousseau réinitialisé : l'ancien état ne peut plus être lu.
            Log.w(TAG, "Clé de l'état illisible")
        }
        if (!create) return null
        val key = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
        folder.mkdirs()
        writeAtomically(keyFile, protect(key.toByteString().hex()).toByteArray())
        return key
    }

    override fun load(): SyncState? {
        if (!stateFile.exists()) return null
        val key = key(create = false) ?: return null
        return try {
            SyncStateCodec.decode(String(SyncCodec.decrypt(stateFile.readBytes(), key), Charsets.UTF_8))
        } catch (e: SyncFormatException) {
            Log.w(TAG, "État illisible", e)
            null
        } catch (e: IOException) {
            null
        }
    }

    override fun save(state: SyncState) {
        val key = key(create = true) ?: return
        try {
            writeAtomically(stateFile, SyncCodec.encrypt(SyncStateCodec.encode(state).toByteArray(Charsets.UTF_8), key))
        } catch (e: IOException) {
            // Stockage plein : la synchronisation suivante refera ce qu'il faut.
            Log.w(TAG, "État non enregistré", e)
        }
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        folder.mkdirs()
        val temporary = File(folder, file.name + ".tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(file)) {
            file.delete()
            if (!temporary.renameTo(file)) throw IOException("Écriture impossible : ${file.name}")
        }
    }

    private companion object {
        const val TAG = "NixTV/Sync"
        const val KEY_BYTES = 32
    }
}
