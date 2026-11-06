package tv.nextv.app.ui.guide

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
 * Image d'un programme : celle du guide, sinon, pour un film ou une série, celle de TMDB (lue après
 * un court arrêt sur le programme, gardée en cache). `null` : aucune image connue.
 */
@Composable
fun rememberProgrammeImage(programme: GuideProgramme?): String? {
    // Sans application NixTV (aperçus, captures d'écran) : image du guide seulement.
    val container = (LocalContext.current.applicationContext as? NexTvApplication)?.container
    val direct = programme?.imageUrl
    val image by produceState(direct, programme?.title, programme?.startMillis) {
        value = direct
        if (direct != null || programme == null) return@produceState
        val type = mediaTypeOf(programme) ?: return@produceState
        if (container == null || !container.hasTmdbKey()) return@produceState
        delay(LOOKUP_DELAY_MILLIS)
        val title = programme.title.substringBefore(" - ").trim()
        val key = "epg|${type.name}|${ItemKeys.normalize(title)}|${programme.year ?: ""}"
        val state = runCatching { container.metadata.details(key, type, title, programme.year) }.getOrNull()
        value = (state as? MetadataState.Available)?.details?.let { it.backdropUrl ?: it.posterUrl }
    }
    return image
}

/** Film ou série d'après le genre du guide ; les autres programmes (info, sport…) n'ont pas de fiche TMDB. */
private fun mediaTypeOf(programme: GuideProgramme): MediaType? {
    val genre = programme.category?.lowercase() ?: return null
    return when {
        MOVIE.containsMatchIn(genre) -> MediaType.MOVIE
        SERIES.containsMatchIn(genre) -> MediaType.SERIES
        else -> null
    }
}

private val MOVIE = Regex("""\b(film|téléfilm|telefilm|cinéma|cinema|movie)\b""")
private val SERIES = Regex("""\b(série|serie|feuilleton|séries|series|sitcom)\b""")
private const val LOOKUP_DELAY_MILLIS = 400L

/** Visuel du programme en fondu, ou rien (le reste de la mise en page ne bouge pas). */
@Composable
fun ProgrammeArt(programme: GuideProgramme?, modifier: Modifier) {
    val image = rememberProgrammeImage(programme)
    Crossfade(image, animationSpec = tween(300), label = "programme-art", modifier = modifier.clip(MaterialTheme.shapes.medium)) { url ->
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            if (url != null) {
                AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.Center, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
