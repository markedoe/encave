package app.encave.ui.cellar

import android.content.ActivityNotFoundException
import android.content.Intent
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FilePresent
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.R
import app.encave.core.Attachment
import app.encave.core.AttachmentTarget
import app.encave.data.PendingDocument
import app.encave.ui.AppViewModel
import app.encave.ui.UiText
import app.encave.ui.components.ActionContent
import app.encave.ui.components.SectionHeader
import app.encave.ui.components.displayLocation
import app.encave.ui.theme.Dimens
import java.util.UUID
import kotlinx.coroutines.launch

// Documents of a wine (for all its bottles) or of one bottle: invoices, certificates,
// technical sheets, articles, photos of receipts. Stored on the device (DocumentStore) and
// synced like the photos, encrypted. Never sent to the AI service or the web search.

/** What "Add a document…" offers: PDF, images, text and office documents. */
private val documentTypes = arrayOf(
    "application/pdf", "image/*", "text/*", "application/rtf",
    "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.spreadsheet",
    "application/vnd.oasis.opendocument.presentation",
)

/** What a document is, for its icon and for TalkBack. */
enum class DocumentKind(@StringRes val title: Int, val icon: ImageVector) {
    Pdf(R.string.document_kind_pdf, Icons.Outlined.PictureAsPdf),
    Picture(R.string.document_kind_image, Icons.Outlined.Image),
    Text(R.string.document_kind_text, Icons.Outlined.Description),
    Spreadsheet(R.string.document_kind_spreadsheet, Icons.Outlined.TableChart),
    Presentation(R.string.document_kind_presentation, Icons.Outlined.Slideshow),
    Other(R.string.document_kind_other, Icons.Outlined.FilePresent);

    companion object {
        fun of(contentType: String): DocumentKind {
            val type = contentType.lowercase()
            return when {
                type == "application/pdf" -> Pdf
                type.startsWith("image/") -> Picture
                "spreadsheet" in type || "excel" in type || type == "text/csv" -> Spreadsheet
                "presentation" in type || "powerpoint" in type -> Presentation
                type.startsWith("text/") || "word" in type || "opendocument.text" in type || type == "application/rtf" -> Text
                else -> Other
            }
        }
    }
}

/** Picking files and photographing papers, then [onStaged] with the copies (size checked). */
class DocumentAdder(val pick: () -> Unit, val photograph: () -> Unit, val busy: Boolean)

@Composable
fun rememberDocumentAdder(viewModel: AppViewModel, onStaged: (List<PendingDocument>) -> Unit): DocumentAdder {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val deliver by rememberUpdatedState(onStaged)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            busy = true
            scope.launch {
                val staged = viewModel.stageDocuments(uris)
                busy = false
                if (staged.isNotEmpty()) deliver(staged)
            }
        }
    }
    // Asks for the camera first: see rememberCameraCapture.
    val photograph = app.encave.ui.components.rememberCameraCapture(viewModel, "paper.jpg") { uri ->
        busy = true
        val staged = try {
            viewModel.stagePhotoOfPaper(uri)
        } finally {
            busy = false
        }
        if (staged != null) deliver(listOf(staged))
    }
    return DocumentAdder(
        pick = { runCatching { picker.launch(documentTypes) }.onFailure { viewModel.showMessage(UiText.Res(R.string.err_document_unreadable)) } },
        photograph = photograph,
        busy = busy,
    )
}

/** "Add a document…" and "Photograph a paper". */
@Composable
fun AddDocumentButtons(adder: DocumentAdder, place: String, modifier: Modifier = Modifier) {
    // The action first, then what can be added: "Add a document…, A PDF, an image…".
    val description = stringResource(R.string.action_add_document) + ", " + stringResource(R.string.action_add_document_hint)
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(Dimens.s), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
        OutlinedButton(
            onClick = adder.pick, enabled = !adder.busy,
            modifier = Modifier.testTag("action.add_document.$place")
                .then(if (adder.busy) Modifier else Modifier.semantics { contentDescription = description }),
        ) {
            if (adder.busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.document_adding))
                }
            } else {
                ActionContent(stringResource(R.string.action_add_document), Icons.Outlined.UploadFile)
            }
        }
        OutlinedButton(onClick = adder.photograph, enabled = !adder.busy, modifier = Modifier.testTag("action.photo_document.$place")) {
            ActionContent(stringResource(R.string.action_photo_document), Icons.Outlined.PhotoCamera)
        }
    }
}

/** Opens or shares a document through another app, with a copy that has its name. */
@Composable
private fun rememberDocumentOpener(viewModel: AppViewModel): (Attachment, Boolean) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chooserTitle = stringResource(R.string.action_share)
    return remember<(Attachment, Boolean) -> Unit>(viewModel, context, chooserTitle) {
        { attachment, share ->
            scope.launch {
                if (!viewModel.documents.exists(attachment.id)) {
                    viewModel.showMessage(UiText.Res(R.string.err_document_missing))
                    return@launch
                }
                // Here, but the copy for the other app could not be made (a full disk…).
                val uri = viewModel.documentUri(attachment)
                if (uri == null) {
                    viewModel.showMessage(UiText.Res(R.string.err_document_unreadable))
                    return@launch
                }
                val intent = if (share) {
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType(attachment.contentType).putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        chooserTitle,
                    )
                } else {
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, attachment.contentType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    viewModel.showMessage(UiText.Res(R.string.err_document_no_app))
                }
            }
        }
    }
}

/** "240 KB", in the device's language. */
@Composable
private fun fileSize(byteCount: Long): String = Formatter.formatShortFileSize(LocalContext.current, byteCount)

/** "PDF · 240 KB", or "… · Not on this device yet". */
@Composable
private fun documentDetails(kind: DocumentKind, byteCount: Long, available: Boolean = true): String =
    listOfNotNull(stringResource(kind.title), fileSize(byteCount), if (available) null else stringResource(R.string.document_not_here))
        .joinToString(" · ")

/** A document: touch to open it; Share, Rename and Delete in its menu. */
@Composable
fun DocumentRow(viewModel: AppViewModel, document: Attachment, target: AttachmentTarget, open: (Attachment, Boolean) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val kind = DocumentKind.of(document.contentType)
    // Checked again when a file arrives (files come after the cellar when syncing).
    val fileRevision by viewModel.documents.revision.collectAsStateWithLifecycle()
    val available = remember(document.id, fileRevision) { viewModel.documents.exists(document.id) }
    val notHere = stringResource(R.string.document_not_here)
    val name = document.name.ifEmpty { stringResource(R.string.document_default_name) }
    val details = documentDetails(kind, document.byteCount, available)
    // "PDF, Invoice.pdf, 240 KB".
    val description = listOf(stringResource(kind.title), name, fileSize(document.byteCount)).joinToString(", ")
    val openLabel = stringResource(R.string.document_open_hint)
    val renameLabel = stringResource(R.string.action_rename)
    val deleteLabel = stringResource(R.string.action_delete)
    val shareLabel = stringResource(R.string.action_share)
    ListItem(
        modifier = Modifier
            .clickable(onClickLabel = openLabel) { open(document, false) }
            .semantics {
                contentDescription = description
                if (!available) stateDescription = notHere
                customActions = listOfNotNull(
                    if (available) CustomAccessibilityAction(shareLabel) { open(document, true); true } else null,
                    CustomAccessibilityAction(renameLabel) { renaming = true; true },
                    CustomAccessibilityAction(deleteLabel) { viewModel.removeDocument(target, document.id); true },
                )
            }
            .testTag("document.row"),
        leadingContent = { Icon(kind.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(name, maxLines = 2) },
        supportingContent = { Text(details) },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (available) {
                        DropdownMenuItem(
                            text = { Text(shareLabel) },
                            leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                            onClick = { menu = false; open(document, true) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(renameLabel) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = { menu = false; renaming = true },
                    )
                    DropdownMenuItem(
                        text = { Text(deleteLabel, color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; viewModel.removeDocument(target, document.id) },
                    )
                }
            }
        },
    )
    if (renaming) {
        RenameDocumentDialog(document.name, onRename = { viewModel.renameDocument(target, document.id, it) }, onDismiss = { renaming = false })
    }
}

@Composable
private fun RenameDocumentDialog(initial: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        // Full width with margins: a text field in a default-width dialog never settles in tests.
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = Dimens.xl).widthIn(max = 560.dp),
        title = { Text(stringResource(R.string.document_rename_title)) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.field_name)) },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("document.name"),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(name); onDismiss() }, enabled = Attachment.normalizedName(name).isNotEmpty()) {
                Text(stringResource(R.string.action_rename_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** The documents of a wine, on its page. */
@Composable
fun WineDocumentsSection(viewModel: AppViewModel, wine: app.encave.core.Wine) {
    val target = AttachmentTarget.OfWine(wine.id)
    val adder = rememberDocumentAdder(viewModel) { viewModel.attachDocuments(target, it) }
    val open = rememberDocumentOpener(viewModel)
    Column(Modifier.testTag("wine.documents")) {
        SectionHeader(stringResource(R.string.documents_title))
        wine.attachments.orEmpty().forEach { document -> DocumentRow(viewModel, document, target, open) }
        Column(Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s), verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
            AddDocumentButtons(adder, "wine")
            Text(
                stringResource(R.string.documents_footer_wine),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Documents picked while adding bottles, attached to their wine when they are added. */
@Composable
fun PendingDocumentsSection(viewModel: AppViewModel, pending: List<PendingDocument>, onChange: (List<PendingDocument>) -> Unit) {
    val current by rememberUpdatedState(pending)
    val adder = rememberDocumentAdder(viewModel) { onChange(current + it) }
    val removeLabel = stringResource(R.string.action_remove)
    Column(Modifier.testTag("add.documents"), verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
        Text(
            stringResource(R.string.documents_title), style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() },
        )
        pending.forEach { document ->
            val kind = DocumentKind.of(document.contentType)
            val name = document.name.ifEmpty { stringResource(R.string.document_default_name) }
            val details = documentDetails(kind, document.byteCount)
            val description = listOf(stringResource(kind.title), name, fileSize(document.byteCount)).joinToString(", ")
            val remove = {
                viewModel.discardDocuments(listOf(document))
                onChange(current.filter { it.id != document.id })
            }
            ListItem(
                modifier = Modifier.semantics {
                    contentDescription = description
                    customActions = listOf(CustomAccessibilityAction(removeLabel) { remove(); true })
                },
                leadingContent = { Icon(kind.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = { Text(name, maxLines = 2) },
                supportingContent = { Text(details) },
                trailingContent = {
                    IconButton(onClick = remove) { Icon(Icons.Outlined.RemoveCircleOutline, contentDescription = removeLabel) }
                },
            )
        }
        AddDocumentButtons(adder, "pending")
        Text(
            stringResource(R.string.documents_footer_pending),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A paperclip and the number of documents of a bottle. */
@Composable
fun DocumentCount(count: Int, modifier: Modifier = Modifier) {
    val description = pluralStringResource(R.plurals.documents_count, count, count)
    Row(
        modifier.clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(Icons.Outlined.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Documents…" (when it has some) and "Attach a document…" in a bottle's menu. */
@Composable
fun BottleDocumentMenuItems(hasDocuments: Boolean, onOpen: (importing: Boolean) -> Unit, closeMenu: () -> Unit) {
    if (hasDocuments) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_documents)) },
            leadingIcon = { Icon(Icons.Outlined.AttachFile, contentDescription = null) },
            onClick = { closeMenu(); onOpen(false) },
        )
    }
    DropdownMenuItem(
        text = { Text(stringResource(R.string.action_attach_document)) },
        leadingIcon = { Icon(Icons.Outlined.UploadFile, contentDescription = null) },
        onClick = { closeMenu(); onOpen(true) },
        modifier = Modifier.testTag("action.attach_document"),
    )
}

/** The documents of one bottle, in a sheet. [startPicking] opens the file picker at once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottleDocumentsSheet(viewModel: AppViewModel, bottleId: UUID, startPicking: Boolean, onDismiss: () -> Unit) {
    val cellar by viewModel.cellar.collectAsStateWithLifecycle()
    val bottle = cellar.bottles[bottleId]
    val target = AttachmentTarget.OfBottle(bottleId)
    val adder = rememberDocumentAdder(viewModel) { viewModel.attachDocuments(target, it) }
    val open = rememberDocumentOpener(viewModel)
    var picked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(startPicking) {
        if (startPicking && !picked) {
            picked = true
            adder.pick()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().testTag("bottle.documents")) {
            Text(
                stringResource(R.string.documents_title), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = Dimens.l).semantics { heading() },
            )
            if (bottle == null) {
                Text(stringResource(R.string.documents_bottle_missing), Modifier.padding(Dimens.l))
            } else {
                val wine = cellar.wines[bottle.wineId]
                Text(
                    listOfNotNull(wine?.displayName, cellar.displayLocation(bottle)).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.xs),
                )
                bottle.attachments.orEmpty().forEach { document -> DocumentRow(viewModel, document, target, open) }
                Column(Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s), verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                    AddDocumentButtons(adder, "bottle")
                    Text(
                        stringResource(R.string.documents_footer_bottle),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
