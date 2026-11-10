package tv.nextv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.nextv.core.log.Redactor
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

class CrashLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** Écritures de la session faites tout de suite, dans le fil du test. */
    private val direct = object : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    private var now = 1_700_000_000_000L

    private fun log(version: String = "0.2.87", memory: String? = "appli 120 / 384 Mo") = CrashLog(
        file = folder.root.resolve("crash.txt"),
        version = version,
        clock = { now },
        memory = { memory },
        io = direct,
        redact = Redactor()::redact,
    )

    @Test
    fun recordsTheCrashWithoutCredentials() {
        val log = CrashLog(folder.root.resolve("crash.txt"), Redactor()::redact)
        assertNull(log.last())
        val error = IllegalStateException("Lecture de http://provider.example:8080/live/alice/s3cretpass/42.ts impossible")
        log.record(error)
        val entry = log.last()!!
        assertTrue(entry.summary.startsWith("java.lang.IllegalStateException"))
        assertEquals(CrashLog.Kind.CRASH, entry.kind)
        assertFalse("Identifiants masqués : ${entry.details}", "s3cretpass" in entry.details || "alice" in entry.details)
        assertTrue(entry.details.lines().size <= 40)
        log.clear()
        assertNull(log.last())
    }

    @Test
    fun summaryPointsAtTheCauseAndTheAppCode() {
        val file = folder.root.resolve("crash.txt")
        // Fichier d'une version précédente : la deuxième ligne ne porte que la version.
        file.writeText(
            "1700000000000\n0.2.15\njava.lang.RuntimeException: boom\n\tat android.os.Handler.dispatch(Handler.java:1)\n" +
                "\tat tv.nextv.app.ui.player.PlayerViewModel.start(PlayerViewModel.kt:10)\nCaused by: java.io.IOException: réseau\n",
        )
        val entry = CrashLog(file) { it }.last()!!
        assertEquals(1_700_000_000_000L, entry.atMillis)
        assertEquals("0.2.15", entry.version)
        assertEquals(CrashLog.Kind.CRASH, entry.kind)
        assertEquals("java.io.IOException: réseau\ntv.nextv.app.ui.player.PlayerViewModel.start(PlayerViewModel.kt:10)", entry.summary)
    }

    /** Trace d'une erreur enveloppée deux fois, chaque partie longue comme celles de Compose ou des coroutines. */
    private fun longTrace(): String = buildString {
        append("java.lang.RuntimeException: enveloppe\n")
        repeat(60) { append("\tat androidx.compose.runtime.Composer.frame$it(Composer.kt:$it)\n") }
        append("Caused by: java.lang.IllegalStateException: milieu\n")
        repeat(60) { append("\tat kotlinx.coroutines.Dispatched.run$it(Dispatched.kt:$it)\n") }
        append("Caused by: java.lang.IllegalArgumentException: Key 42 was already used\n")
        repeat(30) { append("\tat androidx.compose.foundation.lazy.LazyList.measure$it(LazyList.kt:$it)\n") }
        append("\tat tv.nextv.app.ui.guide.GuideScreenKt.GuideRow(GuideScreen.kt:590)\n")
        repeat(30) { append("\tat android.os.Looper.loop$it(Looper.java:$it)\n") }
    }

    @Test
    fun aLongTraceKeepsItsRootCause() {
        val compact = CrashLog.compact(longTrace())
        val lines = compact.lines()
        assertTrue("${lines.size} lignes", lines.size <= CrashLog.MAX_LINES)
        // Le début, chaque cause, et surtout la cause première (tout en bas de la trace complète).
        assertEquals("java.lang.RuntimeException: enveloppe", lines.first())
        assertTrue("Caused by: java.lang.IllegalStateException: milieu" in lines)
        assertTrue("Caused by: java.lang.IllegalArgumentException: Key 42 was already used" in lines)
        // La ligne du code de l'application, trop loin pour être dans les premières, est gardée quand même.
        assertTrue(lines.any { "GuideScreenKt.GuideRow" in it })
        assertEquals(
            "java.lang.IllegalArgumentException: Key 42 was already used\ntv.nextv.app.ui.guide.GuideScreenKt.GuideRow(GuideScreen.kt:590)",
            CrashLog.summary(lines),
        )
    }

    @Test
    fun aShortTraceIsKeptWhole() {
        val trace = "java.lang.IllegalStateException: boom\n\tat a.b.C.d(C.kt:1)\nCaused by: java.io.IOException: x\n\tat e.f.G.h(G.kt:2)"
        assertEquals(trace, CrashLog.compact(trace))
    }

    @Test
    fun manyCausesStillFitAndKeepTheLastOne() {
        val trace = buildString {
            append("java.lang.RuntimeException: 0\n")
            repeat(20) { append("\tat a.B.c$it(B.kt:$it)\n") }
            repeat(8) { cause ->
                append("Caused by: java.lang.RuntimeException: cause $cause\n")
                repeat(20) { append("\tat d.E.f$it(E.kt:$it)\n") }
            }
        }
        val lines = CrashLog.compact(trace).lines()
        assertTrue("${lines.size} lignes", lines.size <= CrashLog.MAX_LINES)
        assertEquals("java.lang.RuntimeException: 0", lines.first())
        assertTrue("Caused by: java.lang.RuntimeException: cause 7" in lines)
    }

    @Test
    fun aBackgroundErrorDoesNotHideACrash() {
        val log = log()
        log.record(IllegalStateException("tâche de fond"), fatal = false)
        assertEquals(CrashLog.Kind.BACKGROUND, log.last()!!.kind)
        // Un plantage le remplace…
        now += 1_000
        log.record(IllegalArgumentException("plantage"))
        assertEquals(CrashLog.Kind.CRASH, log.last()!!.kind)
        // … et n'est pas remplacé par une erreur de fond survenue ensuite.
        now += 1_000
        log.record(IllegalStateException("autre tâche de fond"), fatal = false)
        val entry = log.last()!!
        assertEquals(CrashLog.Kind.CRASH, entry.kind)
        assertTrue(entry.summary.startsWith("java.lang.IllegalArgumentException"))
        assertEquals("0.2.87", entry.version)
    }

    @Test
    fun aSystemExitIsShownWithItsReason() {
        val log = log()
        log.recordSystemExit(now - 5_000, "Fermée par le système pendant l’utilisation : mémoire insuffisante", listOf("Écran affiché : home (guide)"))
        val entry = log.last()!!
        assertEquals(CrashLog.Kind.SYSTEM, entry.kind)
        assertEquals(now - 5_000, entry.atMillis)
        assertEquals("Fermée par le système pendant l’utilisation : mémoire insuffisante", entry.summary)
        assertTrue("Écran affiché : home (guide)" in entry.details)
    }

    @Test
    fun theSessionSaysWhereTheAppWasWhenItWasKilled() {
        val log = log()
        assertNull(log.takePreviousSession())
        // Rien n'est noté tant que l'application n'est pas à l'écran.
        log.screen("home")
        assertNull(log.takePreviousSession())
        log.foreground(boot = 7)
        log.screen("home")
        log.section("guide")
        now += 3_000
        log.memoryPressure("critique")
        now += 4_000
        log.screen("home")
        log.section("guide")
        log.memoryPressure("critique")
        // Processus fermé ici par le système : au lancement suivant, la session est encore là.
        val session = log().takePreviousSession()!!
        assertEquals(7, session.boot)
        assertEquals("0.2.87", session.version)
        assertEquals("home (guide)", session.screen)
        assertEquals("appli 120 / 384 Mo", session.memory)
        assertEquals("critique, 0 s avant", session.pressure)
        assertEquals(now, session.atMillis)
        // Lue une seule fois.
        assertNull(log().takePreviousSession())
    }

    @Test
    fun leavingTheAppForgetsTheSession() {
        val log = log()
        log.foreground(boot = 7)
        log.screen("player/{channelId}")
        log.background()
        // Application quittée : une fermeture par le système n'est plus un incident.
        assertNull(log().takePreviousSession())
        // Et rien n'est noté tant qu'elle n'est pas revenue à l'écran.
        log.screen("home")
        assertNull(log().takePreviousSession())
    }
}
