package app.encave.ui.cellar

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.encave.R
import app.encave.core.Wine
import app.encave.data.PhotoStore
import app.encave.ui.AppViewModel
import app.encave.ui.components.ActionContent
import app.encave.ui.components.SectionHeader
import app.encave.ui.theme.Dimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * More photos of a wine (back label, receipt, the bottle in its rack), beside the label
 * photo: a strip of thumbnails; tap one to see it large, touch and hold to remove it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MorePhotosSection(viewModel: AppViewModel, wine: Wine) {
    val photos = wine.extraPhotoIds.orEmpty().filter { viewModel.photos.exists(it) }
    var adding by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            adding = true
            viewModel.addExtraPhoto(wine.id, uri) { adding = false }
        }
    }
    val removeLabel = stringResource(R.string.action_remove_photo)
    val largeLabel = stringResource(R.string.photo_show_large)
    Column(Modifier.testTag("wine.more_photos")) {
        SectionHeader(stringResource(R.string.more_photos))
        if (photos.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = Dimens.l, vertical = Dimens.xs),
                horizontalArrangement = Arrangement.spacedBy(Dimens.m),
            ) {
                itemsIndexed(photos, key = { _, id -> id }) { index, id ->
                    var menu by remember { mutableStateOf(false) }
                    val description = stringResource(R.string.photo_item, index + 1, photos.size)
                    Box {
                        Thumbnail(
                            viewModel.photos, id,
                            Modifier.size(96.dp, 128.dp).clip(RoundedCornerShape(Dimens.radiusCell))
                                .combinedClickable(onClickLabel = largeLabel, onLongClick = { menu = true }, onClick = { shown = id })
                                .semantics {
                                    customActions = listOf(CustomAccessibilityAction(removeLabel) { viewModel.removeExtraPhoto(wine.id, id); true })
                                }
                                .testTag("photo.thumb"),
                            description,
                        )
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(removeLabel, color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; viewModel.removeExtraPhoto(wine.id, id) },
                            )
                        }
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s), verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
            OutlinedButton(
                onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !adding,
                modifier = Modifier.testTag("action.add_photo"),
            ) {
                if (adding) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.photo_adding))
                    }
                } else {
                    ActionContent(stringResource(R.string.action_add_photo), Icons.Outlined.AddPhotoAlternate)
                }
            }
            Text(
                stringResource(R.string.more_photos_footer),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    shown?.let { id ->
        LargePhoto(
            viewModel.photos, id,
            onRemove = { viewModel.removeExtraPhoto(wine.id, id); shown = null },
            onDismiss = { shown = null },
        )
    }
}

@Composable
private fun Thumbnail(photos: PhotoStore, id: String, modifier: Modifier, description: String) {
    val bitmap by produceState<Bitmap?>(null, id) { value = withContext(Dispatchers.IO) { photos.load(id, 400) } }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = description, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** The photo on its own, as large as the screen allows. */
@Composable
private fun LargePhoto(photos: PhotoStore, id: String, onRemove: () -> Unit, onDismiss: () -> Unit) {
    val bitmap by produceState<Bitmap?>(null, id) { value = withContext(Dispatchers.IO) { photos.load(id, 1600) } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).safeDrawingPadding()) {
            bitmap?.let {
                Image(
                    it.asImageBitmap(), contentDescription = stringResource(R.string.photo_section),
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(vertical = 64.dp),
                )
            }
            Row(Modifier.align(Alignment.TopEnd).padding(Dimens.s)) {
                TextButton(onClick = onRemove) { Text(stringResource(R.string.action_remove_photo), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("photo.done")) { Text(stringResource(R.string.action_done)) }
            }
        }
    }
}
