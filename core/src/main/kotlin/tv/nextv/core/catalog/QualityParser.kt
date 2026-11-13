package tv.nextv.core.catalog

import tv.nextv.core.model.StreamQuality
import java.text.Normalizer

/**
 * Repère la qualité annoncée dans le nom d'une chaîne (« TF1 FHD », « TF1 ᴴᴰ », « TF1 (720p) »)
 * et en déduit une clé commune aux différentes versions de la même chaîne.
 *
 * Seules les mentions présentes dans la playlist sont prises en compte : une chaîne sans
 * mention de qualité n'a pas de qualité connue, et aucune version n'est supposée exister.
 */
object QualityParser {

    /**
     * Version des règles de regroupement : quand elle augmente, la playlist est relue au démarrage
     * suivant pour regrouper les chaînes selon les nouvelles règles (3 : qualité des films et des séries ;
     * 4 : versions de secours « BKP » et « FHD++ (Très haute résolution) » regroupées avec la chaîne ;
     * 5 : versions d'un même film (« Truefrench », « FR », 4K, HD…) regroupées sous une seule affiche ;
     * 6 : éditions régionales de France 3 regroupées sous le nom de leur antenne, « Haute » gardé dans les noms,
     * chaînes dont les noms ne diffèrent que par « TV » regroupées ; 7 : « (True FR) » reconnu comme Truefrench ;
     * 8 : un film sous son titre français et son titre original regroupé, d'après sa fiche TMDB ;
     * 9 : versions « Double audio » et « Dolby Digital » d'une chaîne regroupées avec elle).
     */
    const val VERSION = 9

    data class Result(
        /** Nom sans les mentions de qualité ni de codec. */
        val baseName: String,
        val quality: StreamQuality?,
        /** Clé normalisée commune aux versions d'une même chaîne. */
        val variantKey: String,
        /** Son annoncé par le nom : [AUDIO_MULTI] (« Double audio »), [AUDIO_DOLBY] (« Dolby Digital »), sinon 0. */
        val audioRank: Int = 0,
    )

    /** Version en Dolby Digital (« DOLBY DIGITAL », « AC3 », « DD+ ») : son multicanal. */
    const val AUDIO_DOLBY = 1

    /** Version en double audio (« DOUBLE AUDIO », « MULTI AUDIO ») : VF et VO au choix, préférée aux autres. */
    const val AUDIO_MULTI = 2

    /** Premier mot de « Double audio », « Dual audio », « Multi audio ». */
    private val MULTI_AUDIO_PREFIXES = setOf("DOUBLE", "DUAL", "MULTI")

    /** Mention Dolby en un mot. */
    private val DOLBY_TOKENS = setOf("DOLBY", "AC3", "EAC3", "DD+")

    /** Mots qui suivent « Dolby » (« Dolby Digital Plus », « Dolby Atmos »). */
    private val DOLBY_SUFFIXES = setOf("DIGITAL", "PLUS", "ATMOS", "AUDIO")

    private val QUALITY_TOKENS: Map<String, StreamQuality> = buildMap {
        // HDR : diffusé en 4K par les chaînes françaises (« TF1 HDR », logo « TF1 4K »).
        listOf("4K", "UHD", "2160P", "2160", "8K", "4K+", "UHD+", "HDR", "HDR10", "HDR10+", "HLG", "DOLBYVISION").forEach { put(it, StreamQuality.UHD) }
        listOf("FHD", "FULLHD", "1080P", "1080I", "1080", "FHD+").forEach { put(it, StreamQuality.FHD) }
        listOf("HD", "720P", "720", "HDTV", "HD+").forEach { put(it, StreamQuality.HD) }
        listOf("SD", "576P", "576", "480P", "480").forEach { put(it, StreamQuality.SD) }
        listOf("LOW", "LQ", "LD", "360P", "240P", "MOBILE").forEach { put(it, StreamQuality.LOW) }
    }

    /**
     * Mentions techniques ou publicitaires retirées du nom de base sans être des qualités
     * (« M6 HDR (EXCLUS) » est la même chaîne que « M6 »).
     */
    private val CODEC_TOKENS = setOf(
        "HEVC", "H265", "H264", "X265", "X264", "AVC", "50FPS", "60FPS", "FPS", "SDR",
        "EXCLUS", "EXCLU", "EXCLUSIF", "EXCLUSIVE", "EXCLUSIVITE",
    )

    /**
     * Mots qui ne font qu'accompagner une mention de qualité (« TF1 (Résolution 4K) », « M6 Qualité
     * réelle FHD ») : retirés seulement quand le nom annonce une qualité.
     */
    private val DESCRIPTIVE_TOKENS = setOf(
        "RESOLUTION", "QUALITE", "QUALITY", "REEL", "REELLE", "NATIVE", "NATIF", "DEFINITION",
    )

    /**
     * « Très haute résolution » : ces deux mots ne sont retirés qu'avec le nom qu'ils qualifient ([DESCRIPTIVE_NOUNS]).
     * Seuls, ils font partie du nom de la chaîne (« France 3 Haute-Normandie FHD » perdait « Haute »).
     */
    private val QUALIFIER_TOKENS = setOf("TRES", "HAUTE")
    private val DESCRIPTIVE_NOUNS = setOf("RESOLUTION", "QUALITE", "QUALITY", "DEFINITION")

    /**
     * Version de secours d'une chaîne (« RMC SPORT 1 BKP FHD », « TF1 BACKUP2 ») : même chaîne, essayée quand la
     * version habituelle de même qualité ne marche pas (elle vient après dans la playlist).
     */
    private val BACKUP_TOKEN = Regex("(?i)(?:BKP|BACKUP)\\d*")

    /**
     * Chaînes dont chaque qualité diffuse autre chose (FIFA+ : un flux différent en FHD et en HD) : jamais
     * regroupées, chaque qualité reste une chaîne à part.
     */
    private val SEPARATE_FEEDS = setOf("fifa+")

    /** Lettres en exposant fréquentes dans les playlists (« ᶠᴴᴰ », « ˢᴰ »…). */
    private val SUPERSCRIPTS: Map<Char, Char> = buildMap {
        "ᴬᴮᴰᴱᴳᴴᴵᴶᴷᴸᴹᴺᴼᴾᴿᵀᵁⱽᵂ".zip("ABDEGHIJKLMNOPRTUVW").forEach { (s, c) -> put(s, c) }
        "ᵃᵇᶜᵈᵉᶠᵍʰⁱʲᵏˡᵐⁿᵒᵖʳˢᵗᵘᵛʷˣʸᶻ".zip("abcdefghijklmnoprstuvwxyz").forEach { (s, c) -> put(s, c) }
        "⁰¹²³⁴⁵⁶⁷⁸⁹".zip("0123456789").forEach { (s, c) -> put(s, c) }
        put('ᶠ', 'F')
    }

    private val TOKEN = Regex("[\\p{L}\\p{Nd}+]+")

    /** [word] (en majuscules) est une mention de qualité ou de codec (« FHD », « 1080P », « HEVC »). */
    internal fun isTechnical(word: String): Boolean = word in QUALITY_TOKENS || word in CODEC_TOKENS

    fun parse(name: String): Result {
        val normalized = normalizeSuperscripts(name)
        var best: StreamQuality? = null
        val kept = mutableListOf<String>()
        var descriptive = 0
        var nouns = 0
        var backup = false
        var audio = 0
        val tokens = TOKEN.findAll(normalized).map { it.value }.toList()
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            val upper = token.uppercase()
            // « FULL HD » en deux mots.
            if (upper == "FULL" && i + 1 < tokens.size && tokens[i + 1].uppercase() == "HD") {
                best = maxQuality(best, StreamQuality.FHD)
                i += 2
                continue
            }
            // « Double audio », « Multi audio » : même chaîne, VF et VO au choix.
            if (upper in MULTI_AUDIO_PREFIXES && i + 1 < tokens.size && tokens[i + 1].uppercase() == "AUDIO") {
                audio = maxOf(audio, AUDIO_MULTI)
                i += 2
                continue
            }
            if (upper == "MULTIAUDIO") {
                audio = maxOf(audio, AUDIO_MULTI)
                i++
                continue
            }
            // « Dolby Digital », « AC3 » : même chaîne, son multicanal.
            if (upper in DOLBY_TOKENS) {
                audio = maxOf(audio, AUDIO_DOLBY)
                i++
                while (upper == "DOLBY" && i < tokens.size && tokens[i].uppercase() in DOLBY_SUFFIXES) i++
                continue
            }
            // « FHD++ » : la qualité suivie de « + » en plus.
            val quality = QUALITY_TOKENS[upper] ?: upper.takeIf { it.endsWith('+') }?.let { QUALITY_TOKENS[it.trimEnd('+')] }
            when {
                quality != null -> best = maxQuality(best, quality)
                upper in CODEC_TOKENS -> Unit
                BACKUP_TOKEN.matches(upper) -> backup = true
                else -> {
                    kept += token
                    val folded = foldAccents(upper)
                    if (folded in DESCRIPTIVE_TOKENS) descriptive++
                    if (folded in DESCRIPTIVE_NOUNS) nouns++
                }
            }
            i++
        }
        // « Résolution », « Qualité »… n'accompagnent qu'une mention de qualité : retirés avec elle.
        val dropDescriptive = best != null && descriptive > 0
        // « Très haute » : seulement avec « résolution », « définition » ou « qualité ».
        val dropQualifiers = best != null && nouns > 0
        if (dropDescriptive) {
            kept.removeAll { word -> foldAccents(word.uppercase()).let { it in DESCRIPTIVE_TOKENS || (dropQualifiers && it in QUALIFIER_TOKENS) } }
        }
        // Sans mention de qualité ni de codec (cas le plus fréquent des films), la recherche des mentions est sautée.
        val baseName = stripTokens(normalized, hasTokens = kept.size != tokens.size, dropDescriptive, dropQualifiers, backup, audio != 0).ifBlank { name.trim() }
        val words = kept.map { foldAccents(it.lowercase()) }
        val shared = words.joinToString(" ")
        val key = if (best != null && words.any { it in SEPARATE_FEEDS }) "$shared ${best.label.lowercase()}" else shared
        return Result(baseName = baseName, quality = best, variantKey = key, audioRank = audio)
    }

    private fun maxQuality(a: StreamQuality?, b: StreamQuality): StreamQuality =
        if (a == null || b.rank > a.rank) b else a

    /** Mentions de qualité et de codec, en mots entiers (compilé une seule fois : appelé pour chaque entrée). */
    private val STRIP = Regex(
        "(?i)(?<![\\p{L}\\p{Nd}])(?:full\\s*hd|" +
            (QUALITY_TOKENS.keys + CODEC_TOKENS).sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } +
            ")\\+*(?![\\p{L}\\p{Nd}])",
    )
    /** Mentions de son ([AUDIO_MULTI], [AUDIO_DOLBY]). */
    private val AUDIO = Regex(
        "(?i)(?<![\\p{L}\\p{Nd}])(?:(?:double|dual|multi)[\\s_-]*audio|dolby(?:\\s+(?:digital|plus|atmos|audio))*|e?ac3|dd\\+)(?![\\p{L}\\p{Nd}+])",
    )
    private val BACKUP = Regex("(?i)(?<![\\p{L}\\p{Nd}])(?:bkp|backup)\\d*(?![\\p{L}\\p{Nd}])")

    /** Mots qui accompagnent une mention de qualité, accentués ou non ([DESCRIPTIVE_TOKENS]). */
    private val DESCRIPTIVE = Regex(
        "(?iu)(?<![\\p{L}\\p{Nd}])(?:r[eé]solution|qualit[eé]|quality|r[eé]el(?:le)?|nati(?:ve|f)|d[eé]finition)(?![\\p{L}\\p{Nd}])",
    )

    /** « Très », « haute » ([QUALIFIER_TOKENS]). */
    private val QUALIFIERS = Regex("(?iu)(?<![\\p{L}\\p{Nd}])(?:tr[eèé]s|haute)(?![\\p{L}\\p{Nd}])")

    /** Parenthèses ou crochets vidés par le retrait des mentions (« ( ) », « [ : ] »). */
    private val EMPTY_BRACKETS = Regex("[\\[(][\\s:.,;|/_-]*[\\])]")
    private val SPACES = Regex("\\s{2,}")
    private val MARKS = Regex("\\p{Mn}+")

    /** Retire les mentions de qualité et de codec du nom, en conservant le reste tel quel. */
    private fun stripTokens(
        name: String,
        hasTokens: Boolean,
        dropDescriptive: Boolean,
        dropQualifiers: Boolean,
        backup: Boolean,
        audio: Boolean,
    ): String {
        val withoutQuality = if (hasTokens) name.replace(STRIP, " ") else name
        val withoutAudio = if (audio) withoutQuality.replace(AUDIO, " ") else withoutQuality
        val stripped = if (backup) withoutAudio.replace(BACKUP, " ") else withoutAudio
        val described = if (dropDescriptive) stripped.replace(DESCRIPTIVE, " ") else stripped
        return (if (dropQualifiers) described.replace(QUALIFIERS, " ") else described)
            .replace(EMPTY_BRACKETS, " ")
            .replace(SPACES, " ")
            .trim()
            .trim('-', '|', ':', '_', '.', ' ')
            .trim()
    }

    private fun normalizeSuperscripts(text: String): String {
        // Les exposants sont tous hors ASCII : test rapide avant la recherche dans la table.
        if (text.none { it.code > 0x7F && it in SUPERSCRIPTS }) return text
        val out = StringBuilder(text.length + 2)
        var previousWasSuperscript = false
        for (c in text) {
            val mapped = SUPERSCRIPTS[c]
            if (mapped != null) {
                // « TF1ᴴᴰ » : l'exposant forme un mot distinct.
                if (!previousWasSuperscript && out.isNotEmpty() && out.last().isLetterOrDigit()) out.append(' ')
                out.append(mapped.uppercaseChar())
                previousWasSuperscript = true
            } else {
                if (previousWasSuperscript && c.isLetterOrDigit()) out.append(' ')
                out.append(c)
                previousWasSuperscript = false
            }
        }
        return out.toString()
    }

    private fun foldAccents(text: String): String {
        // Texte ASCII (cas le plus fréquent) : rien à décomposer.
        if (text.all { it.code < 0x80 }) return text
        return Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "")
    }
}
