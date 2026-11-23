package ua.kpi.building_audit.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val title: String) { SYSTEM("Як у системі"), LIGHT("Світла"), DARK("Темна") }

/** Вибір теми зберігається в SharedPreferences і переживає перезапуск застосунку. */
class ThemeSettings(context: Context) {
    private val prefs = context.getSharedPreferences("ui_settings", Context.MODE_PRIVATE)
    private val _mode = MutableStateFlow(
        prefs.getString(KEY, null)?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.SYSTEM
    )
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    fun set(mode: ThemeMode) {
        prefs.edit().putString(KEY, mode.name).apply()
        _mode.value = mode
    }

    private companion object {
        const val KEY = "theme_mode"
    }
}
