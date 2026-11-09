package tv.nextv.app.ui.phonesetup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import tv.nextv.app.R
import tv.nextv.app.ui.common.appContainer
import tv.nextv.app.ui.components.PillButton
import tv.nextv.app.ui.components.PillStyle
import tv.nextv.app.ui.components.rememberWindowInfo

/**
 * Configurer la playlist depuis le téléphone : la télé affiche un QR code ; le téléphone (même Wi-Fi) ouvre
 * la page, saisit un compte Xtream ou un lien M3U, et l'ajout s'ouvre prérempli sur la télé. La page ne
 * fonctionne que tant que cet écran est ouvert.
 */
@Composable
fun PhoneSetupScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val container = appContainer()
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf<String?>(null) }
    var noNetwork by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        // Playlist reçue : l'appli ouvre l'ajout prérempli (comme une playlist transmise par adb).
        val server = PhoneSetupServer { import -> container.playlistImport.value = import }
        val ip = PhoneSetupServer.localAddress()
        if (ip == null) {
            noNetwork = true
        } else {
            val port = runCatching { server.start(scope) }.getOrNull()
            if (port == null) noNetwork = true else address = "http://$ip:$port/${server.token}"
        }
        onDispose { server.stop() }
    }
    PhoneSetupContent(address, noNetwork, onBack)
}

@Composable
fun PhoneSetupContent(address: String?, noNetwork: Boolean, onBack: () -> Unit) {
    val window = rememberWindowInfo()
    val colors = MaterialTheme.colorScheme
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { backFocus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(colors.background).padding(horizontal = window.horizontalPadding, vertical = window.topPadding)) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.phone_setup_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                if (noNetwork) {
                    Text(stringResource(R.string.phone_setup_no_network), style = MaterialTheme.typography.bodyLarge, color = colors.error)
                } else {
                    Step(1, stringResource(R.string.phone_setup_step_wifi))
                    Step(2, stringResource(R.string.phone_setup_step_scan))
                    Step(3, stringResource(R.string.phone_setup_step_send))
                    address?.let {
                        Text(stringResource(R.string.phone_setup_or_type), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        Text(it, style = MaterialTheme.typography.titleMedium, color = colors.primary)
                    }
                }
                PillButton(
                    stringResource(R.string.action_back),
                    onBack,
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    style = PillStyle.Ghost,
                    modifier = Modifier.padding(top = 12.dp).focusRequester(backFocus),
                )
            }
            if (address != null) {
                val matrix = remember(address) { qrMatrix(address) }
                // Fond blanc et marge : lisible par tous les téléphones, même sur un thème sombre.
                Box(Modifier.clip(RoundedCornerShape(18.dp)).background(Color.White).padding(16.dp)) {
                    QrCode(matrix, Modifier.size(300.dp))
                }
            }
        }
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Text(number.toString(), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
        }
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** QR code dessiné case par case (noir sur le fond blanc qui l'entoure). */
@Composable
private fun QrCode(matrix: BitMatrix, modifier: Modifier) {
    Canvas(modifier) {
        val cell = size.width / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

private fun qrMatrix(text: String): BitMatrix =
    QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
