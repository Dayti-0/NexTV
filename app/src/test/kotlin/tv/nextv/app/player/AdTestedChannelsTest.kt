package tv.nextv.app.player

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AdTestedChannelsTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `time recorded on a channel is kept, whatever its version`() {
        val file = File(folder.root, "ad_tested.tsv")
        val tested = AdTestedChannels(file)
        // Gulli : pas encore enregistrée sur cet appareil ; TF1 part avec ce que Claude a déjà analysé.
        assertEquals(32 * 60_000L, tested.millis("Gulli"))
        assertEquals(82 * 60_000L, tested.millis("TF1"))
        tested.add("Gulli HD", 30_000)
        tested.add("GULLI FHD", 40_000)
        tested.add("Ma chaîne", 5_000)
        tested.save()
        val again = AdTestedChannels(file)
        assertEquals(32 * 60_000L + 70_000L, again.millis("Gulli"))
        assertEquals(5_000L, again.millis("ma chaîne"))
    }
}
