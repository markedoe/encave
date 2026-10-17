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
