package app.encave.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.encave.ui.AppViewModel

/**
 * Objects kept for one screen (its navigation entry) while the activity is recreated. Those
 * that are [AutoCloseable] are closed when the screen is left for good.
 */
class RetainedValues : ViewModel() {
    internal val values = HashMap<String, Any>()

    override fun onCleared() {
        values.values.filterIsInstance<AutoCloseable>().forEach { it.close() }
    }
}

/**
 * Like `remember`, but kept when the activity is recreated (rotation, folding, dark mode,
 * language): for form state that cannot go in saved state, such as a photo being added.
 * [key] names the value within the screen.
 */
@Composable
fun <T : Any> rememberRetained(key: String, init: () -> T): T {
    val holder: RetainedValues = viewModel()
    @Suppress("UNCHECKED_CAST")
    return remember(holder, key) { holder.values.getOrPut(key, init) as T }
}

/** Forgets a value kept by [rememberRetained] on this screen, so the next one starts afresh. */
@Composable
fun rememberForgetRetained(): (String) -> Unit {
    val holder: RetainedValues = viewModel()
    return remember(holder) { { key -> holder.values.remove(key)?.let { (it as? AutoCloseable)?.close() } } }
}

/** Saves a set of IDs (chosen wines) as strings. */
val UuidSetSaver: Saver<Set<java.util.UUID>, Any> = Saver(
    save = { set -> ArrayList(set.map { it.toString() }) },
    restore = { saved -> (saved as List<*>).mapNotNull { runCatching { java.util.UUID.fromString(it as String) }.getOrNull() }.toSet() },
)

/**
 * Shows [content] once the cellar has been read: editors look up their record and start
 * their form from it, so before that an edit would start empty (and save as a new record).
 */
@Composable
fun WhenLoaded(viewModel: AppViewModel, content: @Composable () -> Unit) {
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    if (loaded) content()
}
