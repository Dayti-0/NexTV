package tv.nextv.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.nextv.app.AppContainer
import tv.nextv.core.model.Credentials
import tv.nextv.core.net.ProxyProblem
import tv.nextv.core.net.ProxyScope
import tv.nextv.core.net.ProxySettings
import tv.nextv.core.net.ProxyType

data class ProxyFormState(
    val loading: Boolean = true,
    val enabled: Boolean = false,
    val type: ProxyType = ProxyType.HTTP,
    val host: String = "",
    val port: String = ProxySettings.DEFAULT_PORT.toString(),
    val username: String = "",
    val password: String = "",
    val scopes: Set<ProxyScope> = ProxyScope.entries.toSet(),
    val bypassLocal: Boolean = true,
    val problems: List<ProxyProblem> = emptyList(),
    val savedAt: Long? = null,
)

class ProxySettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val mutableState = MutableStateFlow(ProxyFormState())
    val state: StateFlow<ProxyFormState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val current = container.settings.currentProxySettings()
            mutableState.value = ProxyFormState(
                loading = false,
                enabled = current.enabled,
                type = current.type,
                host = current.host,
                port = current.port.toString(),
                username = current.credentials?.username.orEmpty(),
                password = current.credentials?.password.orEmpty(),
                scopes = current.scopes,
                bypassLocal = current.bypassLocalAddresses,
            )
        }
    }

    fun edit(transform: (ProxyFormState) -> ProxyFormState) {
        mutableState.update { transform(it).copy(problems = emptyList(), savedAt = null) }
    }

    fun toggleScope(scope: ProxyScope, enabled: Boolean) = edit {
        it.copy(scopes = if (enabled) it.scopes + scope else it.scopes - scope)
    }

    fun save() {
        val form = state.value
        // Un proxy SOCKS ne peut pas être authentifié : les identifiants ne concernent que HTTP.
        val credentials = form.username.trim()
            .takeIf { form.type == ProxyType.HTTP && (it.isNotEmpty() || form.password.isNotEmpty()) }
            ?.let { Credentials(it, form.password) }
        val settings = ProxySettings(
            enabled = form.enabled,
            type = form.type,
            host = form.host.trim(),
            port = form.port.trim().toIntOrNull() ?: 0,
            credentials = credentials,
            scopes = form.scopes,
            bypassLocalAddresses = form.bypassLocal,
        )
        val problems = settings.validate()
        if (problems.isNotEmpty()) {
            mutableState.update { it.copy(problems = problems) }
            return
        }
        viewModelScope.launch {
            container.settings.saveProxySettings(settings)
            mutableState.update { it.copy(savedAt = System.currentTimeMillis()) }
        }
    }
}
