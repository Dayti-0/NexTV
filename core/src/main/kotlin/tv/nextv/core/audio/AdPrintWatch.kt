package tv.nextv.core.audio

/**
 * Reconnaissance des pubs déjà entendues pendant une lecture : calcule les empreintes du son ([AudioFingerprinter]), les
 * cherche toutes les demi-secondes dans [library], et dit à [guard] quand une pub connue passe. Garde aussi le son des
 * dernières minutes : à la fin d'une page de pubs sûre ([AdVolumeGuard.onBreak]), il est confié à [learner] pour que
 * ses pubs soient reconnues la prochaine fois.
 *
 * Utilisé par le fil du son, bloc par bloc ([feed]) ; [learner] et [forget] sont appelés depuis ce fil et doivent
 * renvoyer le travail (plus long) à un autre.
 */
class AdPrintWatch(
    private val library: AdPrintLibrary,
    val guard: AdVolumeGuard,
    private val learner: (prints: IntArray, loud: BooleanArray, confirmed: Boolean) -> Unit,
    private val forget: (AdPrintLibrary.Chunk) -> Unit = {},
) {
    private var fingerprinter: AudioFingerprinter? = null
    private var rate = 0

    // Les dernières minutes d'empreintes, avec l'heure (horloge du son de [guard]) de chacune.
    private val prints = IntArray(HISTORY)
    private val loud = BooleanArray(HISTORY)
    private val times = LongArray(HISTORY)
    private var total = 0L
    private var sinceQuery = 0
    private var clock = 0L

    private val block = IntArray(AdPrintLibrary.BLOCK)
    private val blockLoud = BooleanArray(AdPrintLibrary.BLOCK)

    /** Pub connue reconnue en ce moment (pour les infos de lecture). */
    @Volatile
    var recognized: Boolean = false
        private set

    /** Pubs reconnues depuis le début de la lecture. */
    @Volatile
    var matches: Int = 0
        private set

    /** Chaque empreinte calculée, avec son instant (horloge du son) : pour l'enregistrement d'analyse. */
    var onPrint: ((print: Int, loud: Boolean, time: Long) -> Unit)? = null

    private var lastMatch: AdPrintLibrary.Match? = null
    private var underLogo = 0

    init {
        guard.onBreak = { start, end, confirmed -> learn(start, end, confirmed) }
    }

    /**
     * Son reçu ([samples], mono, entre −1 et 1, à [sampleRate] Hz), traité par [guard] jusqu'à l'instant [now] (son
     * horloge, en millisecondes).
     */
    fun feed(samples: FloatArray, count: Int, sampleRate: Int, now: Long) {
        if (sampleRate != rate) {
            rate = sampleRate
            fingerprinter = AudioFingerprinter(sampleRate)
        }
        clock = now
        fingerprinter?.feed(samples, count, ::add)
    }

    /** Empreinte déjà calculée (enregistrement rejoué), à l'instant [now] de l'horloge du son. */
    fun print(print: Int, audible: Boolean, now: Long) {
        clock = now
        add(print, audible)
    }

    private fun add(print: Int, audible: Boolean) {
        onPrint?.invoke(print, audible, clock)
        val at = (total % HISTORY).toInt()
        prints[at] = print
        loud[at] = audible
        times[at] = clock
        total++
        if (++sinceQuery >= QUERY_EVERY && total >= AdPrintLibrary.BLOCK) {
            sinceQuery = 0
            query()
        }
    }

    private fun query() {
        if (library.size == 0) return
        copy(total - AdPrintLibrary.BLOCK, AdPrintLibrary.BLOCK, block, blockLoud)
        val match = library.match(block, blockLoud)
        recognized = match != null
        if (match == null) {
            lastMatch = null
            underLogo = 0
            return
        }
        // Deux recherches de suite sur le même morceau, au même endroit : la ressemblance ne tient pas au hasard.
        val previous = lastMatch
        val sustained = previous != null && previous.chunk === match.chunk && kotlin.math.abs(match.offset - previous.offset - QUERY_EVERY) <= DRIFT
        lastMatch = match
        if (previous == null || previous.chunk !== match.chunk) matches++
        val accepted = guard.adPrint(match.chunk.confirmed, sustained)
        if (accepted) return
        // Morceau à l'essai reconnu sous le logo de la chaîne : c'est le programme qui repasse, il est oublié.
        if (!match.chunk.confirmed && guard.logo == LogoState.VISIBLE && sustained && ++underLogo >= FORGET_AFTER) {
            underLogo = 0
            forget(match.chunk)
        }
    }

    /** Fin d'une page de pubs, entre [start] et [end] (horloge du son) : son son est appris. */
    private fun learn(start: Long, end: Long, confirmed: Boolean) {
        val first = maxOf(0L, total - HISTORY)
        var from = -1L
        var to = -1L
        for (n in first until total) {
            val time = times[(n % HISTORY).toInt()]
            if (from < 0 && time >= start + TRIM_MILLIS) from = n
            if (time <= end - TRIM_MILLIS) to = n
        }
        if (from < 0 || to <= from) return
        val count = (to - from + 1).toInt()
        val outPrints = IntArray(count)
        val outLoud = BooleanArray(count)
        copy(from, count, outPrints, outLoud)
        learner(outPrints, outLoud, confirmed)
    }

    private fun copy(from: Long, count: Int, intoPrints: IntArray, intoLoud: BooleanArray) {
        for (i in 0 until count) {
            val at = ((from + i) % HISTORY).toInt()
            intoPrints[i] = prints[at]
            intoLoud[i] = loud[at]
        }
    }

    private companion object {
        /** Dix minutes de son (une page de pubs en dure rarement plus de sept). */
        val HISTORY = (10 * 60 * AudioFingerprinter.PER_SECOND).toInt()

        /** Une recherche toutes les demi-secondes. */
        val QUERY_EVERY = (AudioFingerprinter.PER_SECOND / 2).toInt()

        /** Écart toléré entre deux reconnaissances successives du même morceau (empreintes). */
        const val DRIFT = 4

        /** Le début et la fin d'une page de pubs ne sont pas appris (programme tout proche). */
        const val TRIM_MILLIS = 600L

        /** Reconnaissances sous le logo avant d'oublier un morceau à l'essai. */
        const val FORGET_AFTER = 4
    }
}
