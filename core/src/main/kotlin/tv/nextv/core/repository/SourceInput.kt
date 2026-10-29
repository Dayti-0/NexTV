package tv.nextv.core.repository

import tv.nextv.core.error.InvalidUrlReason
import tv.nextv.core.error.SourceError
import tv.nextv.core.model.Credentials
import tv.nextv.core.model.EpgSettings
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.model.SourceType
import tv.nextv.core.net.RemoteUrlValidator

/** Saisie de l'utilisateur pour créer ou modifier une source. */
data class SourceInput(
    val name: String,
    val location: SourceLocation,
    val type: SourceType = SourceType.M3U,
    val epg: EpgSettings = EpgSettings(),
    val userAgent: String? = null,
    val credentials: CredentialsChange = CredentialsChange.Keep,
)

sealed interface CredentialsChange {
    /** Conserver les identifiants existants (ou aucun pour une nouvelle source). */
    data object Keep : CredentialsChange

    data object Remove : CredentialsChange

    data class Replace(val credentials: Credentials) : CredentialsChange
}

enum class SourceInputField { NAME, LOCATION, EPG_LOCATION, USER_AGENT, USERNAME }

data class InputProblem(val field: SourceInputField, val error: SourceError)

class InvalidSourceInputException(val problems: List<InputProblem>) :
    IllegalArgumentException("Invalid source input: ${problems.joinToString { "${it.field}=${it.error.code}" }}")

/**
 * Validation et normalisation d'une [SourceInput] :
 * URL vérifiées (HTTP/HTTPS uniquement), identifiants intégrés à l'URL extraits,
 * nom par défaut déduit de l'emplacement, User-Agent limité à l'ASCII imprimable.
 */
object SourceInputValidator {

    sealed interface Result {
        data class Valid(val input: SourceInput) : Result
        data class Invalid(val problems: List<InputProblem>) : Result
    }

    fun validate(input: SourceInput): Result {
        val problems = mutableListOf<InputProblem>()
        var credentials = input.credentials

        val location = when (val location = input.location) {
            is SourceLocation.Remote -> when (val result = RemoteUrlValidator.validate(location.url)) {
                is RemoteUrlValidator.Result.Invalid -> {
                    problems += InputProblem(SourceInputField.LOCATION, result.error)
                    location
                }
                is RemoteUrlValidator.Result.Valid -> {
                    val embedded = result.embeddedCredentials
                    if (embedded != null && credentials !is CredentialsChange.Replace) {
                        credentials = CredentialsChange.Replace(embedded)
                    }
                    SourceLocation.Remote(result.url.toString())
                }
            }
            is SourceLocation.LocalDocument -> {
                if (location.uri.isBlank()) {
                    problems += InputProblem(SourceInputField.LOCATION, SourceError.InvalidUrl(InvalidUrlReason.EMPTY))
                }
                // Une source locale n'est jamais authentifiée.
                if (credentials is CredentialsChange.Replace) credentials = CredentialsChange.Remove
                location
            }
        }

        val epgLocation = when (val epg = input.epg.location) {
            is SourceLocation.Remote -> when (val result = RemoteUrlValidator.validate(epg.url)) {
                is RemoteUrlValidator.Result.Invalid -> {
                    problems += InputProblem(SourceInputField.EPG_LOCATION, result.error)
                    epg
                }
                // Les identifiants éventuels restent dans l'URL du guide : ils ne concernent que lui.
                is RemoteUrlValidator.Result.Valid -> SourceLocation.Remote(epg.url.trim())
            }
            is SourceLocation.LocalDocument -> {
                if (epg.uri.isBlank()) {
                    problems += InputProblem(SourceInputField.EPG_LOCATION, SourceError.InvalidUrl(InvalidUrlReason.EMPTY))
                }
                epg
            }
            null -> null
        }

        val userAgent = input.userAgent?.trim()?.ifEmpty { null }
        if (userAgent != null && userAgent.any { it.code !in 0x20..0x7e }) {
            problems += InputProblem(SourceInputField.USER_AGENT, SourceError.InvalidUrl(InvalidUrlReason.INVALID_CHARACTERS))
        }

        (credentials as? CredentialsChange.Replace)?.credentials?.let { value ->
            val username = value.username
            if (username.isBlank() || ':' in username || username.any { it.isISOControl() } || value.password.any { it.isISOControl() }) {
                problems += InputProblem(SourceInputField.USERNAME, SourceError.InvalidUrl(InvalidUrlReason.INVALID_CHARACTERS))
            }
        }

        if (problems.isNotEmpty()) return Result.Invalid(problems)
        return Result.Valid(
            input.copy(
                name = input.name.trim().ifEmpty { defaultName(location) },
                location = location,
                epg = input.epg.copy(location = epgLocation),
                userAgent = userAgent,
                credentials = credentials,
            ),
        )
    }

    fun defaultName(location: SourceLocation): String = when (location) {
        is SourceLocation.Remote -> RemoteUrlValidator.parseOrNull(location.url)?.host ?: DEFAULT_NAME
        is SourceLocation.LocalDocument ->
            location.displayName?.substringBeforeLast('.')?.trim()?.ifEmpty { null } ?: DEFAULT_NAME
    }

    private const val DEFAULT_NAME = "Playlist"
}
