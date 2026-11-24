package tv.nextv.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import tv.nextv.app.R
import tv.nextv.app.ui.player.RadioTitleService
import tv.nextv.core.audio.NowPlaying
import tv.nextv.core.catalog.ChannelLineup
import tv.nextv.core.catalog.InternetRadios

/** Services des radios qui donnent leur morceau en cours, fournis par l'activité ; sans eux (aperçus, tests) : rien n'est lu. */
val LocalRadioTitles = staticCompositionLocalOf<RadioTitleService?> { null }

private val RADIO_GROUP = Regex("""(?i)\b(web )?radios?\b""")

/** Chaîne qui est une radio : radio connue de l'appli, nom de radio (règle de la rubrique Radios), ou catégorie de radios. */
fun isRadio(name: String, groupTitle: String?): Boolean =
    InternetRadios.byName(name) != null || InternetRadios.titlesFor(name) != null || ChannelLineup.isRadioName(name) ||
        groupTitle?.let { RADIO_GROUP.containsMatchIn(it) } == true

/**
 * Texte affiché quand le guide n'a pas de programme pour la chaîne : « Pas d'information de programme », sauf pour une
 * radio, qui n'est jamais dans le guide. Une radio dont le service publie le morceau en cours l'affiche (« ♪ Artiste –
 * Titre », relu à la fin du morceau ou toutes les 30 s, seulement si [fetch]) ; sinon son genre, ou rien (chaîne vide).
 * Le morceau est propre à la chaîne affichée : à un changement de chaîne (aperçu, vignette réutilisée par la liste), celui
 * de la précédente disparaît aussitôt, remplacé par le dernier connu de la nouvelle radio en attendant la réponse.
 */
@Composable
fun noProgrammeText(name: String, groupTitle: String?, fetch: Boolean = true): String {
    if (!isRadio(name, groupTitle)) return stringResource(R.string.channels_no_epg)
    val service = LocalRadioTitles.current
    val source = InternetRadios.titlesFor(name)
    val playing = remember(name, fetch, service) { mutableStateOf(source?.takeIf { fetch }?.let { service?.known(it) }) }
    LaunchedEffect(name, fetch, service) {
        if (!fetch || service == null || source == null) return@LaunchedEffect
        while (true) {
            val song = service.cached(source)
            playing.value = song
            val untilEnd = song?.untilMillis?.let { it - System.currentTimeMillis() + 3_000 } ?: 30_000L
            delay(untilEnd.coerceIn(15_000L, 60_000L))
        }
    }
    playing.value?.let { song -> return if (song.artist.isNullOrBlank()) song.title else "♪ ${song.label}" }
    return InternetRadios.byName(name)?.genre.orEmpty()
}
