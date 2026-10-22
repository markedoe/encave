package app.encave.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.encave.R
import app.encave.core.Maturity
import app.encave.core.WineColor
import app.encave.ui.theme.Dimens
import app.encave.ui.theme.LocalWinePalette

/** One snackbar host for the whole app; each screen's Scaffold shows it. */
val LocalSnackbarHostState = staticCompositionLocalOf { SnackbarHostState() }

/** Small dot showing the wine style. Decorative: the style is always also given as text. */
@Composable
fun WineColorDot(color: WineColor?, modifier: Modifier = Modifier, size: Dp = 12.dp) {
    if (color != null) {
        BottleEnd(color, modifier, size)
    } else {
        Box(modifier.size(size).border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), CircleShape))
    }
}

/** Style shown as a dot plus its name. */
@Composable
fun StyleLabel(color: WineColor?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Dimens.radiusChip),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.padding(horizontal = Dimens.s, vertical = Dimens.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.s),
        ) {
            WineColorDot(color)
            Text(stringResource(color.labelRes), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Icon, label and tint: never color alone. */
@Composable
fun MaturityLabel(maturity: Maturity, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.xs)) {
        Icon(maturity.icon, contentDescription = null, tint = maturity.tint, modifier = Modifier.size(18.dp))
        Text(stringResource(maturity.labelRes), style = MaterialTheme.typography.labelLarge, color = maturity.tint)
    }
}

/** Read-only stars, read by TalkBack as "Rated 4 out of 5". */
@Composable
fun RatingStars(rating: Int?, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val description = if (rating != null) stringResource(R.string.rating_value, rating) else stringResource(R.string.rating_none)
    Row(modifier.clearAndSetSemantics { contentDescription = description }) {
        for (index in 1..5) {
            Icon(
                if (rating != null && index <= rating) Icons.Filled.Star else Icons.Outlined.StarOutline,
                contentDescription = null,
                tint = if (rating != null && index <= rating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(size),
            )
        }
    }
}

/** Five toggles; tapping the current rating again clears it. Each change ticks, as on Apple. */
@Composable
fun RatingInput(rating: Int?, onChange: (Int?) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    Row(modifier) {
        for (index in 1..5) {
            val selected = rating != null && index <= rating
            IconToggleButton(
                checked = rating == index,
                onCheckedChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onChange(if (rating == index) null else index)
                },
            ) {
                Icon(
                    if (selected) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    contentDescription = stringResource(R.string.rating_star, index),
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    Box(modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp).padding(Dimens.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.m),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Dimens.s))
            actions()
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(start = Dimens.l, end = Dimens.l, top = Dimens.l, bottom = Dimens.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing()
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Icon and label of an action button, tagged "action.content" so tests can check the
 * content is centered. The icon takes the button's content color, never the fill color.
 */
@Composable
fun ActionContent(text: String, icon: ImageVector? = null) {
    Row(Modifier.testTag("action.content"), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Dimens.s))
        }
        Text(text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** A short explanation under a control or at the end of a group of fields. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * − value + with 48dp buttons. Holding a button repeats it (a case of 12 without twelve
 * taps); the value is announced by TalkBack whenever it changes, also from elsewhere.
 */
@Composable
fun Stepper(
    value: Int,
    onChange: (Int) -> Unit,
    range: IntRange,
    decreaseLabel: String,
    increaseLabel: String,
    modifier: Modifier = Modifier,
    valueText: String = value.toString(),
) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.m)) {
        RepeatingIconButton(enabled = value > range.first, onStep = { if (current > range.first) change(current - 1) }) {
            Icon(Icons.Filled.Remove, contentDescription = decreaseLabel)
        }
        Text(
            valueText, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 32.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
        RepeatingIconButton(enabled = value < range.last, onStep = { if (current < range.last) change(current + 1) }) {
            Icon(Icons.Filled.Add, contentDescription = increaseLabel)
        }
    }
}

/** A tonal icon button that repeats [onStep] while held, after a short delay. */
@Composable
private fun RepeatingIconButton(enabled: Boolean, onStep: () -> Unit, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val step by rememberUpdatedState(onStep)
    // Set once holding has repeated: the click that ends the press then adds nothing more.
    var repeated by remember { mutableStateOf(false) }
    LaunchedEffect(pressed, enabled) {
        if (!pressed || !enabled) return@LaunchedEffect
        repeated = false
        delay(STEPPER_REPEAT_DELAY_MS)
        while (true) {
            repeated = true
            step()
            delay(STEPPER_REPEAT_INTERVAL_MS)
        }
    }
    FilledTonalIconButton(onClick = { if (repeated) repeated = false else step() }, enabled = enabled, interactionSource = interaction) {
        content()
    }
}

private const val STEPPER_REPEAT_DELAY_MS = 400L
private const val STEPPER_REPEAT_INTERVAL_MS = 100L

/** A row of mutually exclusive options with radio semantics. */
@Composable
fun <T> RadioRow(option: T, selected: Boolean, label: String, onSelect: (T) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = { onSelect(option) }, role = Role.RadioButton)
            .padding(horizontal = Dimens.l, vertical = Dimens.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.size(Dimens.l))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * On wide screens, keeps a form or a page of text to a readable width, centered.
 * Applied after `verticalScroll`, so the whole screen still scrolls.
 */
fun Modifier.readableWidth(max: androidx.compose.ui.unit.Dp = 720.dp): Modifier =
    this.wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max)
