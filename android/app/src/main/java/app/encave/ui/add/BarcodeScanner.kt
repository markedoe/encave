package app.encave.ui.add

import app.encave.ui.components.openAppSettings
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.encave.R
import app.encave.core.Wine
import app.encave.ui.devices.CameraCodeReader
import app.encave.ui.theme.Dimens
import zxingcpp.BarcodeReader

/** Shortest code taken as a bottle barcode (EAN-8). */
private const val MIN_DIGITS = 8

/**
 * Full-screen camera reading the barcode on a bottle (EAN, UPC) on the device. Calls
 * [onScan] with its digits. Without the camera, the digits can be typed.
 */
@Composable
fun BarcodeScannerDialog(onScan: (String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            // Without the camera, the hint to point it is not shown.
            var cameraDenied by remember { mutableStateOf(false) }
            CameraCodeReader(
                formats = setOf(BarcodeReader.Format.EAN_13, BarcodeReader.Format.EAN_8, BarcodeReader.Format.UPC_A, BarcodeReader.Format.UPC_E),
                accept = { Wine.normalizedBarcode(it).length >= MIN_DIGITS },
                onScan = { onScan(Wine.normalizedBarcode(it)) },
                modifier = Modifier.fillMaxSize(),
                deniedWhileAsking = true,
                denied = {
                    DisposableEffect(Unit) {
                        cameraDenied = true
                        onDispose { cameraDenied = false }
                    }
                    TypedBarcode(onScan)
                },
            )
            Row(
                Modifier.fillMaxWidth().safeDrawingPadding().padding(Dimens.l),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.s),
            ) {
                Box(Modifier.weight(1f)) {
                    if (!cameraDenied) {
                        // A pill, not a band across a tablet's whole width.
                        Text(
                            stringResource(R.string.barcode_hint),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.widthIn(max = 480.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(50))
                                .padding(horizontal = Dimens.l, vertical = Dimens.s)
                                .testTag("barcode.hint"),
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(50)).testTag("sheet.cancel"),
                ) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel)) }
            }
        }
    }
}

/** Without the camera: type the digits under the barcode. */
@Composable
private fun TypedBarcode(onScan: (String) -> Unit) {
    var digits by remember { mutableStateOf("") }
    val valid = digits.length >= MIN_DIGITS
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
        Text(
            stringResource(R.string.barcode_no_camera), textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = digits,
            onValueChange = { text -> digits = Wine.normalizedBarcode(text) },
            label = { Text(stringResource(R.string.barcode_digits)) },
            singleLine = true,
            modifier = Modifier.testTag("barcode.field"),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (valid) onScan(digits) }),
        )
        Button(onClick = { onScan(digits) }, enabled = valid, modifier = Modifier.testTag("barcode.use")) {
            Text(stringResource(R.string.action_ok))
        }
        // Where the camera can be allowed again.
        val context = LocalContext.current
        TextButton(onClick = { openAppSettings(context) }, modifier = Modifier.testTag("barcode.open_settings")) {
            Text(stringResource(R.string.action_open_app_settings))
        }
    }
}
