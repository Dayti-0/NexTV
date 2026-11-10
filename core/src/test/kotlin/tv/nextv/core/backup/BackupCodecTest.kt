package tv.nextv.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import tv.nextv.core.model.Credentials

class BackupCodecTest {

    @Test
    fun `a backup survives the round trip`() {
        val backup = Backup(
            exportedAtMillis = 1_793_390_280_000L,
            playlists = listOf(
                PlaylistBackup(
                    name = "Maison",
                    url = "http://line.example/get.php?username=u&password=p&type=m3u_plus",
                    userAgent = "VLC/3.0.20 LibVLC/3.0.20",
                    mirrors = listOf("http://backup.example:8080"),
                    credentials = Credentials("alice", "s3cr\"et"),
                    active = true,
                    favorites = listOf(FavoriteBackup("LIVE", "tf1", "TF1", null, 0), FavoriteBackup("MOVIE", "dune-2021", "Dune", "http://img/dune.jpg", 1)),
                    hiddenGroups = listOf(HiddenGroupBackup("LIVE", "Adultes"), HiddenGroupBackup("LIVE", "")),
                    progress = listOf(
                        ProgressBackup("movie:dune:2021", "MOVIE", "Dune", null, "http://img/dune.jpg", 1_800_000, 9_300_000, 1_793_000_000_000L),
                        ProgressBackup("series:lupin", "SERIES", "Lupin", "S02E03", null, 0, 0, 1_793_000_100_000L),
                    ),
                    watched = listOf(WatchedBackup("movie:heat:1995", 1_792_000_000_000L), WatchedBackup("series:lupin|S2E2", 1_793_000_050_000L)),
                ),
                PlaylistBackup(name = "Autre", url = "https://example.com/liste.m3u"),
            ),
            settings = listOf(
                SettingBackup("auto_quality", true),
                SettingBackup("channel_columns", 6),
                SettingBackup("active_since", 42L),
                SettingBackup("aspect_mode", "ZOOM"),
                SettingBackup("scopes", setOf("PLAYLIST", "EPG")),
            ),
        )

        assertEquals(backup, BackupCodec.decode(BackupCodec.encode(backup)))
    }

    @Test
    fun `a backup made before resume positions were saved is still read`() {
        val old = """
            {"format":"nextv-backup","version":1,"exportedAt":1,
             "playlists":[{"name":"Maison","url":"http://line.example/get.php","favorites":[],"hiddenGroups":[]}],
             "settings":[]}
        """.trimIndent()
        val playlist = BackupCodec.decode(old).playlists.single()
        assertEquals(emptyList<ProgressBackup>(), playlist.progress)
        assertEquals(emptyList<WatchedBackup>(), playlist.watched)
    }

    @Test
    fun `damaged resume entries are skipped`() {
        val damaged = """
            {"format":"nextv-backup","version":1,"exportedAt":1,
             "playlists":[{"name":"Maison","url":"http://line.example/get.php",
               "progress":[{"kind":"MOVIE","title":"sans clé"},{"itemKey":"movie:dune:2021","kind":"MOVIE","positionMs":-5,"durationMs":100}],
               "watched":[{"watchedAt":3},{"itemKey":"movie:heat:1995","watchedAt":4}]}],
             "settings":[]}
        """.trimIndent()
        val playlist = BackupCodec.decode(damaged).playlists.single()
        assertEquals(listOf(ProgressBackup("movie:dune:2021", "MOVIE", "", null, null, 0, 100, 0)), playlist.progress)
        assertEquals(listOf(WatchedBackup("movie:heat:1995", 4)), playlist.watched)
    }

    @Test
    fun `other files are refused`() {
        assertThrows(BackupFormatException::class.java) { BackupCodec.decode("pas du json") }
        assertThrows(BackupFormatException::class.java) { BackupCodec.decode("""{"format":"autre"}""") }
        assertThrows(BackupFormatException::class.java) { BackupCodec.decode("""{"format":"nextv-backup","version":99}""") }
    }
}
