package tv.nextv.app

import android.content.Intent

/**
 * Playlist transmise par une commande depuis un ordinateur (adb), pour éviter de saisir l'adresse
 * à la télécommande :
 *
 * `adb shell "am start -n tv.nextv.app/.MainActivity --es url 'http://…' --es name 'Ma playlist'"`
 *
 * Elle ouvre l'éditeur prérempli : rien n'est enregistré sans confirmation.
 */
class PlaylistImport(val url: String, val name: String?, val username: String?, val password: String?) {

    /** Jamais l'adresse ni les identifiants dans un journal. */
    override fun toString(): String = "PlaylistImport"

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
        const val EXTRA_USERNAME = "user"
        const val EXTRA_PASSWORD = "password"

        fun from(intent: Intent?): PlaylistImport? {
            if (intent == null) return null
            fun text(key: String) = intent.getStringExtra(key)?.trim()?.takeIf { it.isNotEmpty() }
            val url = text(EXTRA_URL) ?: return null
            return PlaylistImport(url, text(EXTRA_NAME), text(EXTRA_USERNAME), intent.getStringExtra(EXTRA_PASSWORD)?.takeIf { it.isNotEmpty() })
        }
    }
}
