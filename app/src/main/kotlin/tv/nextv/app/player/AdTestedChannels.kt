package tv.nextv.app.player

import tv.nextv.core.epg.TntChannels
import java.io.File

/**
 * Temps déjà enregistré pour l'analyse des pubs, chaîne par chaîne (fichier [file], gardé d'une fois sur l'autre) :
 * l'enregistrement automatique va d'abord sur les chaînes les moins connues. Les chaînes déjà analysées par Claude
 * (nuit du 15 au 16/11/2026 sur la box, M6 sur la Fire TV) partent avec leur temps ([ANALYZED]).
 *
 * Clé d'une chaîne ([key]) : son numéro de la TNT (« TF1 HD », « TF1 FHD » et « TF1 » ne font qu'une), sinon son nom.
 */
class AdTestedChannels(private val file: File) {
    private val millis = HashMap<String, Long>()
    private var loaded = false
    private var unsaved = 0L

    /** Temps enregistré sur la chaîne [title] (ms), avec ce que Claude a déjà analysé. */
    @Synchronized
    fun millis(title: String): Long {
        load()
        val key = key(title)
        return (millis[key] ?: 0L) + (ANALYZED[key] ?: 0) * 60_000L
    }

    /** Ajoute [duration] ms enregistrées sur la chaîne [title] ; écrit sur le disque au plus une fois par minute. */
    @Synchronized
    fun add(title: String, duration: Long) {
        if (duration <= 0 || title.isBlank()) return
        load()
        val key = key(title)
        millis[key] = (millis[key] ?: 0L) + duration
        unsaved += duration
        if (unsaved >= SAVE_MILLIS) save()
    }

    /** Écrit tout de suite ce qui ne l'est pas encore (changement de chaîne, fin de l'enregistrement). */
    @Synchronized
    fun save() {
        if (!loaded || unsaved == 0L) return
        unsaved = 0
        runCatching {
            file.parentFile?.mkdirs()
            val partial = File(file.parentFile, file.name + ".part")
            partial.writeText(millis.entries.joinToString("") { (key, value) -> "$key\t$value\n" })
            partial.renameTo(file)
        }
    }

    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            if (!file.exists()) return
            file.readLines().forEach { line ->
                val key = line.substringBefore('\t')
                val value = line.substringAfter('\t', "").toLongOrNull()
                if (key.isNotEmpty() && value != null) millis[key] = value
            }
        }
    }

    companion object {
        private const val SAVE_MILLIS = 60_000L

        fun key(title: String): String = TntChannels.number(title)?.let { "tnt$it" } ?: title.trim().lowercase()

        /** Minutes déjà analysées par Claude, par numéro de la TNT (enregistrements des 15 et 16/11/2026). */
        val ANALYZED: Map<String, Int> = mapOf(
            "tnt1" to 52, "tnt2" to 39, "tnt3" to 30, "tnt6" to 189, "tnt9" to 106, "tnt10" to 98, "tnt11" to 121,
            "tnt20" to 52, "tnt22" to 31,
            // Directs publics des chaînes RMC, enregistrés et notés avec les repères de pub de leur flux.
            "tnt23" to 151, "tnt24" to 120, "tnt25" to 120,
        )
    }
}
