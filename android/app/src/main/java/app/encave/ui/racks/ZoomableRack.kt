package app.encave.ui.racks

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.encave.core.Rack
import app.encave.core.RackCell
import app.encave.ui.theme.Dimens
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** What changes the drawing's shape: kind, depth, cells per row and layers per shelf. Zoom starts again when it changes. */
val Rack.shape: String
    get() = "$kind $positionsPerCell " + (0 until rows).joinToString(",") { columnsInRow(it).toString() } + " ${shelfLayers.orEmpty()}"

/**
 * How far a rack is zoomed in (1 = the whole rack fits) and which spot of it is in the
 * middle of the view, as a fraction of the drawing. Keeping a fraction rather than pixels
 * keeps the same spot in view when the space changes (card shown, rotation).
 */
@Stable
class RackZoomState(level: Float = 1f, center: Offset = Offset(0.5f, 0.5f)) {
    var level by mutableFloatStateOf(level)
    var center by mutableStateOf(center)

    /** Brings a cell to the middle of the view when zoomed in. */
    fun show(rack: Rack, cell: RackCell) {
        if (level <= 1.01f) return
        center = Offset((cell.column + 0.5f) / rack.maxColumns.coerceAtLeast(1), (cell.row + 0.5f) / rack.rows.coerceAtLeast(1))
    }

    companion object {
        const val MAX = 4f
        const val DOUBLE_TAP = 2.5f

        val Saver = listSaver<RackZoomState, Float>(
            save = { listOf(it.level, it.center.x, it.center.y) },
            restore = { RackZoomState(it[0], Offset(it[1], it[2])) },
        )
    }
}

/** Zoom state kept across configuration changes, and started again for a new rack shape. */
@Composable
fun rememberRackZoomState(rack: Rack): RackZoomState =
    rememberSaveable(rack.id, rack.shape, saver = RackZoomState.Saver) { RackZoomState() }

/** Measured size of the drawing, read by the gestures (not state: it changes during layout). */
private class Measured {
    var content = IntSize.Zero
    var view = Size.Zero
}

/** Where the drawing goes on one axis: centered when it is smaller than the view, else around [center]. */
private fun place(view: Float, content: Float, center: Float): Float =
    if (content <= view) (view - content) / 2 else (view / 2 - center * content).coerceIn(view - content, 0f)

private fun clampCenter(view: Float, content: Float, center: Float): Float =
    if (content <= view || content <= 0f) 0.5f else center.coerceIn(view / 2 / content, 1 - view / 2 / content)

/**
 * A rack drawn as large as the space allows, with native zoom: pinch to zoom up to 4×,
 * drag to look around once zoomed in, double-tap a spot to zoom in on it and double-tap
 * again to see the whole rack. The rack is drawn again at each size, so labels stay
 * sharp and names appear once cells are large enough.
 *
 * [content] draws the rack at the given cell size; its second argument zooms on a double
 * tap (cells handle their own taps, so they pass double taps on to it).
 */
@Composable
fun ZoomableRack(
    rack: Rack,
    state: RackZoomState,
    modifier: Modifier = Modifier,
    padding: Dp = Dimens.l,
    content: @Composable (cellSize: Dp, onDoubleTap: () -> Unit) -> Unit,
) {
    val measured = remember { Measured() }
    var lastDown by remember { mutableStateOf(Offset.Unspecified) }
    val scope = rememberCoroutineScope()
    // The gestures below start once and read the latest state: a new rack shape (after
    // editing the rack) brings a new state, which a pinch must zoom.
    val current by rememberUpdatedState(state)

    fun topLeft(): Offset {
        val (view, content) = measured.view to measured.content
        return Offset(place(view.width, content.width.toFloat(), current.center.x), place(view.height, content.height.toFloat(), current.center.y))
    }

    fun clamped(center: Offset, content: Size): Offset =
        Offset(clampCenter(measured.view.width, content.width, center.x), clampCenter(measured.view.height, content.height, center.y))

    fun zoomBy(factor: Float, centroid: Offset) {
        val old = current.level
        val new = (old * factor).coerceIn(1f, RackZoomState.MAX)
        val size = measured.content
        if (new == old || size.width == 0 || size.height == 0) return
        val origin = topLeft()
        // The spot under the fingers stays under them.
        val spot = Offset((centroid.x - origin.x) / size.width, (centroid.y - origin.y) / size.height)
        val grown = Size(size.width * new / old, size.height * new / old)
        val view = measured.view
        current.level = new
        current.center = clamped(
            Offset((view.width / 2 - centroid.x) / grown.width + spot.x, (view.height / 2 - centroid.y) / grown.height + spot.y),
            grown,
        )
    }

    fun panBy(delta: Offset) {
        val size = measured.content
        if (size.width == 0 || size.height == 0) return
        current.center = clamped(
            Offset(current.center.x - delta.x / size.width, current.center.y - delta.y / size.height),
            Size(size.width.toFloat(), size.height.toFloat()),
        )
    }

    fun doubleTap(point: Offset) {
        val fromLevel = current.level
        val fromCenter = current.center
        val toLevel: Float
        val toCenter: Offset
        if (fromLevel > 1.01f) {
            toLevel = 1f
            toCenter = Offset(0.5f, 0.5f)
        } else {
            val size = measured.content
            if (size.width == 0 || size.height == 0) return
            val origin = topLeft()
            val at = if (point.isSpecified()) point else Offset(measured.view.width / 2, measured.view.height / 2)
            toLevel = RackZoomState.DOUBLE_TAP
            toCenter = Offset(
                ((at.x - origin.x) / size.width).coerceIn(0f, 1f),
                ((at.y - origin.y) / size.height).coerceIn(0f, 1f),
            )
        }
        scope.launch {
            animate(0f, 1f, animationSpec = tween(250)) { t, _ ->
                current.level = fromLevel + (toLevel - fromLevel) * t
                current.center = fromCenter + (toCenter - fromCenter) * t
            }
            val grown = measured.content.let { Size(it.width * toLevel / fromLevel, it.height * toLevel / fromLevel) }
            current.center = clamped(toCenter, grown)
        }
    }

    BoxWithConstraints(
        modifier
            .clipToBounds()
            .testTag("rack.canvas")
            // Where the last finger went down, for double taps on cells.
            .pointerInput(Unit) {
                awaitEachGesture {
                    lastDown = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { doubleTap(it) }) }
            .pointerInput(Unit) { detectZoomAndPan(onZoom = ::zoomBy, onPan = ::panBy) },
    ) {
        val density = LocalDensity.current
        val labelHeight = with(density) { (MaterialTheme.typography.labelSmall.lineHeight * 1.25f).toDp() }
        // The rack fits the space at zoom 1, whatever its size; zoom is relative to that.
        val base = fittingCellSize(rack, maxWidth - padding * 2, maxHeight - padding * 2, labelHeight = labelHeight)
        measured.view = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        androidx.compose.foundation.layout.Box(
            Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(Constraints())
                measured.content = IntSize(placeable.width, placeable.height)
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val origin = topLeft()
                    placeable.place(IntOffset(origin.x.roundToInt(), origin.y.roundToInt()))
                }
            },
        ) {
            androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                content(base * state.level) { doubleTap(lastDown) }
            }
        }
    }
}

private fun Offset.isSpecified() = this != Offset.Unspecified && !x.isNaN() && !y.isNaN()

/**
 * Pinch and drag, like `detectTransformGestures`, but zoom and pan are reported apart,
 * nothing is consumed before the fingers move past the touch slop (so taps reach the
 * cells), and a finger landing on a cell does not cancel the pinch.
 */
private suspend fun PointerInputScope.detectZoomAndPan(onZoom: (Float, Offset) -> Unit, onPan: (Offset) -> Unit) {
    awaitEachGesture {
        var zoom = 1f
        var pan = Offset.Zero
        var pastSlop = false
        val slop = viewConfiguration.touchSlop
        awaitFirstDown(requireUnconsumed = false)
        while (true) {
            val event = awaitPointerEvent()
            // Cells take the down of their own tap; only a move used by someone else ends the gesture.
            if (event.changes.any { it.isConsumed && it.positionChanged() }) break
            val zoomChange = event.calculateZoom()
            val panChange = event.calculatePan()
            if (!pastSlop) {
                zoom *= zoomChange
                pan += panChange
                val zoomMotion = abs(1 - zoom) * event.calculateCentroidSize(useCurrent = false)
                if (zoomMotion > slop || pan.getDistance() > slop) pastSlop = true
            }
            if (pastSlop) {
                val centroid = event.calculateCentroid(useCurrent = false)
                if (zoomChange != 1f) onZoom(zoomChange, centroid)
                if (panChange != Offset.Zero) onPan(panChange)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
            if (event.changes.none { it.pressed }) break
        }
    }
}
