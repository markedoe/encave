package app.encave.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import app.encave.core.WineColor
import app.encave.ui.theme.LocalStudioPalette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The backdrop behind studio pictures and covers: a gradient in the wine style's
 * studio shade (design tokens, deep and rich in dark theme), a soft light behind
 * the picture so it stands out, and a bunch of grapes and vine leaves in the
 * corners when there is room. The same drawing as on Apple (StudioPhoto.swift,
 * VineArt). Decorative.
 */
@Composable
fun StudioBackdrop(color: WineColor?, tint: Color, modifier: Modifier = Modifier) {
    val palette = LocalStudioPalette.current
    Canvas(modifier) {
        val dark = palette.dark
        val base = palette.backdrop
        val shade = palette.shade(color)
        drawRect(base)
        drawRect(Brush.verticalGradient(listOf(shade, lerp(shade, base, if (dark) 0.45f else 0.55f))))
        val light = if (dark) lerp(shade, Color.White, 0.35f) else Color.White
        val ink = if (dark) lerp(shade, Color.White, 0.55f).copy(alpha = 0.32f) else tint.copy(alpha = 0.3f)
        if (min(size.width, size.height) >= VineArt.MINIMUM_SIDE.dp.toPx()) {
            VineArt.draw(this, ink, light.copy(alpha = 0.6f), shade.copy(alpha = 0.6f))
        }
        drawRect(
            Brush.radialGradient(
                listOf(light.copy(alpha = if (dark) 0.4f else 0.7f), light.copy(alpha = 0f)),
                center = Offset(size.width / 2, size.height * 0.46f),
                radius = max(size.width, size.height) * 0.48f,
            ),
        )
    }
}

/**
 * A bunch of grapes on its cane with a vine leaf and a tendril (bottom right), and
 * a lone leaf (top left), drawn in a 100 × 100 box; the numbers are the same as on Apple.
 */
internal object VineArt {
    /** No art in small pictures (list rows), where it would only be noise. In dp. */
    const val MINIMUM_SIDE = 90

    /** Grapes in rows of 4, 5, 4, 4, 3, 2 and 1, each row a little to the left. */
    val grapes: List<Offset> = buildList {
        listOf(4, 5, 4, 4, 3, 2, 1).forEachIndexed { row, count ->
            val y = 28f + row * 10.4f
            val start = 52f - row * 0.6f - (count - 1) * 6f
            repeat(count) { add(Offset(start + it * 12f, y)) }
        }
    }

    /** A spiral of 1.5 turns, starting at the cane. */
    private val tendril: List<Offset> = (0..40).map { step ->
        val t = step / 40f * 3f * PI.toFloat()
        val r = 7f * (1f - t / (3.6f * PI.toFloat()))
        Offset(34f + r * cos(t), 14f + r * sin(t))
    }

    fun draw(scope: DrawScope, ink: Color, light: Color, vein: Color) = with(scope) {
        val side = min(size.height * 0.78f, size.width * 0.5f)
        val scale = side / 100f
        translate(size.width - side * 0.82f, size.height - side * 0.92f) {
            withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
                val cane = Path().apply {
                    moveTo(40f, 8f)
                    quadraticTo(60f, 2f, 98f, 12f)
                    moveTo(56f, 6f)
                    lineTo(52f, 24f)
                }
                drawPath(cane, ink, style = Stroke(width = 2.2f, cap = StrokeCap.Round))
                drawPath(polyline(tendril, close = false), ink, style = Stroke(width = 1.4f, cap = StrokeCap.Round))
                grapes.forEach {
                    drawCircle(ink, radius = 6f, center = it)
                    drawCircle(light, radius = 1.8f, center = it + Offset(-2f, -2f))
                }
                leaf(Offset(74f, 30f), 19f, -25f, ink, vein)
            }
        }
        withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
            leaf(Offset(10f, 12f), 17f, 155f, ink, vein)
        }
    }

    /** A point at angle [theta] from the tip of a leaf turned by [rotation] degrees, clockwise. */
    private fun point(center: Offset, r: Float, theta: Float, rotation: Float): Offset {
        val angle = theta + rotation * PI.toFloat() / 180f
        return Offset(center.x + r * sin(angle), center.y - r * cos(angle))
    }

    /** A five-lobed leaf with a notch where the stem joins, and its veins. */
    private fun DrawScope.leaf(center: Offset, radius: Float, rotation: Float, ink: Color, vein: Color) {
        val pi = PI.toFloat()
        val outline = (0 until 90).map { step ->
            val theta = step / 90f * 2f * pi
            val notch = 1f - 0.45f * exp(-((theta - pi) / 0.25f).pow(2))
            point(center, radius * (0.78f + 0.22f * cos(5f * theta)) * notch, theta, rotation)
        }
        drawPath(polyline(outline, close = true), ink)
        val veins = Path()
        listOf(0, 1, -1, 2, -2).forEach { lobe ->
            val start = point(center, radius * 0.45f, pi, rotation)
            val end = point(center, radius * 0.85f, lobe * 2f * pi / 5f, rotation)
            veins.moveTo(start.x, start.y)
            veins.lineTo(end.x, end.y)
        }
        drawPath(veins, vein, style = Stroke(width = 0.9f, cap = StrokeCap.Round))
    }

    private fun polyline(points: List<Offset>, close: Boolean) = Path().apply {
        points.forEachIndexed { index, it -> if (index == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) }
        if (close) close()
    }
}
