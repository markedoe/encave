package app.encave.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.encave.core.WineColor
import app.encave.ui.theme.LocalWinePalette

/** Bubbles on sparkling wine (x, y, radius as fractions of the size), so it differs from white by shape. */
private val bubbles = listOf(
    Triple(0.36f, 0.62f, 0.11f), Triple(0.60f, 0.40f, 0.085f), Triple(0.64f, 0.68f, 0.07f), Triple(0.44f, 0.33f, 0.06f),
)

/**
 * A bottle seen from its end, filled with the wine's token color, with a glass
 * reflection and the punt. Used in lists and racks so a bottle always looks the
 * same. Decorative: the style is always also given as text.
 */
@Composable
fun BottleEnd(color: WineColor?, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val fill = LocalWinePalette.current[color]
    Canvas(modifier.size(size)) {
        val width = this.size.minDimension
        val radius = width / 2
        drawCircle(fill, radius)
        // Glass reflection (neutral white light, not a color).
        drawCircle(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.45f), Color.Transparent),
                center = Offset(width * 0.32f, width * 0.28f), radius = width * 0.45f,
            ),
            radius,
        )
        val rim = maxOf(1.dp.toPx(), width * 0.06f)
        drawCircle(Color.Black.copy(alpha = 0.18f), radius - rim / 2, style = Stroke(rim))
        // Punt: the dimple at the bottom of the bottle.
        val punt = maxOf(0.5.dp.toPx(), width * 0.03f)
        drawCircle(Color.White.copy(alpha = 0.18f), radius - width * 0.3f - punt / 2, style = Stroke(punt))
        if (color == WineColor.Sparkling) {
            val line = maxOf(0.75.dp.toPx(), width * 0.035f)
            bubbles.forEach { (x, y, r) ->
                val center = Offset(width * x, width * y)
                drawCircle(Color.White.copy(alpha = 0.7f), width * r, center)
                drawCircle(Color.Black.copy(alpha = 0.35f), width * r - line / 2, center, style = Stroke(line))
            }
        }
    }
}

/**
 * The neck of a bottle lying the other way round (head-to-tail behind another), seen end on:
 * a ring of glass around the capsule, both in the wine's tint. Its shape tells it apart from a
 * base ([BottleEnd]); sparkling wine shows its wire cage.
 */
@Composable
fun BottleNeck(color: WineColor?, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    val fill = LocalWinePalette.current[color]
    Canvas(modifier.size(size)) {
        val width = this.size.minDimension
        val radius = width / 2
        // The glass around the neck.
        val glass = maxOf(2.dp.toPx(), width * 0.14f)
        drawCircle(fill.copy(alpha = 0.75f), radius - glass / 2, style = Stroke(glass))
        val edge = maxOf(0.75.dp.toPx(), width * 0.04f)
        drawCircle(Color.Black.copy(alpha = 0.18f), radius - edge / 2, style = Stroke(edge))
        // The capsule.
        val capsule = radius - width * 0.24f
        drawCircle(fill, capsule)
        val rim = maxOf(0.75.dp.toPx(), width * 0.05f)
        drawCircle(Color.Black.copy(alpha = 0.3f), capsule - rim / 2, style = Stroke(rim))
        drawCircle(Color.White.copy(alpha = 0.45f), width * 0.07f, Offset(width * 0.42f, width * 0.42f))
        if (color == WineColor.Sparkling) {
            // The wire cage over the cork.
            val line = maxOf(1.dp.toPx(), width * 0.05f)
            val wire = Color.White.copy(alpha = 0.8f)
            drawLine(wire, Offset(width * 0.3f, width * 0.5f), Offset(width * 0.7f, width * 0.5f), line)
            drawLine(wire, Offset(width * 0.5f, width * 0.3f), Offset(width * 0.5f, width * 0.7f), line)
        }
    }
}
