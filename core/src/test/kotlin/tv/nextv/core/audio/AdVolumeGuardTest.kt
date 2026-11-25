package tv.nextv.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10
import kotlin.math.pow
import kotlin.random.Random

/**
 * La détection des pages de pubs, rejouée comme sur l'appareil : le son par blocs de 20 ms, une image réduite par
 * seconde, et une tous les dixièmes de seconde quand elle les demande. Les instants des silences sont ceux
 * d'enregistrements du 14 novembre 2026 (RMC Découverte, L'Équipe, RMC Story).
 */
class AdVolumeGuardTest {

    private val random = Random(11)
    private val guard = AdVolumeGuard()
    private var gain = 1.0

    /** Temps écoulé, en blocs de 20 ms. */
    private var blocks = 0
    private var picture: ByteArray? = scene()

    /** Retard de l'image sur le son traité (le son est traité avant d'être entendu), en blocs de 20 ms. */
    private var lag = 20
    private val shown = ArrayDeque<ByteArray?>()

    private val seconds: Double get() = blocks / 50.0

    /** Un plan : grands pavés gris tirés au hasard. */
    private fun scene(): ByteArray {
        val columns = SmallFrame.WIDTH / BLOCK
        val levels = IntArray(columns * (SmallFrame.HEIGHT / BLOCK + 1)) { 20 + random.nextInt(200) }
        return ByteArray(SmallFrame.SIZE) { i -> levels[(i / SmallFrame.WIDTH) / BLOCK * columns + (i % SmallFrame.WIDTH) / BLOCK].toByte() }
    }

    /** Carton de la chaîne entre deux spots : un fond uni, son logo au milieu. */
    private fun card(background: Int = 60): ByteArray = ByteArray(SmallFrame.SIZE) { i ->
        val x = i % SmallFrame.WIDTH
        val y = i / SmallFrame.WIDTH
        (if (x in 40..55 && y in 22..31) 230 else background).toByte()
    }

    private fun black() = ByteArray(SmallFrame.SIZE) { 2 }

    /** [duration] secondes de son à [db] dBFS ; l'image à l'écran est [picture], vue avec [lag] de retard. */
    private fun play(duration: Double, db: Double = PROGRAMME) {
        repeat((duration * 50).toInt()) {
            shown.addLast(picture)
            while (shown.size > lag + 1) shown.removeFirst()
            // Une image par seconde, et une tous les dixièmes de seconde quand la détection les demande.
            if (blocks % 50 == 17 || (guard.wantsFrames && blocks % 5 == 0)) shown.first()?.let(guard::frame)
            gain = guard.process(10.0.pow(db / 10), 20)
            blocks++
        }
    }

    private fun playUntil(until: Double, db: Double = PROGRAMME) = play(until - seconds, db)

    /** Silence complet entre deux spots : l'image passe au spot suivant, par le carton de la chaîne s'il y en a un. */
    private fun junction(millis: Int, through: ByteArray? = null) {
        if (picture != null) picture = through ?: scene()
        play(millis / 1000.0, SILENCE)
        if (picture != null) picture = scene()
    }

    /** Pause dans une émission : un silence complet, sans que l'image change. */
    private fun pause(millis: Int) = play(millis / 1000.0, SILENCE)

    private fun gainDb() = 20 * log10(gain)

    @Test
    fun `a break is turned down from the first spot change after the logo left, until the logo returns`() {
        guard.logoFollowsBreaks = true
        guard.logo = LogoState.VISIBLE
        play(130.0)
        // RMC Découverte, 15 h 48 : le logo part avec le jingle, premier silence complet quinze secondes plus tard.
        guard.logo = LogoState.HIDDEN
        for ((at, millis) in RMC_DECOUVERTE) {
            playUntil(at)
            if (at > 146) assertTrue("son remonté en pleine page de pub, à $at s", guard.attenuating)
            junction(millis, through = card())
        }
        assertEquals(-24.0, gainDb(), 0.01)
        // Jusqu'à 65 secondes sans silence entre deux spots (5 min 31 s) : le son est resté bas d'un bout à l'autre.

        // Reprise du programme : le logo revient (et reste 3 s), le son aussi.
        play(6.0)
        guard.logo = LogoState.VISIBLE
        play(4.5)
        assertFalse(guard.attenuating)
        assertEquals(0.0, gainDb(), 0.01)
    }

    @Test
    fun `a logo seen again for a few seconds in the middle of a break does not end it`() {
        // W9, 16/11/2026 à 8 h 12 : le logo semblait revenir 3 à 11 s sur certaines pubs, et le son remontait.
        guard.logoFollowsBreaks = true
        guard.logo = LogoState.VISIBLE
        play(130.0)
        guard.logo = LogoState.HIDDEN
        play(10.0)
        junction(400, through = card())
        play(2.0)
        assertTrue(guard.attenuating)
        // Deux secondes de « logo » sur une pub : rien ne change.
        guard.logo = LogoState.VISIBLE
        play(2.0)
        assertTrue(guard.attenuating)
        guard.logo = LogoState.HIDDEN
        play(15.0)
        // Plus longtemps : le son remonte… et redescend aussitôt quand le logo repart dans les 20 s.
        guard.logo = LogoState.VISIBLE
        play(8.0)
        assertFalse(guard.attenuating)
        guard.logo = LogoState.HIDDEN
        play(1.0)
        assertTrue("logo reparti aussitôt : la page continue", guard.attenuating)
        // Vraie reprise du programme : le logo reste.
        guard.logo = LogoState.VISIBLE
        play(60.0)
        assertFalse(guard.attenuating)
    }

    @Test
    fun `the logo gone for 20 s opens the break even without a silence between two spots`() {
        // TF1, 15/11/2026 à 23 h 18 : des bandes-annonces enchaînées sans silence, une minute et demie de pub non baissée.
        guard.logoFollowsBreaks = true
        guard.logo = LogoState.VISIBLE
        play(130.0)
        guard.logo = LogoState.HIDDEN
        play(15.0)
        assertFalse(guard.attenuating)
        play(6.0)
        assertTrue(guard.attenuating)
        // Sur une chaîne dont le logo n'a jamais été vu revenir après une page de pubs : son départ seul ne suffit pas.
        val unsure = AdVolumeGuard()
        unsure.logo = LogoState.VISIBLE
        repeat(130 * 50) { unsure.process(10.0.pow(PROGRAMME / 10), 20) }
        unsure.logo = LogoState.HIDDEN
        repeat(60 * 50) { unsure.process(10.0.pow(PROGRAMME / 10), 20) }
        assertFalse(unsure.attenuating)
    }

    @Test
    fun `after a change of quality the logo must be seen again before its absence opens a break`() {
        // TF1, 20/11/2026 à 10 h 13 (box) : page de pubs ouverte en plein « Bonjour ! », logo pourtant à l'image. Une autre
        // version de la chaîne peut être cadrée autrement : le logo appris sur la précédente ne s'y retrouve pas.
        guard.logoFollowsBreaks = true
        guard.logo = LogoState.VISIBLE
        play(130.0)
        guard.logo = LogoState.HIDDEN
        guard.versionChanged()
        play(60.0)
        assertFalse("logo pas revu sur cette version : son absence ne dit rien", guard.attenuating)
        // Revu sur cette version : son départ compte de nouveau.
        guard.logo = LogoState.VISIBLE
        play(60.0)
        guard.logo = LogoState.HIDDEN
        play(21.0)
        assertTrue(guard.attenuating)
    }

    @Test
    fun `an ad already heard is ignored under the logo of a channel whose logo leaves during its ads`() {
        // Musique d'une émission (jingle, fond sonore du plateau) qui est aussi celle d'une pub apprise.
        guard.logoFollowsBreaks = true
        guard.logo = LogoState.VISIBLE
        play(130.0)
        assertFalse(guard.adPrint(confirmed = true, sustained = true))
        play(5.0)
        assertFalse(guard.attenuating)
        // Chaîne dont le logo n'a jamais été vu partir pendant les pubs : une pub sûre, reconnue deux fois, ouvre la page.
        val other = AdVolumeGuard()
        other.logo = LogoState.VISIBLE
        repeat(130 * 50) { other.process(10.0.pow(PROGRAMME / 10), 20) }
        assertTrue(other.adPrint(confirmed = true, sustained = true))
        assertTrue(other.attenuating)
        assertEquals(AdOpening.PRINT, other.openedBy)
    }

    @Test
    fun `the logo gone without any spot change is the programme after 30 s, and then only spot changes open a break`() {
        // France 2, 16/11/2026 à 12 h 52 : logo caché pendant la météo, puis sur « Tout le monde veut prendre sa place » ;
        // le son restait baissé plus d'une minute sans aucune jonction.
        guard.logoFollowsBreaks = true
        guard.logo = LogoState.VISIBLE
        play(130.0)
        guard.logo = LogoState.HIDDEN
        play(21.0)
        assertTrue("logo parti depuis 20 s : page ouverte", guard.attenuating)
        play(31.0)
        assertFalse("aucune jonction en 30 s : le programme", guard.attenuating)
        // Le logo revient puis repart : son départ seul n'ouvre plus rien pendant un moment.
        guard.logo = LogoState.VISIBLE
        play(60.0)
        guard.logo = LogoState.HIDDEN
        play(40.0)
        assertFalse(guard.attenuating)
        // Une vraie page de pubs, avec ses jonctions, est toujours prise.
        junction(500)
        play(20.0)
        junction(500)
        play(1.0)
        assertTrue(guard.attenuating)
    }

    @Test
    fun `a flickering logo does not open a break on its own`() {
        // Gulli, 25/11/2026 vers 1 h 45 : sur les dessins animés, le logo est mal vu et « part » toutes les 30 s ; son départ
        // ouvrait des pages de pubs en plein programme.
        guard.logoFollowsBreaks = true
        guard.logo = LogoState.VISIBLE
        play(60.0)
        repeat(3) {
            guard.logo = LogoState.HIDDEN
            play(10.0)
            guard.logo = LogoState.VISIBLE
            play(20.0)
        }
        guard.logo = LogoState.HIDDEN
        play(25.0)
        assertFalse("logo qui clignote : son départ seul n'ouvre rien", guard.attenuating)
        // Logo de nouveau stable plusieurs minutes : son départ ouvre la page, comme avant.
        guard.logo = LogoState.VISIBLE
        play(330.0)
        guard.logo = LogoState.HIDDEN
        play(21.0)
        assertTrue("logo stable puis parti depuis 20 s : page ouverte", guard.attenuating)
    }

    @Test
    fun `trailers one after the other without a silence keep the break open after 30 s`() {
        // CNews, 19/11/2026 à 4 h : deux minutes de bandes-annonces sans silence entre elles ; la page ouverte au départ
        // du logo était refermée au bout de 30 s, faute de jonction.
        guard.logoFollowsBreaks = true
        guard.logo = LogoState.VISIBLE
        play(130.0)
        guard.logo = LogoState.HIDDEN
        repeat(60) {
            picture = scene()
            play(1.5)
        }
        assertTrue("l'image change de plan sans arrêt : toujours la page de pubs", guard.attenuating)
        guard.logo = LogoState.VISIBLE
        play(4.0)
        assertFalse("logo revenu : le programme", guard.attenuating)
    }

    @Test
    fun `the pauses of a talk show are not ad breaks`() {
        // L'Équipe, « les grands live » : onze silences complets en sept minutes, l'image ne change pas.
        for ((at, millis) in L_EQUIPE_PAUSES) {
            playUntil(at)
            pause(millis)
            assertFalse("son baissé en pleine émission, à $at s", guard.attenuating)
        }
        play(60.0)
        assertFalse(guard.attenuating)
        assertEquals(0.0, gainDb(), 0.01)
    }

    @Test
    fun `scene changes under the channel logo are the programme`() {
        guard.logoFollowsBreaks = true
        guard.logo = LogoState.VISIBLE
        // RMC Story, « The Big Bang Theory » en version française : chaque changement de scène a son silence.
        for ((at, millis) in SERIES_SCENES) {
            playUntil(at)
            junction(millis, through = if (millis > 2_000) black() else null)
            assertFalse("son baissé en pleine série, à $at s", guard.attenuating)
        }
        play(60.0)
        assertFalse(guard.attenuating)
    }

    @Test
    fun `a channel never watched before - the same card twice opens the break`() {
        play(20.0)
        junction(460, through = card())
        play(15.0)
        assertFalse("une seule jonction ne suffit pas", guard.attenuating)
        junction(480, through = card())
        play(2.0)
        assertTrue("le carton de la chaîne revu : page de pubs", guard.attenuating)
        // Le carton est maintenant connu : à la page suivante, il suffit de le voir une fois.
        val known = guard.separator
        play(60.0)
        assertFalse("plus de jonction : le programme a repris", guard.attenuating)

        val later = AdVolumeGuard().apply { separator = known }
        var open = false
        repeat(3_000) { step ->
            val silent = step in 1_000 until 1_023
            if (step % 50 == 17 || (later.wantsFrames && step % 5 == 0)) later.frame(if (step in 1_020 until 1_043) card() else if (step < 1_043) FIRST else SECOND)
            later.process(10.0.pow((if (silent) SILENCE else PROGRAMME) / 10), 20)
            if (step == 1_200) open = later.attenuating
        }
        assertTrue("carton connu d'avance : la première jonction ouvre la page de pubs", open)
    }

    @Test
    fun `without a card nor a logo, three spots in a row open the break`() {
        play(30.0)
        // Spots de 20, 30 et 15 secondes : les silences sont espacés de leur durée, à une demi-seconde près.
        var at = 30.0
        for (spot in listOf(20.3, 30.4, 15.3)) {
            playUntil(at)
            junction(400)
            assertFalse(guard.attenuating)
            at += spot
        }
        playUntil(at)
        junction(400)
        play(2.0)
        assertTrue(guard.attenuating)
    }

    @Test
    fun `scene changes that are not spaced like spots open nothing`() {
        // Trois changements de scène avec silence en une minute vingt (RMC Story, 31 min 56 s) : 24 puis 54 secondes.
        play(30.0)
        junction(420)
        play(23.5)
        junction(340)
        play(53.8)
        junction(2_420, through = black())
        play(40.0)
        junction(400)
        play(30.0)
        assertFalse(guard.attenuating)
    }

    @Test
    fun `a logo that never came back after a break proves nothing by leaving`() {
        // Euronews : un habillage propre à une émission, pris pour le logo, disparaît avec elle.
        guard.logo = LogoState.VISIBLE
        play(120.0)
        guard.logo = LogoState.HIDDEN
        play(40.0)
        junction(400, through = card())
        play(60.0)
        assertFalse(guard.attenuating)
    }

    @Test
    fun `a logo seen coming back at the end of a break is trusted from then on`() {
        guard.logo = LogoState.VISIBLE
        play(120.0)
        guard.logo = LogoState.HIDDEN
        play(15.0)
        junction(400, through = card())
        play(20.0)
        junction(400, through = card())
        play(5.0)
        assertTrue(guard.attenuating)
        guard.logo = LogoState.VISIBLE
        play(3.5)
        assertFalse(guard.attenuating)
        assertTrue(guard.logoFollowsBreaks)

        // Page suivante : la première jonction après le départ du logo suffit, même sans carton.
        play(300.0)
        guard.logo = LogoState.HIDDEN
        play(25.0)
        junction(400)
        play(2.0)
        assertTrue(guard.attenuating)
    }

    @Test
    fun `without the logo, the sound comes back when the spot changes stop`() {
        play(20.0)
        junction(460, through = card())
        play(15.0)
        junction(480, through = card())
        play(30.0)
        junction(400, through = card())
        play(45.0)
        assertTrue("un spot de 45 secondes ne remonte pas le son", guard.attenuating)
        play(10.0)
        assertFalse(guard.attenuating)
        assertEquals(0.0, gainDb(), 0.01)
    }

    @Test
    fun `no picture at all - only silences spaced like spots open a break`() {
        picture = null
        // Pauses d'une émission : rien, même nombreuses.
        for ((at, millis) in L_EQUIPE_PAUSES) {
            playUntil(at)
            pause(millis)
        }
        assertFalse(guard.attenuating)

        // Quatre silences espacés comme des spots de 20 et 30 secondes.
        var at = seconds + 70
        for (spot in listOf(20.4, 30.4, 20.3, 30.4)) {
            playUntil(at)
            assertFalse(guard.attenuating)
            pause(420)
            at += spot
        }
        play(3.0)
        assertTrue(guard.attenuating)
        // Sans image, rien d'autre ne dit que le programme a repris : le son revient 40 secondes après le dernier silence.
        play(45.0)
        assertFalse(guard.attenuating)
    }

    @Test
    fun `the picture is asked for more often during a silence`() {
        play(10.0)
        assertFalse(guard.wantsFrames)
        play(0.1, SILENCE)
        assertTrue(guard.wantsFrames)
        play(1.0)
        assertTrue("encore le temps de voir l'image d'après", guard.wantsFrames)
        play(2.0)
        assertFalse(guard.wantsFrames)
    }

    @Test
    fun `long silences and the silence before the first sound open nothing`() {
        play(1.0, SILENCE)
        repeat(4) {
            play(20.0)
            picture = scene()
            play(8.0, SILENCE)
            picture = scene()
        }
        play(20.0)
        assertFalse(guard.attenuating)
    }

    @Test
    fun `the result does not depend on how early the sound is processed`() {
        for (blocksOfLag in listOf(0, 10, 35, 45)) {
            val test = AdVolumeGuardTest().apply { lag = blocksOfLag }
            test.guard.logoFollowsBreaks = true
            test.guard.logo = LogoState.VISIBLE
            test.play(100.0)
            test.guard.logo = LogoState.HIDDEN
            test.play(16.0)
            test.junction(400, through = test.card())
            test.play(3.0)
            assertTrue("image en retard de ${blocksOfLag * 20} ms", test.guard.attenuating)
        }
    }

    private companion object {
        const val PROGRAMME = -26.0
        const val SILENCE = -90.0
        const val BLOCK = 12
        val FIRST = ByteArray(SmallFrame.SIZE) { (it % 7 * 30).toByte() }
        val SECOND = ByteArray(SmallFrame.SIZE) { (200 - it % 5 * 40).toByte() }

        /**
         * RMC Découverte, 14 novembre 2026, page de pubs de 15 h 48 : début de chaque silence complet (secondes
         * depuis le début de l'enregistrement) et sa durée (millisecondes). Le jingle commence à 128 s.
         */
        val RMC_DECOUVERTE = listOf(
            145.60 to 400, 175.96 to 400, 205.30 to 420, 225.10 to 960, 246.04 to 540, 266.40 to 400, 331.68 to 400,
            347.04 to 400, 377.42 to 380, 397.78 to 380, 413.18 to 600, 443.28 to 620, 473.48 to 340,
        )

        /** L'Équipe, même jour, émission de plateau : silences complets pendant qu'un invité reprend son souffle. */
        val L_EQUIPE_PAUSES = listOf(
            68.26 to 480, 72.72 to 640, 91.20 to 740, 375.18 to 440, 401.08 to 260, 419.66 to 200, 434.62 to 240,
            446.34 to 300, 464.28 to 200, 467.16 to 220,
        )

        /** RMC Story, même jour, série doublée : silences des changements de scène (le dernier sur un écran noir). */
        val SERIES_SCENES = listOf(1916.08 to 420, 1940.02 to 340, 1994.24 to 2_420)
    }
}
