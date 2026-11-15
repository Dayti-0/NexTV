package tv.nextv.app.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tv.nextv.core.audio.AdMarks
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Repères de « baisser le son des pubs » appris chaîne par chaîne (logo, carton entre deux spots), gardés sur
 * l'appareil : un petit fichier par flux, nommé d'après une empreinte de son adresse (jamais l'adresse elle-même).
 * Les plus anciens sont supprimés au-delà de [MAX_FILES].
 *
 * @param scope fil d'arrière-plan des écritures ([save] rend la main tout de suite).
 */
class AdMarksStore(private val directory: File, private val scope: CoroutineScope) {

    /** Derniers repères lus ou appris : relus sans attendre l'écriture du fichier (chaîne rouverte aussitôt). */
    private val recent = object : LinkedHashMap<String, AdMarks>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AdMarks>?): Boolean = size > RECENT
    }

    /** Repères du flux d'adresse [url] ; `null` s'il n'a jamais été regardé assez longtemps. */
    fun load(url: String): AdMarks? {
        val name = name(url)
        synchronized(recent) { recent[name] }?.let { return it }
        val read = try {
            File(directory, name).takeIf { it.isFile && it.length() in 1..MAX_BYTES }?.readBytes()?.let(AdMarks::decode)
        } catch (e: IOException) {
            null
        }
        if (read != null) synchronized(recent) { recent[name] = read }
        return read
    }

    fun save(url: String, marks: AdMarks) {
        if (marks.isEmpty) return
        val name = name(url)
        synchronized(recent) { recent[name] = marks }
        scope.launch(Dispatchers.IO) { write(name, marks) }
    }

    private fun write(name: String, marks: AdMarks) {
        try {
            directory.mkdirs()
            val temporary = File(directory, "$name.tmp")
            temporary.writeBytes(marks.encode())
            if (!temporary.renameTo(File(directory, name))) temporary.delete()
            val files = directory.listFiles { file -> file.name.endsWith(EXTENSION) } ?: return
            if (files.size > MAX_FILES) files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
        } catch (e: IOException) {
            // Disque plein ou dossier illisible : les repères seront réappris à la prochaine lecture.
        }
    }

    private fun name(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        return digest.take(16).joinToString("") { "%02x".format(it) } + EXTENSION
    }

    private companion object {
        const val EXTENSION = ".marks"
        const val MAX_FILES = 400
        const val MAX_BYTES = 128 * 1024L
        const val RECENT = 64
    }
}
