package tv.nextv.app.ui.home

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
private fun OpenedItem.pending(): Boolean = visit != HomeVisit.current

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
 * liste ensuite ne déplace plus le focus. La demande est répétée brièvement : l'élément peut ne
 * devenir sélectionnable qu'une fois la liste remise à sa place.
 */
@Composable
internal fun Modifier.restoreFocus(key: String, lastOpened: MutableState<OpenedItem?>): Modifier {
    val opened = lastOpened.value
    if (opened?.key != key || !opened.pending()) return this
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(FOCUS_ATTEMPTS) {
            if (runCatching { requester.requestFocus() }.isSuccess) {
                lastOpened.value = null
                return@LaunchedEffect
            }
            delay(FOCUS_RETRY_MILLIS)
        }
        lastOpened.value = null
    }
    return focusRequester(requester)
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

private const val FOCUS_ATTEMPTS = 8
private const val FOCUS_RETRY_MILLIS = 120L
