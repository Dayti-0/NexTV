package tv.nextv.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tv.nextv.app.R

/** Palette NexTV : sombre, pensée pour les écrans TV comme pour les téléphones. */
object NexTvColors {
    val Background = Color(0xFF0A0E14)
    val Surface = Color(0xFF10151D)
    val SurfaceContainerLow = Color(0xFF141A23)
    val SurfaceContainer = Color(0xFF18202B)
    val SurfaceContainerHigh = Color(0xFF1E2734)
    val SurfaceContainerHighest = Color(0xFF263142)
    val Outline = Color(0xFF334155)
    val OutlineVariant = Color(0xFF242E3D)

    val Primary = Color(0xFF4C8DFF)
    val PrimaryContainer = Color(0xFF173468)
    val Accent = Color(0xFF8B5CF6)

    /** Couleur « en direct » : programme en cours, progression. */
    val Live = Color(0xFF2ED3A0)
    val Warning = Color(0xFFFFB547)
    val Error = Color(0xFFFF6B6B)

    val TextPrimary = Color(0xFFE8EDF5)
    val TextSecondary = Color(0xFF9AA6B8)
    val TextMuted = Color(0xFF6B778A)

    /** Teintes des monogrammes et des bannières de playlist, choisies d'après le nom. */
    val Swatches = listOf(
        Color(0xFF4C8DFF) to Color(0xFF8B5CF6),
        Color(0xFF14B8A6) to Color(0xFF0EA5E9),
        Color(0xFFF97316) to Color(0xFFEF4444),
        Color(0xFFA855F7) to Color(0xFFEC4899),
        Color(0xFF22C55E) to Color(0xFF14B8A6),
        Color(0xFFEAB308) to Color(0xFFF97316),
        Color(0xFF6366F1) to Color(0xFF06B6D4),
        Color(0xFFE11D48) to Color(0xFF9333EA),
    )

    fun swatchFor(key: String): Pair<Color, Color> = Swatches[(key.hashCode() and Int.MAX_VALUE) % Swatches.size]
}

val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private val DarkColors = darkColorScheme(
    primary = NexTvColors.Primary,
    onPrimary = Color.White,
    primaryContainer = NexTvColors.PrimaryContainer,
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = NexTvColors.Live,
    onSecondary = Color(0xFF00382A),
    tertiary = NexTvColors.Warning,
    onTertiary = Color(0xFF3D2600),
    background = NexTvColors.Background,
    onBackground = NexTvColors.TextPrimary,
    surface = NexTvColors.Surface,
    onSurface = NexTvColors.TextPrimary,
    surfaceVariant = NexTvColors.SurfaceContainerHigh,
    onSurfaceVariant = NexTvColors.TextSecondary,
    surfaceContainerLowest = NexTvColors.Background,
    surfaceContainerLow = NexTvColors.SurfaceContainerLow,
    surfaceContainer = NexTvColors.SurfaceContainer,
    surfaceContainerHigh = NexTvColors.SurfaceContainerHigh,
    surfaceContainerHighest = NexTvColors.SurfaceContainerHighest,
    outline = NexTvColors.Outline,
    outlineVariant = NexTvColors.OutlineVariant,
    error = NexTvColors.Error,
    onError = Color(0xFF3B0A0A),
)

private fun TextStyle.inter(weight: FontWeight? = null) =
    copy(fontFamily = InterFamily, fontWeight = weight ?: fontWeight)

private val NexTvTypography = Typography().run {
    Typography(
        displayLarge = displayLarge.inter(FontWeight.Bold),
        displayMedium = displayMedium.inter(FontWeight.Bold),
        displaySmall = displaySmall.inter(FontWeight.Bold),
        headlineLarge = headlineLarge.inter(FontWeight.Bold),
        headlineMedium = headlineMedium.inter(FontWeight.SemiBold),
        headlineSmall = headlineSmall.inter(FontWeight.SemiBold),
        titleLarge = titleLarge.inter(FontWeight.SemiBold),
        titleMedium = titleMedium.inter(FontWeight.SemiBold),
        titleSmall = titleSmall.inter(FontWeight.SemiBold),
        bodyLarge = bodyLarge.inter(),
        bodyMedium = bodyMedium.inter(),
        bodySmall = bodySmall.inter(),
        labelLarge = labelLarge.inter(FontWeight.SemiBold),
        labelMedium = labelMedium.inter(FontWeight.Medium),
        labelSmall = labelSmall.inter(FontWeight.Medium),
    )
}

private val NexTvShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Thème unique (sombre) : une application TV se regarde en général dans la pénombre. */
@Composable
fun NexTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = NexTvTypography,
        shapes = NexTvShapes,
    ) {
        // Texte clair par défaut, y compris hors d'une Surface (écrans plein cadre, menu du lecteur).
        CompositionLocalProvider(LocalContentColor provides DarkColors.onBackground, content = content)
    }
}
