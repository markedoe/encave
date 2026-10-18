package app.encave.widget

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import app.encave.BuildConfig

/** The app's home screen widgets. */
object Widgets {
    private const val PREFS = "widgets"
    private const val KEY_PREVIEWS = "previews_version"

    /** Redraws the widgets placed on the home screen (none: nothing to do). */
    suspend fun update(context: Context) {
        runCatching { TonightWidget().updateAll(context) }
        runCatching { CellarWidget().updateAll(context) }
    }

    /**
     * Android 15+: gives the widget picker previews drawn from the sample cellar, once per
     * app version (the system limits how often they can be set). Earlier versions show
     * the preview layouts of the widget info files.
     */
    suspend fun publishPreviews(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_PREVIEWS, -1) == BuildConfig.VERSION_CODE) return
        val manager = GlanceAppWidgetManager(context)
        val success = GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS
        val done = runCatching {
            manager.setWidgetPreviews(TonightWidgetReceiver::class) == success &&
                manager.setWidgetPreviews(CellarWidgetReceiver::class) == success
        }.getOrDefault(false)
        if (done) prefs.edit { putInt(KEY_PREVIEWS, BuildConfig.VERSION_CODE) }
    }
}
