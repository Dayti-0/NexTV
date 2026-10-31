package tv.nextv.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tv.nextv.app.R

/** Thèmes de l'interface (Réglages › Affichage) : tous sombres, pour regarder la TV dans la pénombre. */
enum class ThemeStyle { NIGHT, BLACK, CINEMA, VIOLET }

/** Couleurs propres à un thème : fonds, contours et couleur principale. */
data class Palette(
    val background: Color,
    val surface: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val outline: Color,
    val outlineVariant: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val accent: Color,
)

fun ThemeStyle.palette(): Palette = when (this) {
    ThemeStyle.NIGHT -> Palette(
        background = Color(0xFF0A0E14), surface = Color(0xFF10151D), surfaceContainerLow = Color(0xFF141A23),
        surfaceContainer = Color(0xFF18202B), surfaceContainerHigh = Color(0xFF1E2734), surfaceContainerHighest = Color(0xFF263142),
        outline = Color(0xFF334155), outlineVariant = Color(0xFF242E3D),
        primary = Color(0xFF4C8DFF), onPrimary = Color.White, primaryContainer = Color(0xFF173468), onPrimaryContainer = Color(0xFFD6E3FF),
        accent = Color(0xFF8B5CF6),
    )
    // Noir profond (écrans OLED), sans teinte.
    ThemeStyle.BLACK -> Palette(
        background = Color(0xFF000000), surface = Color(0xFF080808), surfaceContainerLow = Color(0xFF0E0E0E),
        surfaceContainer = Color(0xFF151515), surfaceContainerHigh = Color(0xFF1C1C1C), surfaceContainerHighest = Color(0xFF262626),
        outline = Color(0xFF3A3A3A), outlineVariant = Color(0xFF222222),
        primary = Color(0xFFE6E6E6), onPrimary = Color(0xFF0A0A0A), primaryContainer = Color(0xFF2E2E2E), onPrimaryContainer = Color(0xFFF2F2F2),
        accent = Color(0xFF9E9E9E),
    )
    // Salle de cinéma : noir chaud et rouge.
    ThemeStyle.CINEMA -> Palette(
        background = Color(0xFF0B0909), surface = Color(0xFF120E0E), surfaceContainerLow = Color(0xFF181212),
        surfaceContainer = Color(0xFF1F1717), surfaceContainerHigh = Color(0xFF281D1D), surfaceContainerHighest = Color(0xFF332525),
        outline = Color(0xFF4D3636), outlineVariant = Color(0xFF2E2121),
        primary = Color(0xFFE53935), onPrimary = Color.White, primaryContainer = Color(0xFF4F1416), onPrimaryContainer = Color(0xFFFFDAD6),
        accent = Color(0xFFFF8A50),
    )
    ThemeStyle.VIOLET -> Palette(
        background = Color(0xFF0D0A14), surface = Color(0xFF13101C), surfaceContainerLow = Color(0xFF181424),
        surfaceContainer = Color(0xFF1E1A2C), surfaceContainerHigh = Color(0xFF262136), surfaceContainerHighest = Color(0xFF302A44),
        outline = Color(0xFF443B5E), outlineVariant = Color(0xFF2A2440),
        primary = Color(0xFFA78BFA), onPrimary = Color(0xFF1B0F3B), primaryContainer = Color(0xFF3B2A6B), onPrimaryContainer = Color(0xFFE9DDFF),
        accent = Color(0xFFEC4899),
    )
}

/**
 * Palette NexTV. Les couleurs du thème choisi sont lues dans un état Compose : changer de thème
 * redessine toute l'interface, sans recréer les écrans.
 */
object NexTvColors {
    var palette by mutableStateOf(ThemeStyle.NIGHT.palette())
        internal set

    val Background get() = palette.background
    val Surface get() = palette.surface
    val SurfaceContainerLow get() = palette.surfaceContainerLow
    val SurfaceContainer get() = palette.surfaceContainer
    val SurfaceContainerHigh get() = palette.surfaceContainerHigh
    val SurfaceContainerHighest get() = palette.surfaceContainerHighest
    val Outline get() = palette.outline
    val OutlineVariant get() = palette.outlineVariant

    val Primary get() = palette.primary
    val PrimaryContainer get() = palette.primaryContainer
    val Accent get() = palette.accent

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

private fun colorScheme(palette: Palette) = darkColorScheme(
    primary = palette.primary,
    onPrimary = palette.onPrimary,
    primaryContainer = palette.primaryContainer,
    onPrimaryContainer = palette.onPrimaryContainer,
    secondary = NexTvColors.Live,
    onSecondary = Color(0xFF00382A),
    tertiary = NexTvColors.Warning,
    onTertiary = Color(0xFF3D2600),
    background = palette.background,
    onBackground = NexTvColors.TextPrimary,
    surface = palette.surface,
    onSurface = NexTvColors.TextPrimary,
    surfaceVariant = palette.surfaceContainerHigh,
    onSurfaceVariant = NexTvColors.TextSecondary,
    surfaceContainerLowest = palette.background,
    surfaceContainerLow = palette.surfaceContainerLow,
    surfaceContainer = palette.surfaceContainer,
    surfaceContainerHigh = palette.surfaceContainerHigh,
    surfaceContainerHighest = palette.surfaceContainerHighest,
    outline = palette.outline,
    outlineVariant = palette.outlineVariant,
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

/** Thème sombre de l'application, aux couleurs de [style]. */
@Composable
fun NexTvTheme(style: ThemeStyle = ThemeStyle.NIGHT, content: @Composable () -> Unit) {
    val palette = remember(style) { style.palette() }
    // Couleurs du thème en place avant de composer les écrans qui les lisent.
    if (NexTvColors.palette != palette) NexTvColors.palette = palette
    val scheme = remember(palette) { colorScheme(palette) }
    MaterialTheme(
        colorScheme = scheme,
        typography = NexTvTypography,
        shapes = NexTvShapes,
    ) {
        // Texte clair par défaut, y compris hors d'une Surface (écrans plein cadre, menu du lecteur).
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
    }
}
