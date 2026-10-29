package tv.nextv.core.log

import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Masque les données sensibles dans un texte destiné aux journaux ou à l'affichage.
 *
 * Deux mécanismes complémentaires :
 * - des motifs génériques (identifiants dans l'URL, paramètres de requête sensibles,
 *   chemins de type Xtream `/live/<user>/<pass>/…`, en-têtes d'authentification) ;
 * - les secrets connus ([registerSecret]) : mots de passe et identifiants des sources,
 *   masqués où qu'ils apparaissent, y compris sous forme encodée.
 */
class Redactor {

    private val secrets: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun registerSecret(value: String?) {
        if (value == null || value.length < MIN_SECRET_LENGTH) return
        secrets += value
        val encoded = URLEncoder.encode(value, Charsets.UTF_8.name())
        if (encoded != value) secrets += encoded
    }

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var result = text
        result = USER_INFO.replace(result, "$1$MASK@")
        result = SENSITIVE_QUERY_PARAM.replace(result, "$1$MASK")
        result = XTREAM_PATH.replace(result, "$1$MASK/$MASK/")
        result = AUTH_HEADER.replace(result, "$1: $MASK")
        // Les plus longs d'abord, pour ne pas laisser de fragment d'un secret qui en contient un autre.
        for (secret in secrets.sortedByDescending { it.length }) {
            result = result.replace(secret, MASK)
        }
        return result
    }

    companion object {
        const val MASK = "***"
        private const val MIN_SECRET_LENGTH = 3

        private val USER_INFO = Regex("""(?i)\b([a-z][a-z0-9+.\-]*://)[^/?#\s@]+@""")

        private val SENSITIVE_QUERY_PARAM = Regex(
            """(?i)([?&;](?:username|user|usr|login|password|passwd|pass|pwd|token|access_token|""" +
                """auth|key|apikey|api_key|secret|signature|sig|hash|mac|u|p)=)[^&#\s"'<>]*""",
        )

        private val XTREAM_PATH = Regex("""(?i)(/(?:live|movie|series|timeshift|timeshifts)/)[^/\s?#]+/[^/\s?#]+/""")

        private val AUTH_HEADER = Regex("""(?i)\b((?:proxy-)?authorization)\s*[:=]\s*(?:(?:basic|bearer|digest)\s+)?[^\s,;]+""")
    }
}
