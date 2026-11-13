package tv.nextv.app

import android.content.pm.PackageManager
import android.os.Looper
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ActivityScenario
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tv.nextv.app.ui.home.Category
import tv.nextv.app.ui.home.HomeLayout
import tv.nextv.core.catalog.QualityParser
import tv.nextv.core.epg.EpgImporter
import tv.nextv.core.model.Channel
import tv.nextv.core.model.SourceLocation
import tv.nextv.core.model.SourceStatus
import tv.nextv.core.model.StreamDescriptor
import tv.nextv.core.repository.SourceInput

/**
 * Parcours réel, comme sur la télé : l'application démarre sur l'accueil, OK sur une chaîne ouvre le lecteur, ↑ y
 * change de chaîne, puis retour à l'accueil. La sélection doit être sur la chaîne regardée en dernier.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = NexTvApplication::class, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
class ReturnFromPlayerFocusTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val application = RuntimeEnvironment.getApplication() as NexTvApplication
    private val names = listOf("TF1", "France 2", "France 3", "France 4", "France 5", "M6", "T18", "Arte", "W9", "TMC", "TFX", "NRJ 12")

    @Before
    fun television() {
        shadowOf(application.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
    }

    private fun idle(times: Int = 10) {
        repeat(times) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            Thread.sleep(30)
        }
    }

    /** Laisse passer [millis] ms d'horloge (attentes `delay` des écrans, que [idle] ne fait pas avancer). */
    private fun advance(millis: Long = 100) {
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(millis))
        compose.waitForIdle()
        Thread.sleep(20)
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        idle(5)
    }

    private fun focusedText(): String = runCatching {
        compose.onNode(isFocused()).fetchSemanticsNode().config.toString()
    }.getOrDefault("(rien)")

    /** Chaînes de la catégorie « SPORT » (après celles de la TNT), pour changer de catégorie dans le lecteur. */
    private val sports = listOf("beIN Sports 1", "RMC Sport 1", "Eurosport 1")

    private fun playlist(layout: HomeLayout) = runBlocking {
        val container = application.container
        val id = container.sourceRepository.add(SourceInput("Test", SourceLocation.Remote("http://127.0.0.1:9/list.m3u")))
        val entries = names.map { it to "TNT" } + sports.map { it to "SPORT" }
        container.playlistStore.replaceChannels(
            id,
            entries.mapIndexed { index, (name, group) ->
                Channel(position = index, name = name, stream = StreamDescriptor("http://127.0.0.1:9/live/$index.ts"), groupTitle = group, title = name)
            },
        ) {}
        val now = System.currentTimeMillis()
        container.playlistStore.updateStatus(id, SourceStatus(lastAttemptAtMillis = now, lastSuccessAtMillis = now, channelCount = entries.size))
        container.settings.setActivePlaylist(id)
        container.settings.setQuickSetupDone()
        container.settings.setCatalogImportVersion(QualityParser.VERSION)
        container.settings.setEpgImportVersion(EpgImporter.VERSION)
        container.settings.setHomeLayout(layout)
    }

    private fun zapAndReturn(layout: HomeLayout) {
        playlist(layout)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            repeat(60) { if (compose.onAllNodesWithText("France 2", substring = true).fetchSemanticsNodes().isEmpty()) idle(2) }
            // Sélection sur TF1 (à la télécommande), puis OK. Live Center : des onglets du haut, ↓ vers les catégories
            // puis → vers les chaînes.
            if (layout == HomeLayout.LIVE_CENTER) {
                press(Key.DirectionDown)
                press(Key.DirectionRight)
            }
            repeat(12) { if (!focusedText().contains("TF1")) press(Key.DirectionDown) }
            println("$layout, avant OK : ${focusedText()}")
            assertTrue("Sélection avant OK : ${focusedText()}", focusedText().contains("TF1"))
            press(Key.DirectionCenter)
            idle(20)
            // Dans le lecteur : ↑ trois fois (TF1 → France 2 → France 3 → France 4).
            repeat(3) {
                press(Key.DirectionUp)
                idle(10)
            }
            println("$layout, dernière chaîne lue : ${application.container.lastLiveChannel.value}")
            // Retour à l'accueil (appui long sur « Retour »).
            scenario.onActivity { it.onLongBack?.invoke() }
            idle(40)
            println("$layout, au retour : ${focusedText()}")
            assertTrue("$layout, sélection au retour : ${focusedText()}", focusedText().contains("France 4"))
        } finally {
            scenario.close()
        }
    }

    /**
     * Live TV : chaîne choisie dans une autre catégorie du menu des chaînes du lecteur (« SPORT »), puis retour à
     * l'accueil. L'accueil est passé à « SPORT » et la sélection est sur la chaîne regardée ; elle revenait sur TF1,
     * ouverte au départ. Le choix dans le menu est fait comme PlayerViewModel.playFromMenu (voir PlayerViewModelTest) :
     * ici les flux ne se lisent pas, et le message d'erreur du lecteur garde la sélection hors du menu.
     */
    @Test
    fun channelPickedInAnotherCategoryOfThePlayerMenu() {
        playlist(HomeLayout.LIVE_CENTER)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            repeat(60) { if (compose.onAllNodesWithText("France 2", substring = true).fetchSemanticsNodes().isEmpty()) idle(2) }
            // Catégorie « TNT » ouverte dans la colonne de gauche, puis TF1.
            press(Key.DirectionDown)
            repeat(8) { if (!focusedText().contains("TNT")) press(Key.DirectionDown) }
            assertTrue("Catégorie : ${focusedText()}", focusedText().contains("TNT"))
            press(Key.DirectionCenter)
            idle(10)
            repeat(20) { if (!focusedText().contains("TF1")) advance() }
            println("catégorie TNT ouverte : ${focusedText()}")
            if (!focusedText().contains("France") && !focusedText().contains("TF1")) press(Key.DirectionRight)
            repeat(12) { if (!focusedText().contains("TF1")) press(Key.DirectionUp) }
            assertTrue("Sélection avant OK : ${focusedText()}", focusedText().contains("TF1"))
            press(Key.DirectionCenter)
            idle(20)
            application.container.lastLiveCategory.value = Category.Group("SPORT")
            application.container.lastLiveChannel.value = "live:bein sports 1"
            idle(10)
            scenario.onActivity { it.onLongBack?.invoke() }
            idle(40)
            repeat(30) { if (!focusedText().contains("beIN Sports 1")) advance() }
            println("au retour : ${focusedText()}")
            assertTrue("Sélection au retour : ${focusedText()}", focusedText().contains("beIN Sports 1"))
        } finally {
            scenario.close()
        }
    }

    @Test
    fun streaming() = zapAndReturn(HomeLayout.STREAMING)

    @Test
    fun liveCenter() = zapAndReturn(HomeLayout.LIVE_CENTER)
}
