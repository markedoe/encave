package app.encave.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Device-only display preferences (not part of the cellar, never exported). */
class Preferences(context: Context) {
    private val prefs = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    private val dynamic = MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC_COLOR, true))

    /** Material You colors (Android 12+); the Encave burgundy is used when off or unavailable. */
    val dynamicColor: StateFlow<Boolean> = dynamic.asStateFlow()

    fun setDynamicColor(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_DYNAMIC_COLOR, enabled) }
        dynamic.value = enabled
    }

    private companion object {
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
    }
}
