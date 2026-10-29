package tv.nextv.app.ui.player

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import tv.nextv.app.R
import tv.nextv.app.data.db.ProgrammeEntity
import tv.nextv.app.ui.common.appViewModel
import java.text.DateFormat
import java.util.Date

@Composable
fun PlayerScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val viewModel = appViewModel { container, handle -> PlayerViewModel(container, handle, context) }
    val player by viewModel.player.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    LifecycleStartEffect(viewModel) {
        viewModel.onStart()
        onStopOrDispose { viewModel.onStop() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = true
                    keepScreenOn = true
                    requestFocus()
                }
            },
            update = { view -> view.player = player },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            Text(state.channelName, style = MaterialTheme.typography.titleMedium, color = Color.White)
            state.now?.let { ProgrammeLine(R.string.player_now, it) }
            state.next?.let { ProgrammeLine(R.string.player_next, it) }
            state.notices.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFD27F))
            }
        }

        if (state.buffering && state.error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }

        state.error?.let { message ->
            Card(Modifier.align(Alignment.Center).widthIn(max = 480.dp).padding(24.dp)) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.player_error_title), style = MaterialTheme.typography.titleMedium)
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = viewModel::retry) { Text(stringResource(R.string.action_retry)) }
                        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgrammeLine(labelRes: Int, programme: ProgrammeEntity) {
    val format = DateFormat.getTimeInstance(DateFormat.SHORT)
    val time = "${format.format(Date(programme.startMillis))} – ${format.format(Date(programme.stopMillis))}"
    Text(
        stringResource(labelRes, time, programme.title),
        style = MaterialTheme.typography.bodySmall,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
