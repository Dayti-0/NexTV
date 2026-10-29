package tv.nextv.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Brand = Color(0xFF2F80ED)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF00315F),
    primaryContainer = Color(0xFF1B4B85),
    secondary = Color(0xFFB8C7DA),
    background = Color(0xFF101418),
    surface = Color(0xFF101418),
    surfaceVariant = Color(0xFF2A3038),
    error = Color(0xFFFFB4AB),
)

private val LightColors = lightColorScheme(
    primary = Brand,
    primaryContainer = Color(0xFFD5E3FF),
    secondary = Color(0xFF545F70),
    error = Color(0xFFBA1A1A),
)

@Composable
fun NexTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
