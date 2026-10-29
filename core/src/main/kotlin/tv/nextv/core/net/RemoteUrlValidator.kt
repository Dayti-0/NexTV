package tv.nextv.core.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tv.nextv.core.error.InvalidUrlReason
import tv.nextv.core.error.SourceError
import tv.nextv.core.model.Credentials

/**
 * Validation des URL saisies pour une playlist ou un guide EPG.
 *
 * Seuls HTTP et HTTPS sont acceptés. Les identifiants éventuellement placés dans l'URL
 * (`http://user:pass@hôte/…`) sont extraits pour être stockés chiffrés et retirés de l'URL.
 */
object RemoteUrlValidator {

    val SUPPORTED_SCHEMES = setOf("http", "https")

    private val SCHEME = Regex("""^([a-zA-Z][a-zA-Z0-9+.\-]*):""")

    sealed interface Result {
        data class Valid(val url: HttpUrl, val embeddedCredentials: Credentials?) : Result
        data class Invalid(val error: SourceError) : Result
    }

    fun validate(input: String): Result {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return invalid(InvalidUrlReason.EMPTY)
        if (trimmed.any { it.isISOControl() }) return invalid(InvalidUrlReason.INVALID_CHARACTERS)

        val scheme = SCHEME.find(trimmed)?.groupValues?.get(1)?.lowercase()
        // « hôte:port/chemin » ressemble à un schéma : on le traite comme un schéma absent.
        val looksLikeHostPort = scheme != null && trimmed.substring(scheme.length + 1).let { rest ->
            rest.takeWhile { it.isDigit() }.isNotEmpty() && !rest.startsWith("//")
        }
        if (scheme == null || looksLikeHostPort) return invalid(InvalidUrlReason.MISSING_SCHEME)
        if (scheme !in SUPPORTED_SCHEMES) return Result.Invalid(SourceError.UnsupportedScheme(scheme))
        if (!trimmed.substring(scheme.length + 1).startsWith("//")) return invalid(InvalidUrlReason.MALFORMED)

        val url = trimmed.toHttpUrlOrNull() ?: return invalid(InvalidUrlReason.MALFORMED)
        if (url.host.isBlank()) return invalid(InvalidUrlReason.MISSING_HOST)

        val credentials = if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            Credentials(url.username, url.password)
        } else {
            null
        }
        val clean = if (credentials != null) url.newBuilder().username("").password("").build() else url
        return Result.Valid(clean, credentials)
    }

    /** Raccourci : URL valide ou `null`. */
    fun parseOrNull(input: String): HttpUrl? = (validate(input) as? Result.Valid)?.url

    private fun invalid(reason: InvalidUrlReason) = Result.Invalid(SourceError.InvalidUrl(reason))
}
