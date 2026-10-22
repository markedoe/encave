package app.encave.data

import android.content.Context
import androidx.core.content.edit
import app.encave.core.DishCategory
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

    private val frequency = MutableStateFlow(SyncFrequency.fromRaw(prefs.getString(KEY_SYNC_FREQUENCY, null)))

    /** How often this device syncs away from home (the in-app loop and the background work). */
    val syncFrequency: StateFlow<SyncFrequency> = frequency.asStateFlow()

    fun setSyncFrequency(value: SyncFrequency) {
        prefs.edit { putString(KEY_SYNC_FREQUENCY, value.raw) }
        frequency.value = value
    }

    private val notifyChanges = MutableStateFlow(prefs.getBoolean(KEY_SYNC_NOTIFICATIONS, false))

    /** A notification when a sync brings changes made on another device (off by default). */
    val syncNotifications: StateFlow<Boolean> = notifyChanges.asStateFlow()

    fun setSyncNotifications(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SYNC_NOTIFICATIONS, enabled) }
        notifyChanges.value = enabled
    }

    private val dish = MutableStateFlow(
        DishCategory.entries.firstOrNull { it.raw == prefs.getString(KEY_PAIRING_DISH, null) } ?: DishCategory.RedMeat,
    )

    /** The dish last chosen in food pairing (red meat at first). */
    val pairingDish: StateFlow<DishCategory> = dish.asStateFlow()

    fun setPairingDish(value: DishCategory) {
        prefs.edit { putString(KEY_PAIRING_DISH, value.raw) }
        dish.value = value
    }

    private companion object {
        const val KEY_PAIRING_DISH = "pairing_dish"
        const val KEY_SYNC_FREQUENCY = "sync_frequency"
        const val KEY_SYNC_NOTIFICATIONS = "sync_notifications"
        const val KEY_STUDIO_LOOK = "studio_look"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_CELLAR_TABLE = "cellar_table"
    }
}

/** How often a device syncs away from home. The sync on the same Wi-Fi after a change does not depend on it. */
enum class SyncFrequency(val raw: String, val minutes: Int?) {
    EveryMinute("1", 1),
    Every5Minutes("5", 5),
    Every15Minutes("15", 15),

    /** Only when the user taps Sync now: no loop, no background work, no round after a change. */
    Manual("manual", null);

    val intervalMs: Long? get() = minutes?.let { it * 60_000L }

    companion object {
        fun fromRaw(raw: String?): SyncFrequency = entries.firstOrNull { it.raw == raw } ?: EveryMinute
    }
}
