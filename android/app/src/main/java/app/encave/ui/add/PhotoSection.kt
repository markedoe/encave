package app.encave.ui.add

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.R
import app.encave.data.PhotoStore
import app.encave.data.ai.AiConfiguration
import app.encave.data.ai.AiModelChoice
import app.encave.ui.title
import app.encave.ui.AppViewModel
import app.encave.ui.LabelReading
import app.encave.ui.components.rememberCameraCapture
import app.encave.ui.components.rememberRetained
import app.encave.ui.UiText
import app.encave.ui.components.Formats
import app.encave.ui.theme.Dimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

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
 * on, a new photo is read and fills the empty fields of the form. The reading runs in the
 * view model, so it goes on when the device is rotated or folded, and completes the wine
 * if the bottles are added before it ends. [onScanBarcode]: also offers the barcode scanner.
 */
@Composable
fun PhotoSection(
    viewModel: AppViewModel,
    form: WineFormState,
    modifier: Modifier = Modifier,
    onScanBarcode: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val aiConfiguration by viewModel.ai.configuration.collectAsStateWithLifecycle()
    val aiEnabled = aiConfiguration.isEnabled
    val readings by viewModel.labelReadings.collectAsStateWithLifecycle()
    val reading = readings[form.id]
    var filled by rememberSaveable { mutableStateOf(false) }
    // The model that read the current photo, by its id in the list.
    var readById by rememberSaveable { mutableStateOf<String?>(null) }
    // Leaving the form stops its reading, unless the wine was just added (the answer completes it).
    rememberRetained("photo.reading") { LabelReadingRelease { viewModel.releaseLabelReading(form.id) } }
    LaunchedEffect(reading) {
        when (reading) {
            is LabelReading.Done -> {
                readById = reading.model?.id
                if (form.apply(reading.analysis)) {
                    filled = true
                } else {
                    viewModel.showMessage(UiText.Res(R.string.msg_ai_nothing))
                }
                viewModel.consumeLabelReading(form.id)
            }
            is LabelReading.Failed -> {
                viewModel.showMessage(viewModel.aiErrorText(reading.error))
                viewModel.consumeLabelReading(form.id)
            }
            else -> {}
        }
    }

    /** [only]: "Read again with another model"; its answer replaces what the previous reading filled. */
    fun analyze(jpeg: ByteArray, only: String? = null) {
        filled = false
        viewModel.startLabelAnalysis(form.id, jpeg, only)
    }

    /** The photo of the form: the new one, or the one kept with the wine. */
    fun withPhoto(block: (ByteArray) -> Unit) {
        val jpeg = form.newPhoto
        scope.launch {
            val bytes = jpeg ?: form.storedPhotoId?.let { id -> withContext(Dispatchers.IO) { viewModel.photos.data(id) } }
            bytes?.let(block)
        }
    }

    suspend fun prepare(uri: Uri) {
        val jpeg = viewModel.preparePhoto(uri)
        if (jpeg == null) {
            viewModel.showMessage(UiText.Res(R.string.photo_failed))
            return
        }
        form.newPhoto = jpeg
        form.photoRemoved = false
        readById = null
        if (aiEnabled) analyze(jpeg)
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { scope.launch { prepare(it) } } }
    // The camera's original is deleted once prepared.
    val takePhoto = rememberCameraCapture(viewModel, "label.jpg") { prepare(it) }
    val isReading = reading is LabelReading.Reading

    Column(modifier, verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
        if (form.hasPhoto) {
            WinePhoto(
                viewModel.photos, form.storedPhotoId.takeIf { !form.photoRemoved }, contentDescription = stringResource(R.string.photo_section),
                bytes = form.newPhoto,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
            OutlinedButton(onClick = takePhoto, modifier = Modifier.testTag("action.photo_take")) {
                app.encave.ui.components.ActionContent(
                    stringResource(if (form.hasPhoto) R.string.photo_replace else R.string.photo_take), Icons.Outlined.PhotoCamera,
                )
            }
            OutlinedButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                app.encave.ui.components.ActionContent(stringResource(R.string.photo_choose), Icons.Outlined.PhotoLibrary)
            }
            if (onScanBarcode != null) {
                OutlinedButton(onClick = onScanBarcode, modifier = Modifier.testTag("action.scan_barcode.form")) {
                    app.encave.ui.components.ActionContent(stringResource(R.string.action_scan_barcode), Icons.Outlined.QrCodeScanner)
                }
            }
            if (form.hasPhoto) {
                TextButton(onClick = { form.newPhoto = null; form.photoRemoved = true; filled = false; readById = null }) {
                    app.encave.ui.components.ActionContent(stringResource(R.string.photo_remove), Icons.Outlined.Delete)
                }
            }
        }
        // A full-width row of its own, so its title is never squeezed by the photo actions.
        // Once the AI has completed the wine, it reads "Complete again" and says when;
        // while it reads the label, it cannot be tapped twice.
        if (aiEnabled && form.hasPhoto) {
            val completedAt = form.aiCompletedAt
            FilledTonalButton(
                onClick = { withPhoto { analyze(it) } },
                enabled = !isReading,
                modifier = Modifier.fillMaxWidth().testTag("action.ai_complete"),
            ) {
                if (isReading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(Dimens.s))
                    Text(stringResource(R.string.ai_reading), maxLines = 2, overflow = TextOverflow.Ellipsis)
                } else {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Dimens.s))
                    Text(
                        stringResource(if (completedAt == null) R.string.ai_complete else R.string.ai_complete_again),
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (completedAt != null && !isReading) {
                AiCompletedCaption(completedAt)
            }
            val readBy = readById?.let { id -> aiConfiguration.models.firstOrNull { it.id == id } }
            if (readBy != null && !isReading) {
                AiReadAgain(readBy, aiConfiguration) { model -> withPhoto { analyze(it, only = model.id) } }
            }
        }
        if (isReading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
        } else if (filled) {
            Text(
                stringResource(R.string.ai_filled), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        } else if (!aiEnabled) {
            // No reading of the label text on Android itself (no third-party OCR): say so, and what helps.
            // The barcode is mentioned only where it can be scanned.
            Text(stringResource(if (onScanBarcode != null) R.string.photo_hint_ai else R.string.photo_hint_ai_no_barcode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * "Read by model · connection", and "Read again with another model": the same photo sent
 * to the model picked, whose answer replaces what the previous reading filled.
 */
@Composable
private fun AiReadAgain(readBy: AiModelChoice, configuration: AiConfiguration, readAgain: (AiModelChoice) -> Unit) {
    val resources = LocalResources.current
    fun title(model: AiModelChoice) = configuration.connection(model)?.let { "${model.model} · ${it.title(resources)}" } ?: model.model
    Text(
        stringResource(R.string.ai_read_by, title(readBy)), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("label.read_by"),
    )
    val others = configuration.usableModels.filter { it.id != readBy.id }
    if (others.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.testTag("action.ai_read_again")) {
            app.encave.ui.components.ActionContent(stringResource(R.string.ai_read_again), Icons.Outlined.Autorenew)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            others.forEach { model ->
                DropdownMenuItem(text = { Text(title(model)) }, onClick = { open = false; readAgain(model) })
            }
        }
    }
}

/** "Completed by AI · Oct 22": when the AI last completed the wine's details. */
@Composable
fun AiCompletedCaption(completedAt: Instant, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(Dimens.xs))
        Text(
            stringResource(R.string.ai_completed_on, Formats.recentDay(completedAt)),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Closed when the form's screen is left for good (not on rotation). */
private class LabelReadingRelease(private val release: () -> Unit) : AutoCloseable {
    override fun close() = release()
}
