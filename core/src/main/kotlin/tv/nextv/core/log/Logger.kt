package tv.nextv.core.log

/** Journalisation minimale, implémentée côté Android par `android.util.Log`. */
interface Logger {
    fun debug(tag: String, message: String)
    fun info(tag: String, message: String)
    fun warn(tag: String, message: String, throwable: Throwable? = null)
    fun error(tag: String, message: String, throwable: Throwable? = null)

    object None : Logger {
        override fun debug(tag: String, message: String) = Unit
        override fun info(tag: String, message: String) = Unit
        override fun warn(tag: String, message: String, throwable: Throwable?) = Unit
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }
}

/**
 * Décorateur qui masque toute donnée sensible avant de déléguer : URL avec identifiants,
 * paramètres `password=`, chemins Xtream, en-têtes `Authorization`, secrets enregistrés.
 *
 * Les exceptions sont remplacées par une copie dont les messages sont expurgés
 * (la pile d'appels est conservée) : un message d'exception contient souvent l'URL complète.
 */
class RedactingLogger(
    private val delegate: Logger,
    private val redactor: Redactor,
) : Logger {

    override fun debug(tag: String, message: String) = delegate.debug(tag, redactor.redact(message))

    override fun info(tag: String, message: String) = delegate.info(tag, redactor.redact(message))

    override fun warn(tag: String, message: String, throwable: Throwable?) =
        delegate.warn(tag, redactor.redact(message), throwable?.let(::sanitize))

    override fun error(tag: String, message: String, throwable: Throwable?) =
        delegate.error(tag, redactor.redact(message), throwable?.let(::sanitize))

    private fun sanitize(throwable: Throwable): Throwable = sanitize(throwable, depth = 0)

    private fun sanitize(throwable: Throwable, depth: Int): Throwable {
        val cause = throwable.cause?.takeIf { it !== throwable && depth < MAX_CAUSE_DEPTH }
        val copy = RedactedThrowable(
            originalClassName = throwable.javaClass.name,
            redactedMessage = throwable.message?.let(redactor::redact),
            redactedCause = cause?.let { sanitize(it, depth + 1) },
        )
        copy.stackTrace = throwable.stackTrace
        return copy
    }

    private companion object {
        const val MAX_CAUSE_DEPTH = 8
    }
}

/** Copie expurgée d'une exception ; [toString] reprend le nom de la classe d'origine. */
class RedactedThrowable(
    val originalClassName: String,
    redactedMessage: String?,
    redactedCause: Throwable?,
) : Throwable(redactedMessage, redactedCause) {
    override fun toString(): String =
        message?.let { "$originalClassName: $it" } ?: originalClassName
}
