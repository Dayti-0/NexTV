package tv.nextv.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import tv.nextv.app.R
import tv.nextv.app.data.db.ChannelListRow
import tv.nextv.app.data.db.MovieRow
import tv.nextv.app.data.db.SeriesRow
import tv.nextv.app.ui.components.FadingBottom
import tv.nextv.app.ui.components.PillButton
import tv.nextv.app.ui.components.PillStyle
import tv.nextv.app.ui.components.rememberWindowInfo

/**
 * Recherche de partout : un champ, puis les chaînes, les films et les séries qui correspondent, une
 * rangée chacun. Ouverte depuis le menu de Cinéma Premium ou la barre du haut (Live Center, Streaming).
 */
@Composable
internal fun GlobalSearch(
    query: String,
    onQuery: (String) -> Unit,
    onClose: () -> Unit,
    channels: LazyPagingItems<ChannelListRow>,
    movies: LazyPagingItems<MovieRow>,
    series: LazyPagingItems<SeriesRow>,
    content: ShelfContent,
    lastOpened: MutableState<OpenedItem?>,
    actions: HomeActions,
) {
    val window = rememberWindowInfo()
    val sizes = ShelfSizes.of(window)
    val padding = window.horizontalPadding
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(top = window.topPadding)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = padding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PillButton(stringResource(R.string.action_back), onClose, icon = Icons.AutoMirrored.Filled.ArrowBack, style = PillStyle.Ghost)
            Text(stringResource(R.string.search_everywhere), style = MaterialTheme.typography.headlineSmall)
        }
        SearchField(
            query,
            stringResource(R.string.search_everywhere_hint),
            onQuery,
            Modifier.padding(horizontal = padding, vertical = 16.dp).widthIn(max = 640.dp).focusRequester(fieldFocus),
        )
        val loading = listOf(channels, movies, series).any { it.loadState.refresh is LoadState.Loading }
        val empty = channels.itemCount == 0 && movies.itemCount == 0 && series.itemCount == 0
        when {
            // Rien de tapé : pas de texte d'aide, le champ suffit.
            query.isBlank() -> Unit
            empty && !loading -> Text(
                stringResource(R.string.search_everywhere_none, query),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = padding),
            )
            else -> {
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                FadingBottom({ listState.canScrollForward }, { listState.canScrollBackward }) { fade ->
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().then(fade),
                        contentPadding = PaddingValues(bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for ((section, count) in listOf(HomeSection.TV to channels.itemCount, HomeSection.MOVIES to movies.itemCount, HomeSection.SERIES to series.itemCount)) {
                            if (count == 0) continue
                            item(key = "search-${section.name}") {
                                SearchResults(
                                    section, channels, movies, series, sizes, padding, content, lastOpened, actions,
                                    title = if (section == HomeSection.TV) stringResource(R.string.search_everywhere_channels) else sectionLabel(section),
                                    rowKey = "search-${section.name}",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
