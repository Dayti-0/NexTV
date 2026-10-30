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
    fun `other files are refused`() {
        assertThrows(BackupFormatException::class.java) { BackupCodec.decode("pas du json") }
        assertThrows(BackupFormatException::class.java) { BackupCodec.decode("""{"format":"autre"}""") }
        assertThrows(BackupFormatException::class.java) { BackupCodec.decode("""{"format":"nextv-backup","version":99}""") }
    }
}
