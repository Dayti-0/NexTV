package tv.nextv.app.ui.startup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.nextv.app.R
import tv.nextv.app.data.sync.RemoteReceiveState
import tv.nextv.app.ui.components.FocusSurface
import tv.nextv.app.ui.components.NixTvLogo
import tv.nextv.app.ui.components.PillButton
import tv.nextv.app.ui.components.PillStyle
import tv.nextv.app.ui.components.rememberWindowInfo
import tv.nextv.app.ui.settings.remoteCodeStatus
import tv.nextv.app.ui.theme.NexTvColors
import tv.nextv.core.sync.SyncPairing

/**
 * Premier lancement (aucune playlist) : trois façons d'ajouter ses chaînes, côte à côte sur une télé.
 * - entrer son abonnement à la télécommande ([onAdd]) ;
 * - le taper sur son téléphone ([onAddFromPhone], QR code) ;
 * - se le faire installer à distance : le code de configuration est affiché directement ([remote]).
 * En bas : sauvegarde à restaurer ou télés reliées, dans les réglages ([onSettings]).
 */
@Composable
fun WelcomeScreen(
    onAdd: () -> Unit,
    animate: Boolean = true,
    onAddFromPhone: (() -> Unit)? = null,
    remote: RemoteReceiveState? = null,
    onSettings: (() -> Unit)? = null,
    now: Long = System.currentTimeMillis(),
) {
    val window = rememberWindowInfo()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(screenBackground())) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = window.horizontalPadding, vertical = if (window.isWide) 24.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // En-tête : le logo, puis la question.
            NixTvLogo(height = if (window.isWide) 40.dp else 44.dp)
            Spacer(Modifier.height(if (window.isWide) 14.dp else 18.dp))
            Text(
                stringResource(R.string.welcome_title),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.welcome_question),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(if (window.isWide) 20.dp else 22.dp))

            val manual: @Composable (Modifier) -> Unit = { modifier ->
                ChoiceCard(
                    icon = Icons.Filled.Edit,
                    title = stringResource(R.string.welcome_manual_title),
                    text = stringResource(R.string.welcome_manual_text),
                    action = stringResource(R.string.welcome_manual_action),
                    onClick = onAdd,
                    modifier = modifier.focusRequester(first),
                    primary = true,
                )
            }
            val phone: (@Composable (Modifier) -> Unit)? = onAddFromPhone?.let { open ->
                { modifier ->
                    ChoiceCard(
                        icon = Icons.Filled.Phone,
                        title = stringResource(R.string.welcome_phone_title),
                        text = stringResource(R.string.welcome_phone_text),
                        action = stringResource(R.string.welcome_phone_action),
                        onClick = open,
                        modifier = modifier,
                    )
                }
            }
            val code: (@Composable (Modifier) -> Unit)? = remote?.let { state -> { modifier -> RemoteChoice(state, now, modifier) } }
            val cards = listOfNotNull(manual, phone, code)
            if (window.isWide) {
                Row(
                    Modifier.widthIn(max = 1080.dp).fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    cards.forEach { card -> card(Modifier.weight(1f).fillMaxHeight()) }
                }
            } else {
                Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    cards.forEach { card -> card(Modifier.fillMaxWidth()) }
                }
            }

            onSettings?.let { open ->
                Spacer(Modifier.height(if (window.isWide) 14.dp else 20.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        stringResource(R.string.welcome_other),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    PillButton(stringResource(R.string.welcome_settings), open, icon = Icons.Filled.Settings, style = PillStyle.Ghost)
                }
            }
        }
    }
}

/** Carte d'un choix : icône, titre, explication, et l'action en bas (OK sur la carte). */
@Composable
private fun ChoiceCard(
    icon: ImageVector,
    title: String,
    text: String,
    action: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    FocusSurface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceContainer,
        focusedColor = colors.surfaceContainerHighest,
        focusScale = 1.03f,
    ) {
        CardBody(icon, title, text, primary) {
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(action, style = MaterialTheme.typography.titleSmall, color = colors.primary, fontWeight = FontWeight.SemiBold)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Troisième choix : rien à faire sur la télé, le code est déjà affiché ; la personne qui installe l'entre chez elle. */
@Composable
private fun RemoteChoice(state: RemoteReceiveState, now: Long, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    Box(modifier.clip(shape).background(colors.surfaceContainer).border(1.dp, colors.primary.copy(alpha = 0.35f), shape)) {
        CardBody(Icons.AutoMirrored.Filled.Send, stringResource(R.string.welcome_remote_title), stringResource(R.string.welcome_remote_text), primary = false) {
            Spacer(Modifier.weight(1f))
            val offer = (state as? RemoteReceiveState.Waiting)?.offer
            Text(
                offer?.let { SyncPairing.display(it.code) } ?: "––– –––",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
                color = if (offer != null) colors.primary else colors.onSurfaceVariant,
                maxLines = 1,
            )
            val (status, error) = remoteCodeStatus(state, now)
            Text(status, style = MaterialTheme.typography.bodySmall, color = if (error) colors.error else colors.onSurfaceVariant)
            if (state is RemoteReceiveState.Installing || state is RemoteReceiveState.Installed) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun CardBody(icon: ImageVector, title: String, text: String, primary: Boolean, bottom: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxHeight().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (primary) {
                        Brush.linearGradient(listOf(NexTvColors.Primary, NexTvColors.Accent))
                    } else {
                        Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.surfaceContainerHighest))
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (primary) Color.White else MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(2.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        bottom()
    }
}
