package app.encave.ui.search

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.R
import app.encave.core.Wine
import app.encave.core.search.WebImageResult
import app.encave.core.search.WebSearchQuery
import app.encave.core.search.pictureTerms
import app.encave.data.PhotoStore
import app.encave.data.search.WebSearchClient
import app.encave.ui.AppViewModel
import app.encave.ui.components.rememberForgetRetained
import app.encave.ui.components.rememberRetained
import app.encave.ui.theme.Dimens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The pictures a picture sheet found, and its search or download, kept while the activity is recreated. */
private class PictureSession : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var searching by mutableStateOf(false)
    var searched by mutableStateOf(false)
    var error by mutableStateOf<Throwable?>(null)
    var downloadFailed by mutableStateOf(false)
    var images by mutableStateOf<List<WebImageResult>>(emptyList())
    var downloading by mutableStateOf<String?>(null)

    override fun close() = scope.cancel()
}

/**
 * "Change picture": the label photo, or a picture of the bottle found on the internet
 * with the web search service. The search terms are shown and editable and sent only
 * on Search pictures; the chosen picture is downloaded from its host, saved as a resized
 * JPEG and shown for the wine. The label photo is always kept.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WinePictureSheet(viewModel: AppViewModel, wine: Wine, onDismiss: () -> Unit) {
    val preferences = webSearchPreferences()
    val settings by preferences.settings.collectAsStateWithLifecycle()
    // Kept when the activity is recreated (rotation, folding, dark mode): the terms in saved
    // state, the pictures found and a search or download under way in the session.
    val session = rememberRetained("webSearch.picture") { PictureSession() }
    val forget = rememberForgetRetained()
    val activity = LocalActivity.current
    DisposableEffect(session) { onDispose { if (activity?.isChangingConfigurations != true) forget("webSearch.picture") } }
    val scope = session.scope
    val dismiss by rememberUpdatedState(onDismiss)
    val bottleWord = stringResource(R.string.search_bottle_word)
    var query by rememberSaveable { mutableStateOf(WebSearchQuery.pictureTerms(wine, bottleWord)) }
    var searching by session::searching
    var searched by session::searched
    var error by session::error
    var downloadFailed by session::downloadFailed
    var images by session::images
    var downloading by session::downloading

    fun search() {
        val terms = query.trim()
        if (terms.isEmpty() || searching || !settings.isEnabled) return
        searching = true
        error = null
        downloadFailed = false
        val current = settings
        scope.launch {
            val key = withContext(Dispatchers.IO) { preferences.apiKey(current.service) }
            runCatching { WebSearchClient.searchImages(terms, current, key) }
                .onSuccess { images = it }
                .onFailure {
                    images = emptyList()
                    error = it
                }
            searched = true
            searching = false
        }
    }

    fun pick(image: WebImageResult) {
        if (downloading != null) return
        downloading = image.id
        downloadFailed = false
        scope.launch {
            val jpeg = runCatching { WebSearchClient.downloadImage(image.imageUrl) }.getOrNull()
                ?.let { bytes -> withContext(Dispatchers.Default) { viewModel.photos.prepare(bytes) } }
            downloading = null
            if (jpeg == null) {
                downloadFailed = true
            } else {
                viewModel.setCover(wine.id, jpeg)
                dismiss()
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            modifier = Modifier.fillMaxWidth().testTag("picture.sheet"),
            horizontalArrangement = Arrangement.spacedBy(Dimens.s),
            verticalArrangement = Arrangement.spacedBy(Dimens.s),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Dimens.l),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
                    Text(
                        stringResource(R.string.picture_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (wine.coverPhotoId != null && viewModel.photos.exists(wine.photoId)) {
                        OutlinedButton(onClick = { viewModel.setCover(wine.id, null); onDismiss() }) {
                            Icon(Icons.Outlined.Image, contentDescription = null)
                            Text(stringResource(R.string.picture_use_label), Modifier.padding(start = Dimens.s))
                        }
                    }
                    if (!settings.isEnabled) {
                        Text(stringResource(R.string.picture_needs_search), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(
                            stringResource(R.string.picture_from_internet),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.semantics { heading() },
                        )
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(R.string.search_terms)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("picture.query"),
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { search() }),
                        )
                        Button(onClick = ::search, enabled = !searching && query.isNotBlank(), modifier = Modifier.testTag("picture.search")) {
                            if (searching) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.search_searching), Modifier.padding(start = Dimens.s))
                            } else {
                                Icon(Icons.Outlined.ImageSearch, contentDescription = null)
                                Text(stringResource(R.string.picture_search), Modifier.padding(start = Dimens.s))
                            }
                        }
                        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                            val problem = error
                            if (problem != null || downloadFailed) {
                                Box(contentAlignment = Alignment.CenterStart) {
                                    Text(
                                        if (problem != null) webSearchErrorText(problem) else stringResource(R.string.picture_download_failed),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            if (searched && error == null && images.isEmpty() && !searching) {
                                Text(stringResource(R.string.picture_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            items(images, key = { it.id }) { image ->
                PictureTile(image, downloading = downloading == image.id, enabled = downloading == null) { pick(image) }
            }
            if (settings.isEnabled) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(R.string.picture_footer, settings.service.brand.orEmpty()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = Dimens.s),
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(Dimens.xxl)) }
        }
    }
}

/** A thumbnail and its site. Tap to use the picture. */
@Composable
private fun PictureTile(image: WebImageResult, downloading: Boolean, enabled: Boolean, onPick: () -> Unit) {
    val label = if (image.title.isEmpty()) image.domain else "${image.title}, ${image.domain}"
    val use = stringResource(R.string.picture_use)
    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClickLabel = use, role = Role.Button, onClick = onPick)
            .semantics(mergeDescendants = true) {}
            .testTag("picture.tile"),
        verticalArrangement = Arrangement.spacedBy(Dimens.xs),
    ) {
        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
            RemoteThumbnail(image.thumbnailUrl, label)
            if (downloading) {
                Surface(shape = RoundedCornerShape(50), tonalElevation = 3.dp) {
                    CircularProgressIndicator(Modifier.padding(Dimens.s).size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
        Text(
            image.domain, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Downloads and shows a thumbnail (https, an image, at most 2 MB). */
@Composable
private fun RemoteThumbnail(url: String, description: String) {
    val state by produceState<Pair<Boolean, Bitmap?>>(false to null, url) {
        val bitmap = runCatching { WebSearchClient.downloadImage(url, maxBytes = 2 * 1024 * 1024) }.getOrNull()
            ?.let { bytes -> withContext(Dispatchers.Default) { PhotoStore.decode(bytes, 320) } }
        value = true to bitmap
    }
    val (loaded, bitmap) = state
    when {
        bitmap != null -> Image(
            bitmap.asImageBitmap(), contentDescription = description, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(8.dp)),
        )
        loaded -> Icon(Icons.Outlined.BrokenImage, contentDescription = description, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}
