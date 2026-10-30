package tv.nextv.app.data.reminders

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tv.nextv.app.data.settings.Reminder
import tv.nextv.app.data.settings.SettingsRepository

/**
 * Surveille les rappels : un programme qui commence dans moins de 5 minutes est signalé une fois
 * ([due], jusqu'à [dismiss]), puis son rappel est retiré.
 */
class ReminderWatcher(
    private val settings: SettingsRepository,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutableDue = MutableStateFlow<Reminder?>(null)
    val due: StateFlow<Reminder?> = mutableDue.asStateFlow()

    init {
        scope.launch {
            while (true) {
                if (mutableDue.value == null) {
                    val now = clock()
                    settings.reminders.first().firstOrNull { it.startMillis - LEAD_MILLIS <= now && now < it.startMillis + LATE_MILLIS }?.let {
                        settings.removeReminder(it.key)
                        mutableDue.value = it
                    }
                }
                delay(CHECK_MILLIS)
            }
        }
    }

    fun dismiss() {
        mutableDue.value = null
    }

    private companion object {
        const val LEAD_MILLIS = 5 * 60_000L

        /** Appareil éteint à l'heure du rappel : encore signalé pendant le premier quart d'heure. */
        const val LATE_MILLIS = 15 * 60_000L
        const val CHECK_MILLIS = 20_000L
    }
}
