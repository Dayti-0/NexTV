package tv.nextv.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tv.nextv.app.R
import tv.nextv.app.data.settings.Reminder
import tv.nextv.app.ui.components.PillButton
import tv.nextv.app.ui.components.PillStyle
import tv.nextv.app.ui.theme.NexTvColors
import java.text.DateFormat
import java.util.Date

/**
 * Bandeau d'un rappel : le programme commence bientôt. Il ne prend pas le focus de l'écran affiché :
 * sur télécommande, OK et Retour lui sont adressés tant qu'il est visible ; boutons au toucher.
 */
@Composable
fun ReminderBanner(reminder: Reminder, now: Long, onWatch: (() -> Unit)?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 12.dp,
        modifier = modifier.widthIn(max = 520.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Notifications, contentDescription = null, tint = NexTvColors.Warning, modifier = Modifier.size(20.dp))
                val minutes = ((reminder.startMillis - now + 59_999) / 60_000).toInt()
                Text(
                    if (minutes > 0) {
                        stringResource(R.string.reminder_soon, minutes)
                    } else {
                        stringResource(R.string.reminder_started, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(reminder.startMillis)))
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = NexTvColors.Warning,
                )
            }
            Text(reminder.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            reminder.channelName?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Text(
                stringResource(if (onWatch != null) R.string.reminder_keys else R.string.reminder_keys_dismiss),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onWatch != null) PillButton(stringResource(R.string.guide_watch), onWatch, icon = Icons.Filled.PlayArrow, style = PillStyle.Primary)
                PillButton(stringResource(R.string.action_close), onDismiss, style = PillStyle.Ghost)
            }
        }
    }
}
