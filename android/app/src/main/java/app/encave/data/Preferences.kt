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

    private val table = MutableStateFlow(prefs.getBoolean(KEY_CELLAR_TABLE, false))

    /** On wide screens, the cellar as a sortable table instead of a list. */
    val cellarTable: StateFlow<Boolean> = table.asStateFlow()

    fun setCellarTable(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_CELLAR_TABLE, enabled) }
        table.value = enabled
    }

    private val studio = MutableStateFlow(prefs.getBoolean(KEY_STUDIO_LOOK, true))

    /** Label photos shown evened out on a clean background; the stored photo is not changed. */
    val studioLook: StateFlow<Boolean> = studio.asStateFlow()

    fun setStudioLook(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_STUDIO_LOOK, enabled) }
        studio.value = enabled
    }

    private companion object {
        const val KEY_STUDIO_LOOK = "studio_look"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_CELLAR_TABLE = "cellar_table"
    }
}
