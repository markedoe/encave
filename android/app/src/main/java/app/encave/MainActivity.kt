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
import app.encave.ui.components.CameraFiles
import app.encave.ui.theme.EncaveTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /**
     * A link to open (reminder, notification, app shortcut, widget): encave://wine/<id>,
     * encave://cellar, encave://tonight, encave://journal, encave://scan, encave://add or a
     * pairing link, encave://pair?code=…
     */
    private val links = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            links.value = intent?.dataString
            // Camera photos an earlier launch did not get to delete. Not when the activity comes
            // back after the system closed it: the camera app may be about to deliver one.
            CameraFiles.clear(this)
        }
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

        /**
         * The code and commitment (Base64, no wrap) of a pairing link, encave://pair?code=…&c=…,
         * as put in the QR code shown when adding a device; null for any other link.
         */
        fun pairing(link: String): Pair<String, String?>? {
            if (!link.startsWith("encave://pair")) return null
            val (code, commitment) = app.encave.core.sync.PairingCode.parse(link) ?: return null
            return code.digits to commitment?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
        }

        /** The wine id of a wine link, or null. */
        fun wineId(link: String): String? = link.takeIf { it.startsWith(WINE_PREFIX) }?.removePrefix(WINE_PREFIX)?.substringBefore('?')
    }
}
