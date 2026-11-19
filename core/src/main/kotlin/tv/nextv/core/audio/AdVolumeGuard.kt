package tv.nextv.core.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Logo de la chaîne à l'image, d'après [LogoWatcher] : il disparaît pendant les publicités. */
enum class LogoState {
    /** Pas de logo connu pour cette chaîne (ou image non lisible) : seuls le son et les changements de plan décident. */
    UNKNOWN,
    VISIBLE,
    HIDDEN,
}

/**
 * Baisse le son pendant les pages de publicité.
 *
 * Les publicités ne sont pas plus fortes que le programme. Ce qui les distingue, c'est leur **jonction** :
 * entre deux spots, la chaîne laisse un silence complet (0,2 à 1,5 s) pendant lequel l'image passe d'un spot
 * à l'autre, souvent par un carton à ses couleurs (dix images sur les chaînes RMC, trois sur L'Équipe). Mesuré
 * le 14 novembre 2026 sur les directs de RMC Story, RMC Découverte, RMC Life, L'Équipe, BFM 2, TV Monaco,
 * Euronews et Fréquence Novelas.
 *
 * Un silence seul ne suffit pas : une émission de plateau en fait à chaque pause d'un invité (76 en 36 minutes
 * sur L'Équipe, 214 sur BFM 2), sans que l'image change. Un silence ne compte donc comme jonction que si
 * l'image d'après n'est plus celle d'avant ([frame] : images réduites, rapprochées pendant les silences — voir
 * [wantsFrames]).
 *
 * Une série ou un film changent aussi de scène sur un silence. Le logo de la chaîne les départage : il reste
 * à l'image pendant le programme et disparaît pendant les publicités ([logo]).
 *
 * - Logo affiché : c'est le programme, le son n'est jamais baissé.
 * - Logo parti depuis peu, et déjà vu revenir à la fin d'une page de pubs ([logoFollowsBreaks]) : la première
 *   jonction ouvre la page.
 * - Sinon : deux jonctions montrant le même carton ([separator], ensuite reconnu dès la première), deux jonctions
 *   juste après le départ du logo, ou quatre jonctions espacées comme des spots (durées de 5 en 5 secondes).
 * - Sortie : dès que le logo revient ; sinon quand plus aucune jonction n'arrive ([HOLD_MILLIS]).
 *   Page ouverte par le seul départ du logo : refermée sans jonction en 30 s, sauf si l'image change de plan comme
 *   des bandes-annonces enchaînées ([TRAILER_CUTS]).
 * - Sans image du tout (appareil qui ne laisse pas lire l'écran) : le son seul, avec prudence — quatre silences
 *   espacés comme des spots. Mieux vaut alors manquer une page de pubs que baisser le son d'un programme.
 *
 * Deux renforts passent avant ces règles :
 * - les repères de pub du flux ([cue], [AdCues]) : quand la chaîne ou le fournisseur les laisse, ils décident seuls ;
 * - les pubs déjà entendues ([adPrint], reconnues par [AdPrintWatch]) : une pub connue ouvre la page de pubs dès ses
 *   premières secondes (sauf sous le logo de la chaîne) et la fait durer. À la fin d'une page sûre (annoncée par le
 *   flux, ou ouverte par le logo ou le carton et refermée par le retour du logo), son son est appris ([onBreak]).
 *
 * Utilisé bloc par bloc par le lecteur (blocs d'environ 20 ms) : [process] reçoit l'énergie moyenne du bloc
 * (carré moyen des échantillons, entre 0 et 1) et sa durée, et rend le gain linéaire à appliquer. L'horloge
 * est la durée de son reçue : une image est datée du son traité à son arrivée. Le son est traité un peu
 * avant d'être entendu (jusqu'à une seconde), ce dont les délais ci-dessous tiennent compte.
 */
class AdVolumeGuard(private val attenuationDb: Double = -24.0) {

    private var elapsed = 0L
    private var heard = false
    private var silentMillis = 0L
    private var silenceStart = 0L

    /** Silences finis dont l'image d'après n'est pas encore arrivée : début et fin. */
    private val pending = ArrayDeque<LongArray>()
    private var burstUntil = 0L

    private val scenes = SceneMemory()
    private var lastPicture = NEVER

    /** Changements de plan récents (une image par seconde au plus comparée à la précédente) : heures, en anneau. */
    private val cuts = LongArray(CUTS_KEPT) { NEVER }
    private var nextCut = 0
    private val cutFrame = ByteArray(SmallFrame.SIZE)
    private var cutFrameAt = NEVER

    /** Jonctions récentes qui se suivent à moins de [MAX_INTERVAL_MILLIS]. */
    private val chain = ArrayList<Junction>()
    private var lastJunction = 0L

    /** Carton de la chaîne entre deux spots, une fois reconnu (deux jonctions de suite l'ont montré). */
    var separator: ByteArray? = null
        @Synchronized get

        @Synchronized set

    private var logoSince = 0L
    private var logoSeen = false

    /**
     * Le logo suit les coupures de cette chaîne : il a déjà été vu revenir à la fin d'une page de pubs. Tant que ce
     * n'est pas le cas, son absence ne prouve rien (un habillage propre à une émission disparaît avec elle).
     */
    var logoFollowsBreaks = false
        @Synchronized get

        @Synchronized set

    /** Logo resté affiché pendant des publicités : il ne dit rien du programme, jusqu'à ce qu'il disparaisse. */
    private var logoStays = false

    /** Logo qui n'est pas revenu avec le programme : son absence ne dit rien, jusqu'à ce qu'il revienne. */
    private var logoMissing = false

    /** Logo absent pendant la page de pubs en cours : s'il revient à sa fin, c'est bien qu'il suit les coupures. */
    private var awayInBreak = false

    private var gainDb = 0.0

    /** Début de la page de pubs en cours (horloge du son), et ce qui l'a ouverte. */
    private var breakStart = 0L
    private var opening = Opening.NONE

    /** Dernière preuve de pub (jonction ou pub reconnue) de la page en cours. */
    private var lastEvidence = 0L

    /**
     * Repères de pub du flux ([AdCues]) au moment présent, tenus à jour par le lecteur. Dès que le flux en annonce,
     * ils décident seuls.
     */
    var cue: CueState = CueState.NONE
        @Synchronized get

        @Synchronized set

    /**
     * Fin d'une page de pubs dont le son peut être appris ([AdPrintWatch]) : début et fin (horloge du son), et si elle
     * était annoncée par le flux (sûre) ou seulement détectée. Appelé depuis [process].
     */
    var onBreak: ((start: Long, end: Long, confirmed: Boolean) -> Unit)? = null

    /**
     * Ce que la détection voit et décide, pour l'enregistrement d'analyse : instant (horloge du son), quoi
     * (« ouverture », « fermeture », « jonction », « empreinte »), et le détail (ce qui a ouvert ou fermé la page…).
     * Appelé depuis [process], [frame] ou [adPrint] : ne doit faire que noter.
     */
    var onEvent: ((time: Long, what: String, detail: String) -> Unit)? = null

    /** Page de pubs annulée à la main (« ce n'est pas une pub ») : aucune nouvelle avant cet instant. */
    private var dismissedUntil = NEVER

    /** Dernière pub reconnue notée ([onEvent]) : une seule note toutes les quelques secondes. */
    private var printNotedAt = NEVER

    /** Horloge du son : durée de son reçue, en millisecondes. */
    val now: Long
        @Synchronized get() = elapsed

    /** Mise au point : ce que la détection décide, au fil du son. */
    internal var trace: ((String) -> Unit)? = null

    /** Son baissé (page de pubs détectée). */
    @Volatile
    var attenuating: Boolean = false
        private set

    /**
     * Images rapprochées demandées (une dizaine par seconde) : un silence est en cours ou vient de finir, et
     * c'est l'image de ce moment qui dit s'il sépare deux spots.
     */
    @Volatile
    var wantsFrames: Boolean = false
        private set

    /** Logo de la chaîne à l'image, tenu à jour par le lecteur. */
    var logo: LogoState = LogoState.UNKNOWN
        @Synchronized set(value) {
            if (field == value) return
            field = value
            logoSince = elapsed
            when (value) {
                LogoState.VISIBLE -> {
                    logoSeen = true
                    logoMissing = false
                }
                // Un logo qui disparaît suit de nouveau les coupures : il est réécouté.
                LogoState.HIDDEN -> logoStays = false
                LogoState.UNKNOWN -> Unit
            }
        }

    /** Image réduite ([SmallFrame]) affichée en ce moment. */
    @Synchronized
    fun frame(small: ByteArray) {
        if (small.size < SmallFrame.SIZE) return
        scenes.add(elapsed, small)
        lastPicture = elapsed
        if (cutFrameAt == NEVER || elapsed - cutFrameAt >= CUT_SPACING_MILLIS) {
            if (cutFrameAt != NEVER && SmallFrame.changedShare(cutFrame, small) >= CUT_SHARE) {
                cuts[nextCut] = elapsed
                nextCut = (nextCut + 1) % CUTS_KEPT
            }
            small.copyInto(cutFrame, 0, 0, SmallFrame.SIZE)
            cutFrameAt = elapsed
        }
    }

    /** Changements de plan depuis [since]. */
    private fun cutsSince(since: Long): Int = cuts.count { it != NEVER && it > since }

    @Synchronized
    fun process(meanSquare: Double, durationMillis: Long): Double {
        val duration = durationMillis.coerceAtLeast(0)
        elapsed += duration
        if (decibels(meanSquare) < SILENCE_DB) {
            if (silentMillis == 0L) silenceStart = elapsed - duration
            silentMillis += duration
            // Sous le logo de la chaîne, c'est le programme : images rapprochées seulement pour un long silence.
            if (silentMillis >= (if (showsProgramme()) BURST_AFTER_UNDER_LOGO_MILLIS else BURST_AFTER_MILLIS)) burstUntil = elapsed + SETTLE_MILLIS + BURST_MARGIN_MILLIS
        } else {
            // Le silence d'avant le premier son (ouverture du flux) ne sépare rien.
            if (heard && silentMillis in MIN_GAP_MILLIS..MAX_GAP_MILLIS) pending.addLast(longArrayOf(silenceStart, elapsed - duration))
            silentMillis = 0
            heard = true
        }
        wantsFrames = elapsed < burstUntil
        settle()
        if (cue != CueState.NONE) {
            followCues()
        } else if (attenuating) {
            if (logo == LogoState.HIDDEN) awayInBreak = true
            leaveIfOver()
        } else if (logo == LogoState.HIDDEN && closedByLogoAt != NEVER && elapsed - closedByLogoAt <= REOPEN_MILLIS && !dismissed()) {
            // Logo revenu quelques secondes puis reparti : c'était une pub qui lui ressemblait, la page continue.
            closedByLogoAt = NEVER
            open(Opening.LOGO_AGAIN)
        } else if (logoLeftForAds()) {
            // Logo parti et toujours absent, sur une chaîne où il suit les coupures : la page de pubs a commencé, même
            // sans silence repéré entre deux pubs (bandes-annonces enchaînées…).
            logoOnlyFor = logoSince
            open(Opening.LOGO_ONLY)
        }

        // Gain lissé : baisse en une demi-seconde, retour en une seconde (pas de claquement).
        val target = if (attenuating) attenuationDb else 0.0
        val step = duration * (if (target < gainDb) -attenuationDb / FALL_MILLIS else -attenuationDb / RISE_MILLIS)
        gainDb = if (target < gainDb) max(target, gainDb - step) else min(target, gainDb + step)
        return 10.0.pow(gainDb / 20)
    }

    /** Repères du flux : la page de pubs commence et finit avec eux. */
    private fun followCues() {
        val inBreak = cue == CueState.BREAK
        if (inBreak && !attenuating) {
            open(Opening.CUE)
        } else if (attenuating) {
            if (logo == LogoState.HIDDEN) awayInBreak = true
            if (!inBreak) {
                // Logo parti pendant la page annoncée et revenu avec le programme : il suit les coupures.
                if (awayInBreak && showsProgramme()) logoFollowsBreaks = true
                close(learn = opening == Opening.CUE, end = elapsed, confirmed = true, reason = "reperes du flux")
            }
        }
    }

    /**
     * Pub déjà entendue reconnue à l'instant ([AdPrintWatch]).
     * @param confirmed apprise d'une page de pubs annoncée par le flux.
     * @param sustained reconnue aussi à la recherche précédente, au même endroit.
     * @return `false` si la reconnaissance n'a pas été retenue (logo de la chaîne à l'image, repères du flux contraires).
     */
    @Synchronized
    fun adPrint(confirmed: Boolean, sustained: Boolean): Boolean {
        val kind = if (confirmed) "sure" else "a l'essai"
        when (cue) {
            CueState.BREAK -> return true
            CueState.PROGRAMME -> return false.also { notePrint("$kind, ignoree (reperes du flux : programme)") }
            CueState.NONE -> Unit
        }
        // Sous le logo : seulement une pub sûre, reconnue deux fois de suite (une chanson de pub dans une émission…).
        if (programmeBack()) {
            if (!(confirmed && sustained)) return false.also { notePrint("$kind, ignoree (logo a l'image)") }
            // Pub sûre sous le logo : la chaîne le garde pendant ses pubs, il ne dit plus rien jusqu'à son départ.
            logoStays = true
        }
        notePrint(kind)
        if (!confirmed && !sustained && !attenuating) return true
        lastEvidence = elapsed
        lastJunction = elapsed
        if (!attenuating && !dismissed()) {
            trace?.invoke("pub reconnue à $elapsed ms (${if (confirmed) "sûre" else "à l'essai"})")
            open(Opening.PRINT)
        }
        return true
    }

    /**
     * « Ce n'est pas une pub » (options du lecteur) : le son revient tout de suite, et la détection ne rouvre pas de
     * page de pubs pendant [DISMISS_MILLIS] (les repères du flux, eux, décident toujours).
     */
    @Synchronized
    fun dismiss() {
        dismissedUntil = elapsed + DISMISS_MILLIS
        chain.clear()
        if (attenuating && cue == CueState.NONE) close(learn = false, end = elapsed, confirmed = false, reason = "annule a la main")
    }

    private fun dismissed() = dismissedUntil != NEVER && elapsed < dismissedUntil

    private fun notePrint(detail: String) {
        if (printNotedAt != NEVER && elapsed - printNotedAt < PRINT_NOTE_MILLIS) return
        printNotedAt = elapsed
        onEvent?.invoke(elapsed, "empreinte", detail)
    }

    private fun open(by: Opening) {
        attenuating = true
        opening = by
        breakStart = elapsed
        lastEvidence = elapsed
        // L'ouverture compte comme une preuve : le délai sans jonction part d'ici.
        lastJunction = maxOf(lastJunction, elapsed)
        trace?.invoke("son baissé à $elapsed ms")
        onEvent?.invoke(elapsed, "ouverture", by.label + " (logo ${logo.name.lowercase()}, ${chain.size} jonction(s) de suite)")
    }

    /** Fin de la page de pubs ; son son est appris s'il est sûr d'en être ([learn]). */
    private fun close(learn: Boolean, end: Long, confirmed: Boolean, reason: String) {
        onEvent?.invoke(elapsed, "fermeture", reason + (if (learn) ", son appris (${if (confirmed) "sur" else "a l'essai"})" else ""))
        attenuating = false
        awayInBreak = false
        val start = breakStart
        opening = Opening.NONE
        // Les jonctions de cette page ne comptent pas pour la suivante.
        chain.clear()
        trace?.invoke("son remis à $elapsed ms")
        if (learn && end > start) onBreak?.invoke(start, end, confirmed)
    }

    /** Silences dont l'image d'après est arrivée (ou n'arrivera plus) : jonction ou simple pause ? */
    private fun settle() {
        while (pending.isNotEmpty()) {
            val (start, end) = pending.first()
            if (elapsed < end + SETTLE_MILLIS) return
            val after = scenes.firstFrom(end + AFTER_MILLIS)
            // Image en retard (une par seconde seulement) : on l'attend encore un peu.
            if (after == null && pictures() && elapsed < end + SETTLE_MILLIS + LATE_MILLIS) return
            pending.removeFirst()
            onSilence(start, end, after)
        }
    }

    private fun pictures() = lastPicture != NEVER && elapsed - lastPicture <= PICTURE_MAX_AGE_MILLIS

    private fun onSilence(start: Long, end: Long, after: ByteArray?) {
        val before = scenes.lastBefore(start)
        if (before == null || after == null) {
            // Pas d'image de ce moment : le son seul, pour un silence assez long.
            if (end - start >= BLIND_MIN_GAP_MILLIS) onJunction(Junction(end, card = null, blind = true, underLogo = showsProgramme()))
            return
        }
        // Carton entre deux spots : une image unie, le temps du silence, qui n'est ni celle d'avant ni celle d'après.
        // Celui de la chaîne, une fois connu, est reconnu même entre deux spots qui lui ressemblent.
        var card: ByteArray? = null
        var channelCard = false
        scenes.between(start, end + AFTER_MILLIS - CARD_MARGIN_MILLIS) { candidate ->
            if (channelCard || SmallFrame.flatShare(candidate) < FLAT_SHARE) return@between
            val signature = SmallFrame.signature(candidate)
            if (black(signature)) return@between
            if (separator?.let { SmallFrame.sameSignature(it, signature) } == true) {
                card = signature
                channelCard = true
            } else if (card == null && SmallFrame.changedShare(before, candidate) >= CARD_SHARE && SmallFrame.changedShare(candidate, after) >= CARD_SHARE) {
                card = signature
            }
        }
        // Même plan avant et après : une pause dans le programme, pas une jonction.
        if (!channelCard && SmallFrame.changedShare(before, after) < CUT_SHARE) return
        onJunction(Junction(end, card, blind = false, underLogo = showsProgramme()))
    }

    /** Logo affiché, et qui suit bien les coupures : c'est le programme. */
    private fun showsProgramme() = logo == LogoState.VISIBLE && !logoStays

    /**
     * Pendant une page de pubs, le logo ne dit « programme » qu'une fois revu pendant [LOGO_BACK_MILLIS] : certaines pubs
     * dessinent quelques secondes ce qui ressemble au logo (le son remontait et redescendait pendant la page).
     */
    private fun programmeBack() = showsProgramme() && (!attenuating || elapsed - logoSince >= LOGO_BACK_MILLIS)

    /** Logo de ce flux parti depuis [LOGO_ONLY_MILLIS] (et pas plus de [LOGO_JUST_HIDDEN_MILLIS]), sur une chaîne où il suit les coupures. */
    private fun logoLeftForAds(): Boolean {
        if (dismissed() || !logoFollowsBreaks || !logoSeen || logoMissing || logo != LogoState.HIDDEN || !pictures()) return false
        if (logoOnlyFor == logoSince) return false
        // Ce départ du logo seul s'est déjà trompé il y a peu sur ce flux : seules les jonctions ouvrent la page.
        if (logoOnlyFailedAt != NEVER && elapsed - logoOnlyFailedAt < LOGO_ONLY_PAUSE_MILLIS) return false
        return elapsed - logoSince in LOGO_ONLY_MILLIS..LOGO_JUST_HIDDEN_MILLIS
    }

    /** Départ du logo qui a déjà ouvert une page de pubs à lui seul (une seule fois par départ). */
    private var logoOnlyFor = NEVER

    /** Dernière page ouverte par le seul départ du logo et refermée faute de jonction. */
    private var logoOnlyFailedAt = NEVER

    /** Fin de la dernière page de pubs, si c'est le retour du logo qui l'a fermée. */
    private var closedByLogoAt = NEVER

    private fun onJunction(junction: Junction) {
        val previous = chain.lastOrNull()
        if (previous != null && junction.time - previous.time < MIN_INTERVAL_MILLIS) {
            // Deux silences trop rapprochés : la même jonction, elle ne compte qu'une fois.
            if (previous.card == null) previous.card = junction.card
            return
        }
        if (previous != null && junction.time - previous.time > MAX_INTERVAL_MILLIS) chain.clear()
        chain += junction
        if (chain.size > CHAIN_KEPT) chain.removeAt(0)
        lastJunction = junction.time
        if (attenuating) lastEvidence = junction.time
        // Le même carton à deux jonctions de suite : celui que la chaîne met entre ses spots.
        val repeated = junction.card?.takeIf { card -> drawn(card) && chain.any { it !== junction && it.card?.let { other -> SmallFrame.sameSignature(other, card) } == true } }
        if (repeated != null) separator = repeated
        val kind = when {
            junction.blind -> "sans image"
            junction.card != null && (repeated != null || separator?.let { SmallFrame.sameSignature(it, junction.card!!) } == true) -> "carton de la chaine"
            junction.card != null -> "carton"
            else -> "coupe"
        }
        trace?.invoke("jonction à ${junction.time} ms : $kind, suite de ${chain.size}, logo $logo")
        onEvent?.invoke(junction.time, "jonction", "$kind, ${chain.size} de suite")
        if (!attenuating && cue == CueState.NONE && !dismissed()) {
            val by = opens(junction, repeated != null)
            if (by != Opening.NONE) open(by)
        }
    }

    /** La jonction qui vient d'être vue ouvre-t-elle une page de pubs ? Et par quel indice. */
    private fun opens(junction: Junction, repeatedCard: Boolean): Opening {
        val card = junction.card
        val channelCard = card != null && (repeatedCard || separator?.let { SmallFrame.sameSignature(it, card) } == true)
        if (showsProgramme()) {
            // Logo à l'image : le programme… sauf si le carton de la chaîne revient sous lui de jonction en jonction
            // (chaîne qui garde son logo pendant les publicités) : il n'est alors plus écouté.
            val keepsLogo = channelCard && chain.takeLastWhile { it.underLogo }.size >= LOGO_STAYS_CHAIN
            if (keepsLogo) logoStays = true
            return if (keepsLogo) Opening.CARD else Opening.NONE
        }
        if (!junction.blind) {
            if (channelCard) return Opening.CARD
            if (logo == LogoState.HIDDEN && logoSeen && !logoMissing) {
                val hiddenFor = junction.time - logoSince
                val justHidden = hiddenFor <= LOGO_JUST_HIDDEN_MILLIS
                val opened = if (logoFollowsBreaks) {
                    // Logo qui suit les coupures : la première jonction après son départ, ou deux jonctions de suite.
                    justHidden || (card != null && hiddenFor <= LOGO_HIDDEN_CARD_MILLIS) || chain.size >= 2
                } else {
                    // Logo pas encore vu revenir d'une page de pubs : il vient de partir et deux jonctions se suivent.
                    justHidden && chain.size >= 2
                }
                if (opened) return Opening.LOGO
            }
        }
        // Logo inconnu, ou pas d'image du tout : des jonctions espacées comme des spots.
        return if (spotsInARow()) Opening.SPOTS else Opening.NONE
    }

    /** Les dernières jonctions se suivent comme des spots : [SPOT_CHAIN] de suite, aux durées de 5 en 5 secondes. */
    private fun spotsInARow(): Boolean =
        chain.size >= SPOT_CHAIN && (1 until SPOT_CHAIN).all { spotLike(chain[chain.size - it].time - chain[chain.size - it - 1].time) }

    private fun leaveIfOver() {
        // Logo vu sur ce flux et absent : la page de pubs continue, même sans silence entre deux spots.
        val away = logo == LogoState.HIDDEN && logoSeen && !logoMissing
        var logoBack = false
        var reason = "logo revenu"
        // Page ouverte par le seul départ du logo, et l'image qui change de plan comme des bandes-annonces enchaînées
        // (sans silence entre elles) : elle reste ouverte sans jonction, mais pas indéfiniment.
        val trailers = opening == Opening.LOGO_ONLY && elapsed - breakStart <= TRAILERS_MAX_MILLIS &&
            cutsSince(elapsed - LOGO_ONLY_HOLD_MILLIS) >= TRAILER_CUTS
        val over = when {
            // Le logo est revenu (et resté) : le programme a repris. Parti pendant la page et revenu à sa fin, il suit les coupures.
            programmeBack() -> {
                if (awayInBreak) logoFollowsBreaks = true
                logoBack = true
                true
            }
            // Page ouverte par le seul départ du logo, et toujours aucune jonction : le logo s'est caché sur le programme
            // (météo, générique, logo qui change), pas pour des pubs.
            opening == Opening.LOGO_ONLY && lastJunction <= breakStart && elapsed - breakStart > LOGO_ONLY_HOLD_MILLIS && !trailers -> {
                reason = "logo parti mais aucune jonction"
                logoOnlyFailedAt = elapsed
                true
            }
            !trailers && elapsed - lastJunction > (if (away) HOLD_LOGO_HIDDEN_MILLIS else if (pictures()) HOLD_MILLIS else HOLD_BLIND_MILLIS) -> {
                reason = if (away) "logo toujours absent, plus de jonction" else "plus de jonction"
                // Plus de jonction depuis longtemps et toujours pas de logo : il ne revient pas avec ce programme.
                if (away) logoMissing = true
                true
            }
            else -> false
        }
        if (!over) return
        // Page ouverte par un indice sûr (logo, carton, pub connue) et refermée par le retour du logo : son son est
        // appris, à l'essai, jusqu'à la dernière preuve de pub (la suite peut déjà être le programme).
        close(learn = logoBack && opening != Opening.SPOTS, end = lastEvidence, confirmed = false, reason = reason)
        closedByLogoAt = if (logoBack) elapsed else NEVER
    }

    /** Écart entre deux jonctions qui tient à des spots : durées de 5 en 5 secondes, plus le silence qui les sépare. */
    private fun spotLike(intervalMillis: Long): Boolean {
        if (intervalMillis !in MIN_INTERVAL_MILLIS..MAX_INTERVAL_MILLIS) return false
        val beyond = (intervalMillis + SPOT_EARLY_MILLIS) % SPOT_STEP_MILLIS
        return beyond <= SPOT_EARLY_MILLIS + SPOT_LATE_MILLIS
    }

    /** Image noire : quelques images noires séparent aussi deux scènes d'un film. */
    private fun black(signature: ByteArray) = signature.all { (it.toInt() and 0xFF) < BLACK_LEVEL }

    /** Carton qui porte un dessin (le logo de la chaîne) : un simple aplat blanc ou de couleur ne se reconnaît pas. */
    private fun drawn(signature: ByteArray) = signature.maxOf { it.toInt() and 0xFF } - signature.minOf { it.toInt() and 0xFF } >= DRAWN_CONTRAST

    private fun decibels(energy: Double) = 10 * log10(energy + 1e-12)

    private class Junction(val time: Long, var card: ByteArray?, val blind: Boolean, val underLogo: Boolean)

    /** Ce qui a ouvert la page de pubs en cours. */
    private enum class Opening(val label: String) {
        NONE(""),
        CUE("reperes du flux"),
        CARD("carton de la chaine"),
        LOGO("logo parti"),
        PRINT("pub deja entendue"),
        LOGO_ONLY("logo parti depuis 20 s"),
        LOGO_AGAIN("logo reparti juste apres son retour"),
        SPOTS("jonctions espacees comme des spots"),
    }

    /** Les dernières images réduites reçues, avec l'instant de leur arrivée. */
    private class SceneMemory {
        private val times = LongArray(CAPACITY) { NEVER }
        private val frames = Array(CAPACITY) { ByteArray(SmallFrame.SIZE) }
        private var next = 0

        fun add(time: Long, small: ByteArray) {
            times[next] = time
            small.copyInto(frames[next], 0, 0, SmallFrame.SIZE)
            next = (next + 1) % CAPACITY
        }

        /** Dernière image arrivée avant [time], si elle n'est pas trop vieille. */
        fun lastBefore(time: Long): ByteArray? {
            var best = -1
            for (i in 0 until CAPACITY) {
                val t = times[i]
                if (t != NEVER && t <= time && time - t <= BEFORE_MAX_AGE_MILLIS && (best < 0 || t >= times[best])) best = i
            }
            return frames.getOrNull(best)
        }

        /** Première image arrivée à partir de [time]. */
        fun firstFrom(time: Long): ByteArray? {
            var best = -1
            for (i in 0 until CAPACITY) {
                val t = times[i]
                if (t != NEVER && t >= time && (best < 0 || t < times[best])) best = i
            }
            return frames.getOrNull(best)
        }

        /** Images arrivées après [from] et jusqu'à [to], dans l'ordre. */
        fun between(from: Long, to: Long, action: (ByteArray) -> Unit) {
            for (step in 0 until CAPACITY) {
                val i = (next + step) % CAPACITY
                val t = times[i]
                if (t != NEVER && t > from && t <= to) action(frames[i])
            }
        }

        private companion object {
            const val CAPACITY = 96
        }
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE

        /** Silence complet : bien en dessous des passages calmes d'un film (−50 dB environ). */
        const val SILENCE_DB = -60.0

        /** Silence complet le plus court et le plus long entre deux spots. */
        const val MIN_GAP_MILLIS = 160L
        const val MAX_GAP_MILLIS = 4_000L

        /** Écart entre deux jonctions : plus court, c'est la même ; plus long, elles ne sont pas de la même page. */
        const val MIN_INTERVAL_MILLIS = 2_500L
        const val MAX_INTERVAL_MILLIS = 65_000L

        /** Sans nouvelle jonction après ce délai, le programme a repris (le plus long spot dure une minute). */
        const val HOLD_MILLIS = 50_000L

        /** Sans image, rien d'autre ne dit que le programme a repris : le délai est plus court. */
        const val HOLD_BLIND_MILLIS = 40_000L

        /** Logo connu et toujours absent : la page de pubs continue, même sans silence entre deux spots. */
        const val HOLD_LOGO_HIDDEN_MILLIS = 80_000L

        /** Logo disparu depuis moins longtemps que cela : la première jonction ouvre la page de pubs. */
        const val LOGO_JUST_HIDDEN_MILLIS = 90_000L

        /** Logo revu pendant une page de pubs : le programme n'a repris qu'au bout de ce temps (logo qui « clignote » sur une pub). */
        const val LOGO_BACK_MILLIS = 3_000L

        /** Logo reparti moins de ce temps après le retour qui a fermé la page : la page continue. */
        const val REOPEN_MILLIS = 20_000L

        /** Logo parti depuis ce temps sur une chaîne où il suit les coupures : page de pubs, même sans jonction. */
        const val LOGO_ONLY_MILLIS = 20_000L

        /** Page ouverte par le seul départ du logo : refermée si aucune jonction ne vient dans ce délai. */
        const val LOGO_ONLY_HOLD_MILLIS = 30_000L

        /**
         * Changements de plan dans les [LOGO_ONLY_HOLD_MILLIS] dernières secondes à partir desquels une telle page reste
         * ouverte : bandes-annonces enchaînées (15 à 26 relevés le 19/11/2026), contre 1 à 3 pour une météo ou un générique.
         */
        const val TRAILER_CUTS = 12

        /** Page gardée ouverte par les seuls changements de plan ([TRAILER_CUTS]) : au plus ce temps après son ouverture. */
        const val TRAILERS_MAX_MILLIS = 150_000L

        /** Une image comparée à la précédente au plus toutes les [CUT_SPACING_MILLIS] pour compter les changements de plan. */
        const val CUT_SPACING_MILLIS = 900L
        const val CUTS_KEPT = 64

        /** Après une telle erreur, le départ du logo seul n'ouvre plus de page pendant ce temps. */
        const val LOGO_ONLY_PAUSE_MILLIS = 15 * 60_000L

        /** Pareil pour une jonction qui montre un carton (après des bandes-annonces, par exemple). */
        const val LOGO_HIDDEN_CARD_MILLIS = 240_000L

        /** Logo inconnu : nombre de jonctions de suite, espacées comme des spots, pour ouvrir la page de pubs. */
        const val SPOT_CHAIN = 4

        /** Logo affiché : jonctions de suite montrant le carton de la chaîne, à partir desquelles il n'est plus écouté. */
        const val LOGO_STAYS_CHAIN = 3
        const val CHAIN_KEPT = 8

        /** Délai après la fin d'un silence pour juger l'image : le son est traité jusqu'à une seconde avant d'être entendu. */
        const val AFTER_MILLIS = 1_300L
        const val SETTLE_MILLIS = 1_600L
        const val LATE_MILLIS = 1_000L
        const val CARD_MARGIN_MILLIS = 100L
        const val BEFORE_MAX_AGE_MILLIS = 2_500L
        const val PICTURE_MAX_AGE_MILLIS = 3_000L

        /** Images rapprochées : demandées après ce temps de silence, jusqu'au jugement de l'image. */
        const val BURST_AFTER_MILLIS = 60L
        const val BURST_AFTER_UNDER_LOGO_MILLIS = 280L
        const val BURST_MARGIN_MILLIS = 200L

        /** Part de l'image qui doit avoir changé entre avant et après le silence : un autre plan. */
        const val CUT_SHARE = 0.5f

        /** Carton : image unie (part de la teinte dominante), différente de celles d'avant et d'après. */
        const val FLAT_SHARE = 0.7f
        const val CARD_SHARE = 0.3f

        /** Image noire : tous ses pavés sous ce niveau. Carton dessiné : cet écart entre son pavé le plus clair et le plus sombre. */
        const val BLACK_LEVEL = 40
        const val DRAWN_CONTRAST = 30

        /** Sans image de ce moment : silence d'au moins cette durée. */
        const val BLIND_MIN_GAP_MILLIS = 280L

        /** Durées des spots : de 5 en 5 secondes, à cette avance et ce retard près (le silence qui les sépare). */
        const val SPOT_STEP_MILLIS = 5_000L
        const val SPOT_EARLY_MILLIS = 700L
        const val SPOT_LATE_MILLIS = 1_800L

        /** Après « ce n'est pas une pub » : pas de nouvelle page de pubs pendant ce temps. */
        const val DISMISS_MILLIS = 3 * 60_000L

        /** Pub reconnue notée au plus toutes les 5 secondes dans l'enregistrement d'analyse. */
        const val PRINT_NOTE_MILLIS = 5_000L

        const val FALL_MILLIS = 500.0
        const val RISE_MILLIS = 1_000.0
    }
}
