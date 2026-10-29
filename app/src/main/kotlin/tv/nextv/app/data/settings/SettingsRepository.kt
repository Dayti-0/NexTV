package tv.nextv.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import tv.nextv.app.data.security.KeystoreSecretCipher
import tv.nextv.core.net.ProxyScope
import tv.nextv.core.net.ProxySettings
import tv.nextv.core.net.ProxyType

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Réglages de l'application. Les identifiants du proxy sont chiffrés comme ceux des sources. */
class SettingsRepository(
    context: Context,
    private val cipher: KeystoreSecretCipher,
) {
    private val dataStore = context.applicationContext.settingsDataStore

    val proxySettings: Flow<ProxySettings> = dataStore.data
        .map { prefs ->
            ProxySettings(
                enabled = prefs[PROXY_ENABLED] ?: false,
                type = prefs[PROXY_TYPE]?.let { name -> ProxyType.entries.firstOrNull { it.name == name } } ?: ProxyType.HTTP,
                host = prefs[PROXY_HOST].orEmpty(),
                port = prefs[PROXY_PORT] ?: ProxySettings.DEFAULT_PORT,
                credentials = prefs[PROXY_CREDENTIALS]?.let(cipher::decryptCredentials),
                scopes = prefs[PROXY_SCOPES]
                    ?.mapNotNull { name -> ProxyScope.entries.firstOrNull { it.name == name } }
                    ?.toSet()
                    ?: ProxyScope.entries.toSet(),
                bypassLocalAddresses = prefs[PROXY_BYPASS_LOCAL] ?: true,
            )
        }
        .distinctUntilChanged()

    suspend fun currentProxySettings(): ProxySettings = proxySettings.first()

    suspend fun saveProxySettings(settings: ProxySettings) {
        val encryptedCredentials = settings.credentials?.let(cipher::encryptCredentials)
        dataStore.edit { prefs ->
            if (encryptedCredentials != null) {
                prefs[PROXY_CREDENTIALS] = encryptedCredentials
            } else {
                prefs.remove(PROXY_CREDENTIALS)
            }
            prefs[PROXY_ENABLED] = settings.enabled
            prefs[PROXY_TYPE] = settings.type.name
            prefs[PROXY_HOST] = settings.host.trim()
            prefs[PROXY_PORT] = settings.port
            prefs[PROXY_SCOPES] = settings.scopes.map { it.name }.toSet()
            prefs[PROXY_BYPASS_LOCAL] = settings.bypassLocalAddresses
        }
    }

    private companion object {
        val PROXY_ENABLED = booleanPreferencesKey("proxy_enabled")
        val PROXY_TYPE = stringPreferencesKey("proxy_type")
        val PROXY_HOST = stringPreferencesKey("proxy_host")
        val PROXY_PORT = intPreferencesKey("proxy_port")
        val PROXY_CREDENTIALS = stringPreferencesKey("proxy_credentials")
        val PROXY_SCOPES = stringSetPreferencesKey("proxy_scopes")
        val PROXY_BYPASS_LOCAL = booleanPreferencesKey("proxy_bypass_local")
    }
}
