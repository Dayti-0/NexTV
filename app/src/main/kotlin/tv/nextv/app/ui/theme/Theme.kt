package tv.nextv.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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

/**
 * Palettes de l'interface (Réglages › Apparence). Les quatre premières sont les thèmes d'origine
 * (leurs noms restent ceux enregistrés dans les réglages).
 */
enum class ThemeStyle(val category: PaletteCategory) {
    NIGHT(PaletteCategory.CLASSIC),
    BLACK(PaletteCategory.CLASSIC),
    CINEMA(PaletteCategory.CLASSIC),
    VIOLET(PaletteCategory.CLASSIC),
    IVORY(PaletteCategory.CLASSIC),
    GLACIER(PaletteCategory.PASTEL),
    POWDER(PaletteCategory.PASTEL),
    LAVENDER(PaletteCategory.PASTEL),
    MINT(PaletteCategory.PASTEL),
    SAGE(PaletteCategory.PASTEL),
    PEACH(PaletteCategory.PASTEL),
    VANILLA(PaletteCategory.PASTEL),
    MIDNIGHT(PaletteCategory.DARK),
    BORDEAUX(PaletteCategory.DARK),
    SLATE(PaletteCategory.DARK),
    EMERALD(PaletteCategory.DARK),
    TURQUOISE(PaletteCategory.COLORFUL),
    CORAL(PaletteCategory.COLORFUL),
    AMBER(PaletteCategory.COLORFUL),
    ;

    companion object {
        /** Palette « Par défaut ». */
        val DEFAULT = NIGHT
    }
}

/** Familles de palettes, dans l'ordre de l'écran Apparence. */
enum class PaletteCategory { CLASSIC, PASTEL, DARK, COLORFUL }

/**
 * Couleurs d'une palette, par rôle :
 * - fonds : [background] (écran), [surfaceContainerLow] (colonnes et volets), [surfaceContainer]
 *   (cartes), [surfaceContainerHigh] (menus, boîtes de dialogue, boutons secondaires),
 *   [surfaceContainerHighest] (carte sélectionnée à la télécommande) ;
 * - [primary] : accentuation (sélection, contour du focus, bouton principal) et [primaryContainer]
 *   (catégorie choisie) ;
 * - [secondary] : boutons et éléments interactifs secondaires, [accent] : halos et dégradés ;
 * - textes ([textPrimary], [textSecondary], [textMuted]) et états ([live], [warning], [error]),
 *   choisis pour rester lisibles sur les fonds de la palette.
 */
data class Palette(
    val isLight: Boolean,
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
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val accent: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val live: Color = if (isLight) Color(0xFF0B8A62) else Color(0xFF2ED3A0),
    val warning: Color = if (isLight) Color(0xFFA85D00) else Color(0xFFFFB547),
    val error: Color = if (isLight) Color(0xFFC62828) else Color(0xFFFF6B6B),
)

/** Palette sombre. */
private fun dark(
    background: Long, surface: Long, low: Long, container: Long, high: Long, highest: Long,
    outline: Long, outlineVariant: Long,
    primary: Long, onPrimary: Long, primaryContainer: Long, onPrimaryContainer: Long,
    secondary: Long, onSecondary: Long, secondaryContainer: Long, onSecondaryContainer: Long,
    accent: Long, text: Long, textSecondary: Long, textMuted: Long,
    live: Long = 0xFF2ED3A0, warning: Long = 0xFFFFB547,
) = Palette(
    isLight = false,
    background = Color(background), surface = Color(surface), surfaceContainerLow = Color(low), surfaceContainer = Color(container),
    surfaceContainerHigh = Color(high), surfaceContainerHighest = Color(highest), outline = Color(outline), outlineVariant = Color(outlineVariant),
    primary = Color(primary), onPrimary = Color(onPrimary), primaryContainer = Color(primaryContainer), onPrimaryContainer = Color(onPrimaryContainer),
    secondary = Color(secondary), onSecondary = Color(onSecondary), secondaryContainer = Color(secondaryContainer),
    onSecondaryContainer = Color(onSecondaryContainer), accent = Color(accent),
    textPrimary = Color(text), textSecondary = Color(textSecondary), textMuted = Color(textMuted),
    live = Color(live), warning = Color(warning),
)

/** Palette claire : textes foncés, accent assez soutenu pour rester lisible sur le blanc. */
private fun light(
    background: Long, surface: Long, low: Long, container: Long, high: Long, highest: Long,
    outline: Long, outlineVariant: Long,
    primary: Long, primaryContainer: Long, onPrimaryContainer: Long,
    secondary: Long, secondaryContainer: Long, onSecondaryContainer: Long,
    accent: Long, text: Long, textSecondary: Long, textMuted: Long,
    live: Long = 0xFF0B8A62,
) = Palette(
    isLight = true,
    background = Color(background), surface = Color(surface), surfaceContainerLow = Color(low), surfaceContainer = Color(container),
    // Menus, boutons secondaires, « Tout voir » : nettement distincts du fond (sinon ils s'y confondent).
    surfaceContainerHigh = mix(Color(highest), Color(high), 0.45f), surfaceContainerHighest = Color(highest),
    outline = Color(outline), outlineVariant = Color(outlineVariant),
    primary = Color(primary), onPrimary = Color.White, primaryContainer = Color(primaryContainer), onPrimaryContainer = Color(onPrimaryContainer),
    secondary = Color(secondary), onSecondary = Color.White, secondaryContainer = Color(secondaryContainer),
    onSecondaryContainer = Color(onSecondaryContainer), accent = Color(accent),
    textPrimary = Color(text), textSecondary = Color(textSecondary), textMuted = Color(textMuted),
    live = Color(live),
)

fun ThemeStyle.palette(): Palette = when (this) {
    // Classiques.
    ThemeStyle.NIGHT -> dark(
        0xFF0A0E14, 0xFF10151D, 0xFF141A23, 0xFF18202B, 0xFF1E2734, 0xFF263142, 0xFF334155, 0xFF242E3D,
        0xFF4C8DFF, 0xFFFFFFFF, 0xFF173468, 0xFFD6E3FF,
        0xFF8AB4FF, 0xFF0B1F45, 0xFF1C2A40, 0xFFD6E3FF,
        0xFF8B5CF6, 0xFFE8EDF5, 0xFF9AA6B8, 0xFF6B778A,
    )
    // Noir profond (écrans OLED), sans teinte.
    ThemeStyle.BLACK -> dark(
        0xFF000000, 0xFF080808, 0xFF0E0E0E, 0xFF151515, 0xFF1C1C1C, 0xFF262626, 0xFF3A3A3A, 0xFF222222,
        0xFFE6E6E6, 0xFF0A0A0A, 0xFF2E2E2E, 0xFFF2F2F2,
        0xFFBDBDBD, 0xFF101010, 0xFF242424, 0xFFEDEDED,
        0xFF9E9E9E, 0xFFEDEDED, 0xFFA6A6A6, 0xFF707070,
    )
    // Salle de cinéma : noir chaud et rouge.
    ThemeStyle.CINEMA -> dark(
        0xFF0B0909, 0xFF120E0E, 0xFF181212, 0xFF1F1717, 0xFF281D1D, 0xFF332525, 0xFF4D3636, 0xFF2E2121,
        0xFFE53935, 0xFFFFFFFF, 0xFF4F1416, 0xFFFFDAD6,
        0xFFF2A49C, 0xFF3D0B08, 0xFF3A2020, 0xFFFFDAD6,
        0xFFFF8A50, 0xFFF4EAE8, 0xFFB9A5A3, 0xFF806D6B,
    )
    ThemeStyle.VIOLET -> dark(
        0xFF0D0A14, 0xFF13101C, 0xFF181424, 0xFF1E1A2C, 0xFF262136, 0xFF302A44, 0xFF443B5E, 0xFF2A2440,
        0xFFA78BFA, 0xFF1B0F3B, 0xFF3B2A6B, 0xFFE9DDFF,
        0xFFD8B4FE, 0xFF2A1046, 0xFF2E2448, 0xFFF0E4FF,
        0xFFEC4899, 0xFFEDE9F7, 0xFFA9A1BF, 0xFF746C89,
    )
    // Blanc élégant : ivoire chaud, encre graphite et touches champagne.
    ThemeStyle.IVORY -> light(
        0xFFF5F3EE, 0xFFFBFAF7, 0xFFEDEAE3, 0xFFFFFFFF, 0xFFF3F0EA, 0xFFE4DFD4, 0xFFC4BDAE, 0xFFE0DBD0,
        0xFF22262F, 0xFFE8DFCB, 0xFF3A2E14,
        0xFF9C7A3C, 0xFFF1E6CC, 0xFF46380F,
        0xFFC2A06A, 0xFF191B21, 0xFF545862, 0xFF878B95,
    )
    // Pastel : fonds clairs teintés, accent assez soutenu pour le contraste.
    ThemeStyle.GLACIER -> light(
        0xFFEDF3F8, 0xFFF5F9FC, 0xFFE3ECF4, 0xFFFFFFFF, 0xFFEBF2F8, 0xFFD6E4F0, 0xFFB2C6D8, 0xFFD5E2ED,
        0xFF2C6BA3, 0xFFD2E5F6, 0xFF0E3355,
        0xFF4A8CBF, 0xFFDDEDF9, 0xFF153E5E,
        0xFF8CC4EA, 0xFF132232, 0xFF485A6D, 0xFF7C8C9D,
    )
    ThemeStyle.POWDER -> light(
        0xFFF9F0F2, 0xFFFDF7F8, 0xFFF3E5E9, 0xFFFFFFFF, 0xFFF7ECEF, 0xFFEDD7DD, 0xFFD6BAC2, 0xFFEAD7DC,
        0xFFAD4F68, 0xFFF6D6DF, 0xFF581E2E,
        0xFFC27A8D, 0xFFF9E2E8, 0xFF5C2836,
        0xFFF0B3C2, 0xFF2A191E, 0xFF6A5258, 0xFF998187,
    )
    ThemeStyle.LAVENDER -> light(
        0xFFF2F0FA, 0xFFF8F7FD, 0xFFE9E5F5, 0xFFFFFFFF, 0xFFEFEBF8, 0xFFDFD8F0, 0xFFC2B8DD, 0xFFDDD6EE,
        0xFF6852B5, 0xFFE1D8FA, 0xFF2A1D62,
        0xFF8A78CE, 0xFFEBE4FB, 0xFF33276C,
        0xFFC6B4F4, 0xFF1D192D, 0xFF58526E, 0xFF89839F,
    )
    ThemeStyle.MINT -> light(
        0xFFEDF7F3, 0xFFF5FBF8, 0xFFE2F2EB, 0xFFFFFFFF, 0xFFEBF6F1, 0xFFD4ECE1, 0xFFAFD3C3, 0xFFD3E9DF,
        0xFF237F62, 0xFFCBEEDF, 0xFF0C3D2E,
        0xFF3A9E7F, 0xFFDBF3E8, 0xFF114838,
        0xFF9ADFC3, 0xFF11261E, 0xFF485F57, 0xFF7C9189,
        live = 0xFF0E7A9E,
    )
    ThemeStyle.SAGE -> light(
        0xFFF0F3ED, 0xFFF7F9F5, 0xFFE6EBE1, 0xFFFFFFFF, 0xFFEEF2EA, 0xFFDAE3D2, 0xFFB9C6AE, 0xFFD9E1D2,
        0xFF57714C, 0xFFDAE7D1, 0xFF22321B,
        0xFF86997A, 0xFFE5EDDD, 0xFF2B3A23,
        0xFFB6C9A5, 0xFF1B2317, 0xFF525D4B, 0xFF838E7D,
    )
    ThemeStyle.PEACH -> light(
        0xFFFBF2ED, 0xFFFEF8F4, 0xFFF6E6DC, 0xFFFFFFFF, 0xFFFAEEE7, 0xFFF1DACC, 0xFFDEBFAC, 0xFFEEDBCE,
        0xFFBA5C36, 0xFFF9D9C8, 0xFF58230F,
        0xFFD9835D, 0xFFFBE4D7, 0xFF5C2B16,
        0xFFFFC2A4, 0xFF2A1B14, 0xFF6C5447, 0xFF9E8578,
    )
    ThemeStyle.VANILLA -> light(
        0xFFFAF6E8, 0xFFFDFAF0, 0xFFF4EDD7, 0xFFFFFFFF, 0xFFF9F3E3, 0xFFECE2C3, 0xFFD6C899, 0xFFECE2C5,
        0xFF856810, 0xFFF4E4AA, 0xFF3B2D00,
        0xFFB38F22, 0xFFF7EBC3, 0xFF473805,
        0xFFF1D682, 0xFF25200E, 0xFF605941, 0xFF938B70,
    )
    // Sombres.
    ThemeStyle.MIDNIGHT -> dark(
        0xFF070B1A, 0xFF0B1124, 0xFF0F162D, 0xFF131B36, 0xFF19233F, 0xFF212D4D, 0xFF2E3B5E, 0xFF1C2645,
        0xFF7C9CFF, 0xFF0A1240, 0xFF1F2B66, 0xFFDCE3FF,
        0xFFA5B4FC, 0xFF111A4D, 0xFF1A2350, 0xFFDDE3FF,
        0xFF38BDF8, 0xFFE6EAF7, 0xFF97A1BE, 0xFF626C88,
    )
    ThemeStyle.BORDEAUX -> dark(
        0xFF110609, 0xFF170A0E, 0xFF1D0E13, 0xFF241218, 0xFF2D171F, 0xFF381D27, 0xFF5A2E3B, 0xFF331A23,
        0xFFD4576F, 0xFFFFFFFF, 0xFF5C1A2B, 0xFFFFD9E0,
        0xFFE8A0AE, 0xFF4A1220, 0xFF3D1A24, 0xFFFFD9E0,
        0xFFC9A27E, 0xFFF3E6E9, 0xFFB49BA2, 0xFF7D666C,
    )
    ThemeStyle.SLATE -> dark(
        0xFF0F1215, 0xFF14181C, 0xFF191E23, 0xFF1F252B, 0xFF262D34, 0xFF2F3740, 0xFF46505B, 0xFF2B333B,
        0xFF8FB3C9, 0xFF0E1F2A, 0xFF2C3F4C, 0xFFD7E8F3,
        0xFFA9B6C2, 0xFF1A232C, 0xFF2A333C, 0xFFDEE5EC,
        0xFFC7D2DC, 0xFFE7EBEF, 0xFF9FA9B3, 0xFF6C7680,
    )
    ThemeStyle.EMERALD -> dark(
        0xFF04110C, 0xFF071811, 0xFF0A1E16, 0xFF0E261C, 0xFF132F23, 0xFF1A3A2C, 0xFF2C5443, 0xFF183326,
        0xFF34D399, 0xFF003824, 0xFF0F4A34, 0xFFC8F7E2,
        0xFF6EE7B7, 0xFF00402A, 0xFF123D2D, 0xFFD1FAE5,
        0xFFD4AF37, 0xFFE6F4EC, 0xFF97B5A6, 0xFF62806F,
        live = 0xFF5EEAD4,
    )
    // Colorées : fonds teintés de la couleur, accent vif.
    ThemeStyle.TURQUOISE -> dark(
        0xFF041214, 0xFF06191C, 0xFF082024, 0xFF0B282D, 0xFF0F3238, 0xFF143D44, 0xFF24616A, 0xFF133A40,
        0xFF2DD4CF, 0xFF003735, 0xFF0C4A4C, 0xFFC6FBF7,
        0xFF7DD3FC, 0xFF003548, 0xFF0F3A47, 0xFFD3F1FF,
        0xFFFDA4AF, 0xFFE3F6F6, 0xFF94B9BB, 0xFF5F8486,
        live = 0xFF86EFAC,
    )
    ThemeStyle.CORAL -> dark(
        0xFF140B0A, 0xFF1A0F0E, 0xFF201413, 0xFF281918, 0xFF311F1D, 0xFF3C2624, 0xFF5E3B37, 0xFF36221F,
        0xFFFF7A6B, 0xFF3D0905, 0xFF5A221B, 0xFFFFDAD4,
        0xFFFFB4A1, 0xFF4A140B, 0xFF452621, 0xFFFFDAD4,
        0xFFFFD166, 0xFFF8E9E6, 0xFFBFA19C, 0xFF876A65,
    )
    ThemeStyle.AMBER -> dark(
        0xFF130D05, 0xFF191107, 0xFF1F160A, 0xFF271C0E, 0xFF302313, 0xFF3B2B18, 0xFF5E4527, 0xFF372815,
        0xFFFFA62B, 0xFF3A2200, 0xFF5A3A08, 0xFFFFE0B2,
        0xFFFFCC80, 0xFF3F2700, 0xFF45300F, 0xFFFFE6C2,
        0xFFFF6F3C, 0xFFF7EDE0, 0xFFBCA88F, 0xFF85725C,
        warning = 0xFFFFE082,
    )
}

/** Mélange de deux couleurs ([t] = 0 : [a], 1 : [b]). */
internal fun mix(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t,
)

/**
 * Pendant la lecture vidéo, l'interface reste sombre (bandeau, menus sur l'image) : une palette
 * claire y prend des fonds sombres teintés de son accent, et un accent éclairci.
 */
fun Palette.forVideo(): Palette {
    if (!isLight) return this
    val base = Color(0xFF0B0D12)
    fun tint(t: Float) = mix(base, primary, t)
    val bright = mix(primary, Color.White, 0.35f)
    return copy(
        isLight = false,
        background = tint(0.04f), surface = tint(0.06f), surfaceContainerLow = tint(0.08f), surfaceContainer = tint(0.11f),
        surfaceContainerHigh = tint(0.15f), surfaceContainerHighest = tint(0.2f), outline = tint(0.32f), outlineVariant = tint(0.18f),
        primary = bright, onPrimary = Color(0xFF101114), primaryContainer = mix(base, primary, 0.45f), onPrimaryContainer = Color.White,
        secondary = mix(secondary, Color.White, 0.35f), onSecondary = Color(0xFF101114),
        secondaryContainer = tint(0.22f), onSecondaryContainer = Color.White,
        textPrimary = Color(0xFFECEEF2), textSecondary = Color(0xFFA4A9B4), textMuted = Color(0xFF727885),
        live = Color(0xFF2ED3A0), warning = Color(0xFFFFB547), error = Color(0xFFFF6B6B),
    )
}

/**
 * Palette NixTV. Les couleurs du thème choisi sont lues dans un état Compose : changer de thème
 * redessine toute l'interface, sans recréer les écrans.
 */
object NexTvColors {
    var palette by mutableStateOf(ThemeStyle.NIGHT.palette())
        internal set

    val isLight get() = palette.isLight
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
    val Secondary get() = palette.secondary
    val Accent get() = palette.accent

    /** Couleur « en direct » : programme en cours, progression. */
    val Live get() = palette.live
    val Warning get() = palette.warning
    val Error get() = palette.error

    val TextPrimary get() = palette.textPrimary
    val TextSecondary get() = palette.textSecondary
    val TextMuted get() = palette.textMuted

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

    /** Couleur vive (compétition, étiquette) rendue lisible sur un fond clair. */
    fun readable(color: Color): Color = if (palette.isLight) mix(color, Color.Black, 0.45f) else color
}

val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private fun colorScheme(palette: Palette) = if (palette.isLight) {
    lightColorScheme(
        primary = palette.primary,
        onPrimary = palette.onPrimary,
        primaryContainer = palette.primaryContainer,
        onPrimaryContainer = palette.onPrimaryContainer,
        secondary = palette.secondary,
        onSecondary = palette.onSecondary,
        secondaryContainer = palette.secondaryContainer,
        onSecondaryContainer = palette.onSecondaryContainer,
        tertiary = palette.warning,
        onTertiary = Color.White,
        background = palette.background,
        onBackground = palette.textPrimary,
        surface = palette.surface,
        onSurface = palette.textPrimary,
        surfaceVariant = palette.surfaceContainerHigh,
        onSurfaceVariant = palette.textSecondary,
        surfaceContainerLowest = palette.surfaceContainer,
        surfaceContainerLow = palette.surfaceContainerLow,
        surfaceContainer = palette.surfaceContainer,
        surfaceContainerHigh = palette.surfaceContainerHigh,
        surfaceContainerHighest = palette.surfaceContainerHighest,
        outline = palette.outline,
        outlineVariant = palette.outlineVariant,
        error = palette.error,
        onError = Color.White,
    )
} else {
    darkColorScheme(
        primary = palette.primary,
        onPrimary = palette.onPrimary,
        primaryContainer = palette.primaryContainer,
        onPrimaryContainer = palette.onPrimaryContainer,
        secondary = palette.secondary,
        onSecondary = palette.onSecondary,
        secondaryContainer = palette.secondaryContainer,
        onSecondaryContainer = palette.onSecondaryContainer,
        tertiary = palette.warning,
        onTertiary = Color(0xFF3D2600),
        background = palette.background,
        onBackground = palette.textPrimary,
        surface = palette.surface,
        onSurface = palette.textPrimary,
        surfaceVariant = palette.surfaceContainerHigh,
        onSurfaceVariant = palette.textSecondary,
        surfaceContainerLowest = palette.background,
        surfaceContainerLow = palette.surfaceContainerLow,
        surfaceContainer = palette.surfaceContainer,
        surfaceContainerHigh = palette.surfaceContainerHigh,
        surfaceContainerHighest = palette.surfaceContainerHighest,
        outline = palette.outline,
        outlineVariant = palette.outlineVariant,
        error = palette.error,
        onError = Color(0xFF3B0A0A),
    )
}

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

/**
 * Palette en aperçu (écran Apparence) : affichée partout tant que la sélection est sur elle, sans
 * être enregistrée.
 */
object ThemePreview {
    var style by mutableStateOf<ThemeStyle?>(null)
}

/** Thème de l'application, aux couleurs de [style] (ou de la palette en aperçu). */
@Composable
fun NexTvTheme(style: ThemeStyle = ThemeStyle.NIGHT, content: @Composable () -> Unit) {
    val shown = ThemePreview.style ?: style
    val palette = remember(shown) { shown.palette() }
    PaletteTheme(palette, content)
}

/** Interface sur la vidéo (lecteur) : toujours sombre, même avec une palette claire. */
@Composable
fun VideoTheme(content: @Composable () -> Unit) {
    val palette = NexTvColors.palette
    if (!palette.isLight) {
        content()
        return
    }
    val dark = remember(palette) { palette.forVideo() }
    val scheme = remember(dark) { colorScheme(dark) }
    MaterialTheme(colorScheme = scheme, typography = NexTvTypography, shapes = NexTvShapes) {
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
    }
}

@Composable
private fun PaletteTheme(palette: Palette, content: @Composable () -> Unit) {
    // Couleurs du thème en place avant de composer les écrans qui les lisent.
    if (NexTvColors.palette != palette) NexTvColors.palette = palette
    val scheme = remember(palette) { colorScheme(palette) }
    MaterialTheme(
        colorScheme = scheme,
        typography = NexTvTypography,
        shapes = NexTvShapes,
    ) {
        // Texte par défaut aux couleurs de la palette, y compris hors d'une Surface (écrans plein cadre).
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
    }
}
