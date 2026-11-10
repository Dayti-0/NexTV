package tv.nextv.app

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/** Fermetures par le système, constatées au lancement suivant et notées dans « Dernier incident ». */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SystemExitsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var now = 1_700_000_000_000L

    private val direct = object : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    private fun log() = CrashLog(
        file = File(context.filesDir, "last_crash.txt"),
        version = BuildConfig.VERSION_NAME,
        clock = { now },
        memory = { "appli 150 / 384 Mo" },
        io = direct,
        redact = { it },
    )

    @Before
    fun setUp() {
        context.filesDir.listFiles()?.forEach { it.delete() }
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 12)
    }

    private fun exit(reason: Int, importance: Int, status: Int = 0, timestamp: Long = now) {
        shadowOf(context.getSystemService(ActivityManager::class.java)).addApplicationExitInfo(
            ApplicationExitInfoBuilder.newBuilder()
                .setProcessName(context.packageName)
                .setReason(reason)
                .setImportance(importance)
                .setStatus(status)
                .setTimestamp(timestamp)
                .setPss(310 * 1024)
                .setRss(420 * 1024)
                .build(),
        )
    }

    private fun check(log: CrashLog = log()) = SystemExits.check(context, log) { it() }

    @Test
    fun aKillForMemoryWhileWatchingIsAnIncident() {
        val running = log()
        running.foreground(SystemExits.bootCount(context))
        running.screen("home")
        running.section("guide")
        now += 2_000
        exit(ApplicationExitInfo.REASON_LOW_MEMORY, RunningAppProcessInfo.IMPORTANCE_FOREGROUND)

        val next = log()
        check(next)
        val entry = next.last()!!
        assertEquals(CrashLog.Kind.SYSTEM, entry.kind)
        assertTrue(entry.summary, "mémoire insuffisante" in entry.summary)
        assertTrue(entry.details, "Écran affiché : home (guide)" in entry.details)
        assertTrue(entry.details, "310 Mo (PSS)" in entry.details)
        assertTrue(entry.details, "appli 150 / 384 Mo" in entry.details)
    }

    @Test
    fun aKillInTheBackgroundIsNot() {
        exit(ApplicationExitInfo.REASON_LOW_MEMORY, RunningAppProcessInfo.IMPORTANCE_CACHED)
        val log = log()
        check(log)
        assertNull(log.last())
    }

    @Test
    fun theSameExitIsReportedOnce() {
        exit(ApplicationExitInfo.REASON_CRASH_NATIVE, RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
        val log = log()
        check(log)
        assertTrue(log.last()!!.summary, "bibliothèque native" in log.last()!!.summary)
        // « Effacer », puis nouveau lancement : la même fermeture ne revient pas.
        log.clear()
        check(log)
        assertNull(log.last())
    }

    @Test
    fun aCrashRecordedByTheAppKeepsItsTrace() {
        val log = log()
        log.record(IllegalStateException("boom"))
        now += 500
        // Signal 9 juste après le plantage noté par l'application : c'est le même, sa trace est gardée.
        exit(ApplicationExitInfo.REASON_SIGNALED, RunningAppProcessInfo.IMPORTANCE_FOREGROUND, status = 9)
        check(log)
        assertEquals(CrashLog.Kind.CRASH, log.last()!!.kind)
    }

    @Test
    fun anAnrGivesTheMainThread() {
        val trace = "\"main\" prio=5 tid=1 Blocked\n  at tv.nextv.app.ui.guide.GuideViewModel.load(GuideViewModel.kt:250)\n  at android.os.Looper.loop(Looper.java:1)\n\n\"other\" prio=5\n  at x.Y.z(Y.kt:1)\n"
        shadowOf(context.getSystemService(ActivityManager::class.java)).addApplicationExitInfo(
            ApplicationExitInfoBuilder.newBuilder()
                .setProcessName(context.packageName)
                .setReason(ApplicationExitInfo.REASON_ANR)
                .setImportance(RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
                .setTimestamp(now)
                .setTraceInputStream(trace.byteInputStream())
                .build(),
        )
        val log = log()
        check(log)
        val entry = log.last()!!
        assertTrue(entry.summary, "ne répondait plus" in entry.summary)
        assertTrue(entry.details, "GuideViewModel.load" in entry.details)
        assertTrue(entry.details, "x.Y.z" !in entry.details)
    }

    // --- Avant Android 11 : seule la session restée ouverte renseigne ------------------------------

    private fun session(boot: Int = 12, version: String = BuildConfig.VERSION_NAME) =
        CrashLog.Session(atMillis = now, version = version, boot = boot, screen = "home (guide)", memory = "appli 150 / 384 Mo", pressure = "critique, 2 s avant")

    @Test
    fun anOpenSessionOnTheSameBootIsAnIncident() {
        val log = log()
        SystemExits.checkSession(context, log, session())
        val entry = log.last()!!
        assertEquals(CrashLog.Kind.SYSTEM, entry.kind)
        assertTrue(entry.details, "Écran affiché : home (guide)" in entry.details)
        assertTrue(entry.details, "Mémoire signalée critique, 2 s avant" in entry.details)
    }

    @Test
    fun aPowerCutOrAnUpdateIsNot() {
        val log = log()
        // Appareil éteint puis rallumé (clé TV alimentée par la télé).
        SystemExits.checkSession(context, log, session(boot = 11))
        assertNull(log.last())
        // Application mise à jour pendant qu'elle était ouverte.
        SystemExits.checkSession(context, log, session(version = "0.0.1"))
        assertNull(log.last())
    }

    @Test
    fun aCrashNotedAfterTheSessionWins() {
        val log = log()
        val before = session()
        now += 1_000
        log.record(IllegalStateException("boom"))
        SystemExits.checkSession(context, log, before)
        assertEquals(CrashLog.Kind.CRASH, log.last()!!.kind)
    }
}
