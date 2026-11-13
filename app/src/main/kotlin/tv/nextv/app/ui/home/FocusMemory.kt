package tv.nextv.app.ui.home

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay

/**
 * Dernier élément ouvert depuis l'accueil (chaîne, film, épisode, programme) : sa clé, sa place
 * dans sa liste et la rangée qui le contient ([row], interfaces à rangées). Au retour, la liste
 * revient à cette place et l'élément reprend le focus, sans avoir à le rechercher.
 */
@Immutable
data class OpenedItem(
    val key: String,
    val index: Int = -1,
    val row: String? = null,
    /** Affichage de l'accueil d'où il a été ouvert : rien n'est rendu tant qu'on y est encore. */
    val visit: Int = HomeVisit.current,
)

/**
 * Affichages successifs de l'accueil : chaque retour (lecteur, fiche) en recrée un. Pendant la
 * transition vers le lecteur, l'accueil quitté est encore affiché ; l'élément qu'on vient d'ouvrir
 * ne doit pas y reprendre le focus, sans quoi sa place serait oubliée avant le retour.
 */
internal object HomeVisit {
    var current = 0
        private set
    private var next = 1

    fun start(): Int = next++.also { current = it }

    fun enter(visit: Int) {
        current = visit
    }
}

/** Nouvel affichage de l'accueil (gardé tant que celui-ci reste composé). */
@Composable
internal fun rememberHomeVisit(): Int {
    val visit = remember { HomeVisit.start() }
    HomeVisit.enter(visit)
    return visit
}

/** L'élément est à rendre : ouvert depuis un affichage précédent de l'accueil. */
internal fun OpenedItem.pending(): Boolean = visit != HomeVisit.current

private val OpenedItemSaver = Saver<MutableState<OpenedItem?>, List<Any>>(
    save = { state -> state.value?.let { listOf(it.key, it.index, it.row.orEmpty(), it.visit) } ?: emptyList() },
    restore = { saved ->
        mutableStateOf(
            if (saved.size == 4) OpenedItem(saved[0] as String, saved[1] as Int, (saved[2] as String).ifEmpty { null }, saved[3] as Int) else null,
        )
    },
)

/** Gardé à travers la navigation (lecteur, fiche) comme le reste de l'état de l'accueil. */
@Composable
fun rememberLastOpened(): MutableState<OpenedItem?> = rememberSaveable(saver = OpenedItemSaver) { mutableStateOf(null) }

/**
 * Rend le focus à l'élément [key] si c'est le dernier ouvert, puis l'oublie : faire défiler la
 * liste ensuite ne déplace plus le focus. La demande est répétée jusqu'à ce que l'élément ait vraiment le focus :
 * il peut ne devenir sélectionnable qu'une fois la liste remise à sa place, et au retour du lecteur l'écran le refuse
 * un instant (box Raspberry Pi) ; la liste reprenait alors d'elle-même l'élément choisi avant de partir.
 *
 * Chaîne changée dans le lecteur (zapping) : la dernière regardée ([LocalLastLiveChannel]) prend le focus si elle est
 * dans la même liste ([row] : sa rangée) ; elle devient alors le dernier élément ouvert, celle ouverte au départ ne le
 * cherche plus. Celle-ci attend un instant, et ne le prend que si l'autre n'est pas là.
 */
@Composable
internal fun Modifier.restoreFocus(key: String, lastOpened: MutableState<OpenedItem?>, row: String? = null): Modifier {
    val opened = lastOpened.value
    if (opened == null || !opened.pending()) return this
    val watched = LocalLastLiveChannel.current?.takeIf { it != opened.key && opened.key.startsWith(LIVE_KEY) && opened.row == row }
    if (key != opened.key && key != watched) return this
    val deferred = watched != null && key == opened.key
    val requester = remember { FocusRequester() }
    val focus = remember { HeldFocus() }
    LaunchedEffect(Unit) {
        // Dernière chaîne regardée, présente dans la liste : c'est elle à sélectionner, plus celle ouverte au départ.
        if (key != opened.key) lastOpened.value = opened.copy(key = key)
        if (deferred) delay(WATCHED_FIRST_MILLIS)
        repeat(FOCUS_ATTEMPTS) {
            // Demande refusée (rendue `false`) : pas encore sélectionnable. Acceptée mais détournée : la liste, qui
            // n'avait pas encore le focus, l'a donné à l'élément qu'elle avait retenu en partant (focusRestorer).
            // Dans les deux cas, nouvel essai ; la liste ayant alors le focus, la demande aboutit.
            val accepted = runCatching { requester.requestFocus() }.getOrDefault(false)
            if (accepted && focus.held) {
                lastOpened.value = null
                return@LaunchedEffect
            }
            if (!accepted) delay(FOCUS_RETRY_MILLIS) else withFrameNanos { }
        }
        lastOpened.value = null
    }
    return focusRequester(requester).onFocusChanged { focus.held = it.hasFocus }
}

/** L'élément a le focus (suivi sans recomposer). */
private class HeldFocus {
    var held = false
}

/**
 * Remet la grille à la place du dernier élément ouvert dès que ses éléments sont là (au retour,
 * la liste se remplit après le premier affichage et reviendrait sinon en haut).
 */
@Composable
internal fun RestoreScroll(state: LazyGridState, lastOpened: MutableState<OpenedItem?>, itemCount: Int, row: String? = null) {
    val target = lastOpened.value?.takeIf { it.row == row && it.index >= 0 && it.pending() } ?: return
    LaunchedEffect(target, itemCount > target.index) {
        if (itemCount <= target.index) return@LaunchedEffect
        val visible = state.layoutInfo.visibleItemsInfo.any { it.index == target.index }
        if (!visible) state.scrollToItem(target.index)
    }
}

@Composable
internal fun RestoreScroll(state: LazyListState, lastOpened: MutableState<OpenedItem?>, itemCount: Int, row: String? = null) {
    val target = lastOpened.value?.takeIf { it.row == row && it.index >= 0 && it.pending() } ?: return
    LaunchedEffect(target, itemCount > target.index) {
        if (itemCount <= target.index) return@LaunchedEffect
        val visible = state.layoutInfo.visibleItemsInfo.any { it.index == target.index }
        if (!visible) state.scrollToItem(target.index)
    }
}

/** Dernière chaîne lue en plein écran (clé, voir AppContainer.lastLiveChannel). */
internal val LocalLastLiveChannel = androidx.compose.runtime.compositionLocalOf<String?> { null }

/**
 * Chaîne ouverte depuis la liste, puis changée dans le lecteur (zapping, menu des chaînes) : au retour, c'est la
 * dernière regardée qui reprend le focus (et l'aperçu continue sur elle), si elle est dans cette liste ([row] : sa
 * rangée ; [indexOf] : sa place, -1 si elle n'y est pas). Sinon, celle ouverte au départ, comme avant.
 *
 * Appelé avant de composer les éléments de la liste : aucun ne doit reprendre le focus avant que le dernier élément
 * ouvert soit remplacé (un effet arriverait trop tard, après celui de la chaîne ouverte au départ). Jamais sauté : la
 * liste se remplit souvent juste après le retour, et c'est alors que la chaîne s'y trouve.
 */
@Composable
@androidx.compose.runtime.NonRestartableComposable
internal fun FollowLastChannel(lastOpened: MutableState<OpenedItem?>, row: String? = null, indexOf: (String) -> Int) {
    val watched = LocalLastLiveChannel.current ?: return
    val opened = lastOpened.value ?: return
    if (!opened.pending() || opened.row != row || opened.key == watched || !opened.key.startsWith(LIVE_KEY)) return
    val index = indexOf(watched)
    if (index >= 0) lastOpened.value = opened.copy(key = watched, index = index)
}

private const val LIVE_KEY = "live:"

/** Rangée (interfaces à rangées) contenant le dernier élément ouvert : remise en vue. */
@Composable
internal fun RestoreRow(state: LazyListState, lastOpened: MutableState<OpenedItem?>, rowKeys: List<String>) {
    val row = lastOpened.value?.takeIf { it.pending() }?.row ?: return
    val index = rowKeys.indexOf(row)
    LaunchedEffect(row, index) {
        if (index < 0) return@LaunchedEffect
        val visible = state.layoutInfo.visibleItemsInfo.any { it.key == row }
        if (!visible) state.scrollToItem(index)
    }
}

private const val FOCUS_ATTEMPTS = 16

/** Attente de la chaîne ouverte au départ, le temps que la dernière regardée prenne le focus si elle est là. */
private const val WATCHED_FIRST_MILLIS = 300L
private const val FOCUS_RETRY_MILLIS = 120L
