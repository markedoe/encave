package app.encave.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.encave.R
import app.encave.ui.AppViewModel
import app.encave.ui.UiText
import java.io.File

/**
 * Takes a photo with the camera app into [fileName] in the cache, then calls [onPhoto].
 * Encave declares the camera permission (for the barcode and QR scanners), and Android
 * then lets the camera app take a photo for it only once the camera is allowed: it is
 * asked for first. A refusal says so, with a way to the app's settings.
 */
@Composable
fun rememberCameraCapture(viewModel: AppViewModel, fileName: String, onPhoto: (Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    val deliver by rememberUpdatedState(onPhoto)
    // Created when needed: the camera app writes the photo to this file in our cache.
    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraUri?.let(deliver) }

    fun launch() {
        val target = cameraUri ?: runCatching {
            val file = File(context.cacheDir, "camera/$fileName").apply { parentFile?.mkdirs() }
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        }.getOrNull()?.also { cameraUri = it }
        if (target == null) {
            viewModel.showMessage(UiText.Res(R.string.photo_failed))
            return
        }
        try {
            take.launch(target)
        } catch (_: SecurityException) {
            showCameraDenied(viewModel, context)
        } catch (_: Exception) {
            // No camera app (some tablets, managed devices).
            viewModel.showMessage(UiText.Res(R.string.camera_unavailable))
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launch() else showCameraDenied(viewModel, context)
    }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launch()
        else permission.launch(Manifest.permission.CAMERA)
    }
}

private fun showCameraDenied(viewModel: AppViewModel, context: Context) {
    val app = context.applicationContext
    viewModel.showMessage(UiText.Res(R.string.camera_denied), UiText.Res(R.string.action_open_app_settings)) { openAppSettings(app) }
}

/** Encave's page in the system settings, where the camera (or another permission) can be allowed. */
fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
