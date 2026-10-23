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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.encave.core.Rack
import app.encave.core.RackCell
import app.encave.core.RackViewport
import app.encave.core.ViewportArea
import app.encave.core.ViewportPoint
import app.encave.ui.theme.Dimens
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * What changes the drawing's shape: kind, depth, cells per row, layers per shelf, zones and head-to-tail
 * backs (drawn half a place wider). Zoom starts again when it changes.
 */
val Rack.shape: String
    get() = "$kind $positionsPerCell " + (0 until rows).joinToString(",") { columnsInRow(it).toString() } +
        " ${shelfLayers.orEmpty()} ${zoneBreaks.orEmpty()} $isBackReversed"

/**
 * How far a rack is zoomed in (1 = the whole rack fits) and which spot of it is in the
 * middle of the view, as a fraction of the drawing. Keeping a fraction rather than pixels
 * keeps the same spot in view when the space changes (card shown, rotation).
 */
@Stable
class RackZoomState(level: Float = 1f, center: Offset = Offset(0.5f, 0.5f)) {
    /** The zoom the rack is drawn at; changes once a gesture ends, so the rack is drawn again once. */
    var level by mutableFloatStateOf(level)

    /**
     * While pinching or animating: how much the drawing is scaled on top of [level], applied
     * when drawing only (no new composition or measure on each frame).
     */
    var live by mutableFloatStateOf(1f)
    var center by mutableStateOf(center)

    /** The drawing's size at [level] and the view's, in pixels, for the overview (set when laid out). */
    var contentSize by mutableStateOf(Size.Zero)
        internal set
    var viewSize by mutableStateOf(Size.Zero)
        internal set

    /** The zoom as seen. */
    val effective: Float get() = level * live

    /** The part of the drawing in view, as fractions of it. */
    val visibleArea: ViewportArea
        get() {
            val content = Size(contentSize.width * live, contentSize.height * live)
            val x = place(viewSize.width, content.width, center.x)
            val y = place(viewSize.height, content.height, center.y)
            return RackViewport.visibleArea(
                content.width.toDouble(), content.height.toDouble(), viewSize.width.toDouble(), viewSize.height.toDouble(),
                ViewportPoint(-x.toDouble(), -y.toDouble()),
            )
        }

    /** Brings a point of the drawing (fractions of it) to the middle of the view, as far as it goes. */
    fun jump(point: ViewportPoint) {
        val width = contentSize.width * live
        val height = contentSize.height * live
        if (width <= 0f || height <= 0f) return
        val offset = RackViewport.offsetCentering(point, width.toDouble(), height.toDouble(), viewSize.width.toDouble(), viewSize.height.toDouble())
        center = Offset(
            if (width <= viewSize.width) 0.5f else ((offset.x + viewSize.width / 2) / width).toFloat(),
            if (height <= viewSize.height) 0.5f else ((offset.y + viewSize.height / 2) / height).toFloat(),
        )
    }

    /** Draws the rack again at the zoom as seen. */
    fun settle() {
        if (live == 1f) return
        level = effective
        live = 1f
    }

    /**
     * Brings a place to the middle of the view when the rack is larger than the view (zoomed in,
     * or drawn at the smallest place size); head-to-tail, a back place is half a place right.
     */
    fun show(rack: Rack, cell: RackCell, depth: Int? = null) {
        val fits = viewSize != Size.Zero && contentSize.width * live <= viewSize.width && contentSize.height * live <= viewSize.height
        if (effective <= 1.01f && fits) return
        val point = RackViewport.center(rack, cell, depth)
        center = Offset(point.x.toFloat(), point.y.toFloat())
    }

    companion object {
        const val MAX = 4f
        const val DOUBLE_TAP = 2.5f

        val Saver = listSaver<RackZoomState, Float>(
            save = { listOf(it.effective, it.center.x, it.center.y) },
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
internal fun place(view: Float, content: Float, center: Float): Float =
    if (content <= view) (view - content) / 2 else (view / 2 - center * content).coerceIn(view - content, 0f)

private fun clampCenter(view: Float, content: Float, center: Float): Float =
    if (content <= view || content <= 0f) 0.5f else center.coerceIn(view / 2 / content, 1 - view / 2 / content)

/**
 * A rack drawn as large as the space allows, with native zoom: pinch to zoom up to 4×,
 * drag to look around once zoomed in, double-tap a spot to zoom in on it and double-tap
 * again to see the whole rack. The rack is drawn again at each size, so labels stay
 * sharp and names appear once cells are large enough.
 *
 * Places are never drawn under 48 dp: a larger rack is drawn at that size, scrolls both ways
 * (drag) under an overview of the whole rack showing the part in view, and zooms from there.
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

    /** The drawing's size as seen, with the live scale of a gesture. */
    fun shown(): Size = Size(measured.content.width * current.live, measured.content.height * current.live)

    fun topLeft(): Offset {
        val view = measured.view
        val content = shown()
        return Offset(place(view.width, content.width, current.center.x), place(view.height, content.height, current.center.y))
    }

    fun clamped(center: Offset, content: Size): Offset =
        Offset(clampCenter(measured.view.width, content.width, center.x), clampCenter(measured.view.height, content.height, center.y))

    fun zoomBy(factor: Float, centroid: Offset) {
        val old = current.effective
        val new = (old * factor).coerceIn(1f, RackZoomState.MAX)
        val size = shown()
        if (new == old || size.width == 0f || size.height == 0f) return
        val origin = topLeft()
        // The spot under the fingers stays under them.
        val spot = Offset((centroid.x - origin.x) / size.width, (centroid.y - origin.y) / size.height)
        val grown = Size(size.width * new / old, size.height * new / old)
        val view = measured.view
        current.live = new / current.level
        current.center = clamped(
            Offset((view.width / 2 - centroid.x) / grown.width + spot.x, (view.height / 2 - centroid.y) / grown.height + spot.y),
            grown,
        )
    }

    fun panBy(delta: Offset) {
        val size = shown()
        if (size.width == 0f || size.height == 0f) return
        current.center = clamped(Offset(current.center.x - delta.x / size.width, current.center.y - delta.y / size.height), size)
    }

    fun doubleTap(point: Offset) {
        current.settle()
        val fromLevel = current.level
        val fromCenter = current.center
        val toLevel: Float
        val toCenter: Offset
        if (fromLevel > 1.01f) {
            toLevel = 1f
            toCenter = Offset(0.5f, 0.5f)
        } else {
            val size = shown()
            if (size.width == 0f || size.height == 0f) return
            val origin = topLeft()
            val at = if (point.isSpecified()) point else Offset(measured.view.width / 2, measured.view.height / 2)
            toLevel = RackZoomState.DOUBLE_TAP
            toCenter = Offset(
                ((at.x - origin.x) / size.width).coerceIn(0f, 1f),
                ((at.y - origin.y) / size.height).coerceIn(0f, 1f),
            )
        }
        val target = current
        scope.launch {
            // Scaled while it moves, drawn again at the new size once.
            animate(0f, 1f, animationSpec = tween(250)) { t, _ ->
                target.live = (fromLevel + (toLevel - fromLevel) * t) / fromLevel
                target.center = fromCenter + (toCenter - fromCenter) * t
            }
            target.center = clamped(toCenter, shown())
            target.settle()
        }
    }

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val labelHeight = with(density) { (MaterialTheme.typography.labelSmall.lineHeight * 1.25f).toDp() }
        // The rack fits the space at zoom 1 when its places reach the minimum; else it is drawn at
        // the minimum and scrolls. Zoom is relative to that.
        val fitted = fittingCellSize(rack, maxWidth - padding * 2, maxHeight - padding * 2, labelHeight = labelHeight)
        val minimum = RackViewport.minimumCellSize(rack).toFloat()
        val scrolls = RackViewport.scrolls(fitted.value.toDouble(), minimum.toDouble())
        val base = RackViewport.cellSize(fitted.value.toDouble(), minimum.toDouble()).toFloat().dp
        Column(Modifier.fillMaxSize()) {
            if (scrolls) {
                RackOverview(state) { Box(Modifier.padding(padding)) { content(base * state.level) {} } }
                HorizontalDivider()
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
                    .testTag("rack.canvas")
                    // Where the last finger went down, for double taps on cells.
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            lastDown = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position
                        }
                    }
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { doubleTap(it) }) }
                    .pointerInput(Unit) { detectZoomAndPan(onZoom = ::zoomBy, onPan = ::panBy, onEnd = { current.settle() }) }
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(Constraints())
                        measured.content = IntSize(placeable.width, placeable.height)
                        measured.view = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
                        // For the overview and for showing a bottle (read when drawing or later, not here).
                        current.contentSize = Size(placeable.width.toFloat(), placeable.height.toFloat())
                        current.viewSize = measured.view
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            // Read when placing and drawing only: a gesture moves and scales the drawing
                            // without composing or measuring it again.
                            val origin = topLeft()
                            placeable.placeWithLayer(IntOffset(origin.x.roundToInt(), origin.y.roundToInt())) {
                                val scale = current.live
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = TransformOrigin(0f, 0f)
                            }
                        }
                    },
            ) {
                Box(Modifier.padding(padding)) {
                    content(base * state.level) { doubleTap(lastDown) }
                }
            }
        }
    }
}

/** Height of the overview above a rack that scrolls. */
private val OverviewHeight = 56.dp

/**
 * The whole rack, small, above a rack that scrolls: the part in view is framed. A tap, or a
 * drag, brings that part into view. Hidden from TalkBack, which moves from place to place and
 * scrolls to each one.
 */
@Composable
private fun RackOverview(state: RackZoomState, drawing: @Composable () -> Unit) {
    val frame = MaterialTheme.colorScheme.primary
    // Where the small rack is in the strip: a touch anywhere along the strip goes to its nearest spot.
    var map by remember { mutableStateOf(Rect.Zero) }
    Box(
        Modifier.fillMaxWidth().height(OverviewHeight + Dimens.s * 2).padding(Dimens.s).testTag("rack.overview")
            .clearAndSetSemantics { }
            .pointerInput(state) {
                fun jump(at: Offset) {
                    if (map.width <= 0f || map.height <= 0f) return
                    state.jump(
                        ViewportPoint(
                            ((at.x - map.left) / map.width).coerceIn(0f, 1f).toDouble(),
                            ((at.y - map.top) / map.height).coerceIn(0f, 1f).toDouble(),
                        ),
                    )
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    jump(down.position)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        jump(change.position)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.onPlaced { map = it.boundsInParent() }) {
            // Drawn as in the view, then scaled down, so the frame matches it exactly.
            Box(
                Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(Constraints())
                    if (placeable.width == 0 || placeable.height == 0) return@layout layout(0, 0) {}
                    val scale = minOf(constraints.maxWidth.toFloat() / placeable.width, constraints.maxHeight.toFloat() / placeable.height, 1f)
                    layout((placeable.width * scale).roundToInt(), (placeable.height * scale).roundToInt()) {
                        placeable.placeWithLayer(0, 0) {
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0f, 0f)
                        }
                    }
                },
            ) { drawing() }
            // On top: keeps touches from the places below, and frames the part in view.
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } }
                    .drawBehind {
                        val area = state.visibleArea
                        val stroke = 2.dp.toPx()
                        val topLeft = Offset((area.x * size.width).toFloat(), (area.y * size.height).toFloat())
                        val width = maxOf(stroke * 4, (area.width * size.width).toFloat())
                        val height = maxOf(stroke * 4, (area.height * size.height).toFloat())
                        val corner = CornerRadius(3.dp.toPx())
                        drawRoundRect(frame.copy(alpha = 0.12f), topLeft, Size(width, height), corner)
                        drawRoundRect(
                            frame, topLeft + Offset(stroke / 2, stroke / 2), Size(width - stroke, height - stroke), corner,
                            style = Stroke(stroke),
                        )
                    },
            )
        }
    }
}

private fun Offset.isSpecified() = this != Offset.Unspecified && !x.isNaN() && !y.isNaN()

/**
 * Pinch and drag, like `detectTransformGestures`, but zoom and pan are reported apart,
 * nothing is consumed before the fingers move past the touch slop (so taps reach the
 * cells), and a finger landing on a cell does not cancel the pinch.
 */
private suspend fun PointerInputScope.detectZoomAndPan(onZoom: (Float, Offset) -> Unit, onPan: (Offset) -> Unit, onEnd: () -> Unit) {
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
        onEnd()
    }
}
