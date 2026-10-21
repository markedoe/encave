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
    /**
     * A link to open (reminder, notification, app shortcut, widget): encave://wine/<id>,
     * encave://cellar, encave://tonight, encave://journal, encave://scan or encave://add.
     */
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

    /** Devices sync while the app is open: on start (and back from the background); the weekly backup is made then if due. */
    override fun onStart() {
        super.onStart()
        val app = application as EncaveApplication
        app.sync.resume()
        app.sync.syncRemotely()
        app.backUpIfDue()
    }

    override fun onStop() {
        (application as EncaveApplication).sync.pause()
        super.onStop()
    }

    companion object {
        const val LINK_CELLAR = "encave://cellar"
        const val LINK_TONIGHT = "encave://tonight"
        const val LINK_JOURNAL = "encave://journal"
        const val LINK_SCAN = "encave://scan"
        const val LINK_ADD = "encave://add"
        private const val WINE_PREFIX = "encave://wine/"

        fun wineLink(id: String) = WINE_PREFIX + id

        /** The wine id of a wine link, or null. */
        fun wineId(link: String): String? = link.takeIf { it.startsWith(WINE_PREFIX) }?.removePrefix(WINE_PREFIX)?.substringBefore('?')
    }
}
