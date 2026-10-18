package app.encave.ui.add

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.R
import app.encave.data.PhotoStore
import app.encave.ui.AppViewModel
import app.encave.ui.UiText
import app.encave.ui.theme.Dimens
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Loads a stored photo (or prepared JPEG bytes) off the main thread. */
@Composable
fun WinePhoto(
    photos: PhotoStore,
    photoId: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    bytes: ByteArray? = null,
    height: Dp = 200.dp,
) {
    val bitmap by produceState<Bitmap?>(null, photoId, bytes) {
        value = withContext(Dispatchers.IO) {
            if (bytes != null) PhotoStore.decode(bytes, 900) else photoId?.let { photos.load(it) }
        }
    }
    bitmap?.let {
        Image(
            it.asImageBitmap(), contentDescription = contentDescription, contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(Dimens.radiusCard)),
        )
    }
}

/**
 * Label photo: take or choose one, resized on the device. With AI label recognition
 * on, a new photo is read and fills the empty fields of the form.
 */
@Composable
fun PhotoSection(viewModel: AppViewModel, form: WineFormState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ai by viewModel.ai.settings.collectAsStateWithLifecycle()
    var reading by remember { mutableStateOf(false) }
    var filled by rememberSaveable { mutableStateOf(false) }
    // Created when needed: the camera app writes the photo to this file in our cache.
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    fun cameraTarget(): Uri? = cameraUri ?: runCatching {
        val file = File(context.cacheDir, "camera/label.jpg").apply { parentFile?.mkdirs() }
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }.getOrNull().also { cameraUri = it }

    fun analyze(jpeg: ByteArray) {
        reading = true
        scope.launch {
            viewModel.analyzeLabel(jpeg)
                .onSuccess { form.apply(it); filled = true }
                .onFailure { viewModel.showMessage(viewModel.aiErrorText(it)) }
            reading = false
        }
    }

    fun use(uri: Uri) {
        scope.launch {
            val jpeg = viewModel.preparePhoto(uri)
            if (jpeg == null) {
                viewModel.showMessage(UiText.Res(R.string.photo_failed))
                return@launch
            }
            form.newPhoto = jpeg
            form.photoRemoved = false
            if (ai.isEnabled) analyze(jpeg)
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::use) }
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraUri?.let(::use) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
        if (form.hasPhoto) {
            WinePhoto(
                viewModel.photos, form.storedPhotoId.takeIf { !form.photoRemoved }, contentDescription = stringResource(R.string.photo_section),
                bytes = form.newPhoto,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
            OutlinedButton(onClick = {
                val target = cameraTarget()
                if (target != null) runCatching { take.launch(target) }.onFailure { viewModel.showMessage(UiText.Res(R.string.photo_failed)) }
            }) {
                app.encave.ui.components.ActionContent(
                    stringResource(if (form.hasPhoto) R.string.photo_replace else R.string.photo_take), Icons.Outlined.PhotoCamera,
                )
            }
            OutlinedButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                app.encave.ui.components.ActionContent(stringResource(R.string.photo_choose), Icons.Outlined.PhotoLibrary)
            }
            if (form.hasPhoto) {
                TextButton(onClick = { form.newPhoto = null; form.photoRemoved = true; filled = false }) {
                    app.encave.ui.components.ActionContent(stringResource(R.string.photo_remove), Icons.Outlined.Delete)
                }
            }
            val jpeg = form.newPhoto
            if (ai.isEnabled && form.hasPhoto && !reading) {
                FilledTonalButton(onClick = {
                    scope.launch {
                        val bytes = jpeg ?: form.storedPhotoId?.let { id ->
                            withContext(Dispatchers.IO) { viewModel.photos.data(id) }
                        }
                        bytes?.let(::analyze)
                    }
                }) {
                    app.encave.ui.components.ActionContent(stringResource(R.string.ai_complete), Icons.Outlined.AutoAwesome)
                }
            }
        }
        if (reading) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(stringResource(R.string.ai_reading), style = MaterialTheme.typography.bodyMedium)
            }
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (filled) {
            Text(
                stringResource(R.string.ai_filled), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        } else if (!ai.isEnabled && !form.hasPhoto) {
            Text(stringResource(R.string.photo_hint_ai), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

