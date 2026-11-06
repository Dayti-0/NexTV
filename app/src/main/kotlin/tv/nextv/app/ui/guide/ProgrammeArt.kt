package tv.nextv.app.ui.guide

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.MaterialTheme
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import tv.nextv.app.data.catalog.MetadataState
import tv.nextv.app.data.db.ItemKeys
import androidx.compose.ui.platform.LocalContext
import tv.nextv.app.NexTvApplication
import tv.nextv.core.meta.MediaType

/**
 * Image d'un programme : celle du guide, sinon celle de TMDB (lue après un court arrêt sur le
 * programme, gardée en cache). Films et séries : fiche la plus proche ; autres programmes
 * (téléréalité, magazines, divertissement, documentaires…) : seulement une émission portant
 * exactement ce titre. Informations, météo et sport n'en ont pas. `null` : aucune image connue.
 */
@Composable
fun rememberProgrammeImage(programme: GuideProgramme?): String? {
    // Sans application NixTV (aperçus, captures d'écran) : image du guide seulement.
    val container = (LocalContext.current.applicationContext as? NexTvApplication)?.container
    val direct = programme?.imageUrl
    val image by produceState(direct, programme?.title, programme?.startMillis) {
        value = direct
        if (direct != null || programme == null) return@produceState
        val lookup = lookupOf(programme) ?: return@produceState
        if (container == null || !container.hasTmdbKey()) return@produceState
        delay(LOOKUP_DELAY_MILLIS)
        val title = programme.title.substringBefore(" - ").trim()
        val exact = if (lookup.exact) "x|" else ""
        val key = "epg|${lookup.type.name}|$exact${ItemKeys.normalize(title)}|${programme.year ?: ""}"
        val state = runCatching { container.metadata.details(key, lookup.type, title, programme.year, lookup.exact) }.getOrNull()
        value = (state as? MetadataState.Available)?.details?.let { it.backdropUrl ?: it.posterUrl }
    }
    return image
}

private class Lookup(val type: MediaType, val exact: Boolean)

/** Recherche TMDB d'après le genre du guide, `null` pour les programmes qui n'ont pas de fiche. */
private fun lookupOf(programme: GuideProgramme): Lookup? {
    val genre = programme.category?.lowercase().orEmpty()
    val title = programme.title.lowercase()
    return when {
        MOVIE.containsMatchIn(genre) -> Lookup(MediaType.MOVIE, exact = false)
        SERIES.containsMatchIn(genre) -> Lookup(MediaType.SERIES, exact = false)
        NO_SHEET.containsMatchIn(genre) || NO_SHEET_TITLE.containsMatchIn(title) -> null
        else -> Lookup(MediaType.SERIES, exact = true)
    }
}

private val MOVIE = Regex("""\b(film|téléfilm|telefilm|cinéma|cinema|movie)\b""")
private val SERIES = Regex("""\b(série|serie|feuilleton|séries|series|sitcom)\b""")

/** Genres sans fiche TMDB utile (direct, actualité, sport…). */
private val NO_SHEET = Regex("""\b(information|informations|info|journal|actualité|actualites|actualités|météo|meteo|sport|sports|football|téléachat|teleachat|religion|politique|débat|debat)\b""")

/** Titres de journaux, météo et fins d'antenne : aucune image à chercher. */
private val NO_SHEET_TITLE = Regex("""^(le |la |les )?(journal|jt|météo|meteo|flash|info|infos|édition|edition|téléachat|teleachat|programmes? de la nuit|fin des programmes)\b|^(le )?\d{1,2} ?h\b""")

private const val LOOKUP_DELAY_MILLIS = 400L

/** Visuel du programme en fondu, ou rien : une image introuvable ne laisse pas de cadre vide. */
@Composable
fun ProgrammeArt(programme: GuideProgramme?, modifier: Modifier) {
    val image = rememberProgrammeImage(programme)
    var failed by remember(image) { mutableStateOf(false) }
    if (image == null || failed) return
    Crossfade(image, animationSpec = tween(300), label = "programme-art", modifier = modifier.clip(MaterialTheme.shapes.medium)) { url ->
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
