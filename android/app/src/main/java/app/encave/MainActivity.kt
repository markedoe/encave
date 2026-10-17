package app.encave

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.encave.ui.AppViewModel
import app.encave.ui.EncaveApp
import app.encave.ui.theme.EncaveTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** A link to open (reminder, app shortcut): encave://wine/<id>, encave://tonight or encave://scan. */
    private val links = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) links.value = intent?.dataString
        val app = application as EncaveApplication
        setContent {
            val viewModel: AppViewModel = viewModel(factory = AppViewModel.factory(app))
            val dynamicColor by app.preferences.dynamicColor.collectAsStateWithLifecycle()
            EncaveTheme(dynamicColor = dynamicColor) {
                EncaveApp(viewModel, links)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { links.value = it }
    }

    /** Devices sync while the app is open: on start (and back from the background). */
    override fun onStart() {
        super.onStart()
        val sync = (application as EncaveApplication).sync
        sync.resume()
        sync.syncRemotely()
    }

    override fun onStop() {
        (application as EncaveApplication).sync.pause()
        super.onStop()
    }

    companion object {
        const val LINK_TONIGHT = "encave://tonight"
        const val LINK_SCAN = "encave://scan"
        private const val WINE_PREFIX = "encave://wine/"

        fun wineLink(id: String) = WINE_PREFIX + id

        /** The wine id of a wine link, or null. */
        fun wineId(link: String): String? = link.takeIf { it.startsWith(WINE_PREFIX) }?.removePrefix(WINE_PREFIX)?.substringBefore('?')
    }
}
