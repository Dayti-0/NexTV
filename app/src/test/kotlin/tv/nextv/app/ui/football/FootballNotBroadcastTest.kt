package tv.nextv.app.ui.football

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.nextv.app.data.db.EpgChannelRow
import tv.nextv.app.data.db.FootballProgrammeRow
import tv.nextv.app.ui.guide.GuideChannel
import tv.nextv.app.ui.theme.NexTvTheme
import tv.nextv.app.ui.theme.ThemeStyle
import tv.nextv.core.football.Competition
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * Matchs qu'aucune chaîne ne diffuse d'après le guide (la plupart des matchs de Liga et de Serie A) :
 * cités en fin de journée, sans carte, sauf ceux des équipes suivies et ceux que le guide ne couvre
 * pas encore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi")
class FootballNotBroadcastTest {

    @get:Rule
    val compose = createComposeRule()

    private val minute = 60_000L
    private val day = 24 * 60 * minute
    private val noon = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 12)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Le guide annonce les matchs des trois prochains jours. */
    private val guideUntil = noon + 3 * day

    private fun match(competition: Competition, home: String, away: String, at: Long, channel: String? = null) = FootballMatch(
        competition = competition,
        home = home,
        away = away,
        kickoffMillis = at,
        endMillis = at + 115 * minute,
        channel = channel?.let { GuideChannel(home.length.toLong(), it, null, null, emptyList()) },
    )

    private val leeds = match(Competition.PREMIER_LEAGUE, "Leeds", "Spurs", noon + 90 * minute, "FR| CANAL+ FOOT")
    private val malaga = match(Competition.LA_LIGA, "Málaga", "Racing", noon + 120 * minute)
    private val cagliari = match(Competition.SERIE_A, "Cagliari", "Frosinone", noon + 180 * minute)
    private val valencia = match(Competition.LA_LIGA, "Valencia", "Real Madrid", noon + 540 * minute, "FR| DAZN 2")

    /** Dans cinq jours : le guide ne l'annonce pas encore. */
    private val elche = match(Competition.LA_LIGA, "Elche", "Betis", noon + 5 * day)
    private val matches = listOf(leeds, malaga, cagliari, valencia, elche)

    private fun time(at: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))

    @Test
    fun `matches that no channel shows are set aside, except for followed teams`() {
        val selection = selectMatches(matches, guideUntil, favoriteTeams = setOf("Cagliari"))
        assertEquals(listOf(leeds, cagliari, valencia, elche), selection.cards)
        assertEquals(listOf(malaga), selection.others)
        // « Mes équipes » : tous les matchs des équipes suivies.
        assertEquals(emptyList<FootballMatch>(), selectMatches(matches, guideUntil, favoriteTeams = emptySet(), all = true).others)
        // Guide pas encore chargé : aucun match n'est écarté.
        assertEquals(matches, selectMatches(matches, guideUntilMillis = 0, favoriteTeams = emptySet()).cards)
    }

    @Test
    fun `the guide announces matches until two days before its last football programme`() {
        val channel = EpgChannelRow("beinsports1.fr", 1, "FR| BEIN SPORTS 1", "FR| BEIN SPORTS 1", null)
        fun row(start: Long) = BroadcastRow(channel, null, FootballProgrammeRow("beinsports1.fr", "Football", null, null, "Football", null, start, start + 120 * minute))
        assertEquals(noon + 6 * day + 120 * minute, guideUntil(listOf(row(noon), row(noon + 8 * day), row(noon + day))))
        assertEquals(0L, guideUntil(emptyList()))
    }

    @Test
    fun `matches not broadcast are listed at the end of the day`() {
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                FootballContent(
                    FootballUiState(loading = false, now = noon, matches = matches, guideUntilMillis = guideUntil),
                    competition = null,
                    onCompetition = {},
                    onPlay = {},
                    favoriteTeams = setOf("Cagliari"),
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Leeds – Spurs").assertIsDisplayed()
        compose.onAllNodesWithText("Málaga – Racing").assertCountEquals(0)
        compose.onNodeWithText("Pas dans le guide TV : ${time(malaga.kickoffMillis)} Málaga – Racing (Liga)").assertIsDisplayed()
        // Équipe suivie : sa carte reste, avec la raison de l'absence de chaîne.
        compose.onNodeWithText("Cagliari – Frosinone").assertIsDisplayed()
        compose.onNodeWithText("Pas dans le guide TV").assertIsDisplayed()
    }

    @Test
    fun `matches the guide does not cover yet keep their card`() {
        compose.setContent {
            NexTvTheme(ThemeStyle.NIGHT) {
                FootballContent(
                    FootballUiState(loading = false, now = noon, matches = matches, guideUntilMillis = guideUntil),
                    competition = Competition.LA_LIGA,
                    onCompetition = {},
                    onPlay = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithText("Málaga – Racing").assertCountEquals(0)
        compose.onNodeWithText("Elche – Betis").assertIsDisplayed()
        compose.onNodeWithText("Pas encore dans le guide").assertIsDisplayed()
    }
}
