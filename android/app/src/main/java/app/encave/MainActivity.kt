package app.encave

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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as EncaveApplication
        setContent {
            val viewModel: AppViewModel = viewModel(factory = AppViewModel.factory(app))
            val dynamicColor by app.preferences.dynamicColor.collectAsStateWithLifecycle()
            EncaveTheme(dynamicColor = dynamicColor) {
                EncaveApp(viewModel)
            }
        }
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
}
