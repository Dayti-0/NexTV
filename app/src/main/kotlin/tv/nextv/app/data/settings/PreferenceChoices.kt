package tv.nextv.app.data.settings

/** Son de l'aperçu vidéo de l'accueil. */
enum class PreviewVolume(val volume: Float) {
    OFF(0f),
    LOW(0.15f),
    MEDIUM(0.4f),
}

/**
 * Fréquence de mise à jour automatique de la playlist. [maxAgeMillis] : âge au-delà duquel elle est
 * retéléchargée à l'ouverture de l'application (0 : à chaque ouverture) et pendant son utilisation
 * (au moins une fois par jour pour « à chaque démarrage », les TV restant souvent allumées).
 */
enum class PlaylistRefresh(val maxAgeMillis: Long) {
    EVERY_START(0),
    HOURS_12(12 * HOUR),
    HOURS_24(24 * HOUR),
    DAYS_2(2 * DAY),
    DAYS_3(3 * DAY),
    DAYS_4(4 * DAY),
    DAYS_5(5 * DAY),
    DAYS_6(6 * DAY),
    DAYS_7(7 * DAY),
    ;

    /** Âge à partir duquel la playlist est mise à jour pendant que l'application reste ouverte. */
    val whileRunningMillis: Long get() = if (this == EVERY_START) DAY else maxAgeMillis

    /** La playlist mise à jour pour la dernière fois à [lastSuccess] doit l'être de nouveau à l'ouverture. */
    fun dueAtStart(lastSuccess: Long?, now: Long): Boolean = lastSuccess == null || now - lastSuccess >= maxAgeMillis

    /** La playlist doit être mise à jour pendant que l'application reste ouverte. */
    fun dueWhileRunning(lastSuccess: Long?, now: Long): Boolean = lastSuccess == null || now - lastSuccess >= whileRunningMillis
}

private const val HOUR = 60 * 60_000L
private const val DAY = 24 * HOUR
