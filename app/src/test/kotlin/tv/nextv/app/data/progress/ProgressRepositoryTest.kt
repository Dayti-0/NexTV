package tv.nextv.app.data.progress

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tv.nextv.core.model.ContentKind

/** « Reprendre la lecture » : films et épisodes seulement, jamais un direct (« DAZN 1 » dans les séries). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProgressRepositoryTest {

    private lateinit var db: ProgressDatabase
    private lateinit var progress: ProgressRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ProgressDatabase::class.java).allowMainThreadQueries().build()
        progress = ProgressRepository(db, clock = { 1_000L })
    }

    @After
    fun tearDown() = db.close()

    private fun entry(key: String, position: Long, duration: Long, episode: String? = "S01E02") =
        ProgressEntity(1, key, ContentKind.SERIES.name, 10, key, episode, null, position, duration, 0)

    @Test
    fun `a live stream is never saved, even classified as a series`() = runBlocking {
        progress.save(entry("series:dazn 1", position = 5 * 60_000L, duration = 0))
        progress.save(entry("series:dark", position = 5 * 60_000L, duration = 50 * 60_000L))
        assertEquals(listOf("series:dark"), progress.observeAll(1, ContentKind.SERIES.name).first().map { it.itemKey })
    }

    @Test
    fun `a live stream saved by an earlier version is not listed, the next episode to start is`() = runBlocking {
        db.progressDao().put(entry("series:dazn 1", position = 5 * 60_000L, duration = 0, episode = null))
        progress.saveNext(entry("series:lupin", position = 50 * 60_000L, duration = 52 * 60_000L, episode = "S02E04"))
        assertEquals(listOf("series:lupin"), progress.observeAll(1, ContentKind.SERIES.name).first().map { it.itemKey })
    }
}
