package tv.nextv.app

import java.io.File

/**
 * Dernier plantage de l'application : enregistré (adresses et identifiants masqués) au moment où il
 * se produit, puis montré dans les réglages pour pouvoir le signaler.
 */
class CrashLog(private val file: File, private val redact: (String) -> String) {

    /** @property details trace de la pile, limitée à [MAX_LINES] lignes. */
    data class Entry(val atMillis: Long, val version: String, val summary: String, val details: String)

    /** Enregistre les plantages, puis laisse le système fermer l'application comme d'habitude. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record(error)
            previous?.uncaughtException(thread, error)
        }
    }

    fun record(error: Throwable) {
        runCatching {
            val trace = runCatching { redact(error.stackTraceToString()) }.getOrElse { error.javaClass.name }
            val lines = trace.lineSequence().take(MAX_LINES).joinToString("\n")
            file.writeText("${System.currentTimeMillis()}\n${BuildConfig.VERSION_NAME}\n$lines")
        }
    }

    fun last(): Entry? = runCatching {
        if (!file.exists()) return null
        val lines = file.readLines()
        val at = lines.getOrNull(0)?.toLongOrNull() ?: return null
        val details = lines.drop(2)
        Entry(at, lines.getOrNull(1).orEmpty(), summary(details), details.joinToString("\n"))
    }.getOrNull()

    fun clear() {
        file.delete()
    }

    private companion object {
        const val MAX_LINES = 40

        /** Erreur et premier endroit du code de l'application concerné. */
        fun summary(lines: List<String>): String {
            val error = lines.firstOrNull().orEmpty()
            val cause = lines.lastOrNull { it.startsWith("Caused by:") }?.removePrefix("Caused by: ")
            val place = lines.firstOrNull { it.trim().startsWith("at tv.nextv.") }?.trim()?.removePrefix("at ")
            return listOfNotNull(cause ?: error, place).joinToString("\n")
        }
    }
}
