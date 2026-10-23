package app.encave.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Labels found from their paper on synthetic photos from shared vectors. CaveKit
 * (LabelPaperTests.swift) draws and checks the same file,
 * `fixtures/label-paper-vectors.json`, so the two platforms cannot drift.
 */
class LabelPaperTest {
    /** Tests run in android/core. */
    private val root: JsonObject = Json.parseToJsonElement(File("../../fixtures/label-paper-vectors.json").readText()).jsonObject

    private fun JsonObject.doubles(key: String) = this[key]?.takeIf { it !is JsonNull }?.jsonArray?.map { it.jsonPrimitive.double }
    private fun JsonObject.ints(key: String) = this[key]?.jsonArray?.map { it.jsonPrimitive.int }
    private fun JsonObject.obj(key: String) = this[key]?.jsonObject

    /** The case's photo, drawn as the vectors file describes. */
    private fun draw(item: JsonObject): IntArray {
        val w = item.getValue("width").jsonPrimitive.int
        val h = item.getValue("height").jsonPrimitive.int
        val background = item.ints("background")!!
        val image = Array(w * h) { background }
        fun pixels(rect: List<Double>) = listOf((rect[0] * w).toInt(), (rect[1] * h).toInt(), (rect[2] * w).toInt(), (rect[3] * h).toInt())
        fun fill(rect: List<Double>, color: List<Int>) {
            val (l, t, r, b) = pixels(rect)
            for (y in t until b) for (x in l until r) image[y * w + x] = color
        }
        item.obj("bottle")?.let { fill(it.doubles("rect")!!, it.ints("color")!!) }
        item.obj("label")?.let { label ->
            val (l, t, r, b) = pixels(label.doubles("rect")!!)
            val base = label.ints("color")!!
            val soft = label["softEdge"]?.jsonPrimitive?.int ?: 0
            val shadow = label.obj("shadow")
            for (y in t until b) {
                var factor = 1.0
                if (shadow != null) {
                    val from = t + (shadow.getValue("from").jsonPrimitive.double * (b - t)).toInt()
                    if (y >= from) factor = 1 - shadow.getValue("darkness").jsonPrimitive.double * (y - from) / maxOf(1, b - 1 - from)
                }
                for (x in l until r) {
                    var color = base.map { (it * factor).toInt() }
                    val d = minOf(minOf(x - l, r - 1 - x), minOf(y - t, b - 1 - y))
                    if (soft > 0 && d < soft) {
                        val a = (d + 1).toDouble() / (soft + 1)
                        val under = image[y * w + x]
                        color = (0 until 3).map { (under[it] + (color[it] - under[it]) * a).toInt() }
                    }
                    image[y * w + x] = color
                }
            }
        }
        item.obj("picture")?.let { fill(it.doubles("rect")!!, it.ints("color")!!) }
        val textColor = item.ints("textColor") ?: listOf(0, 0, 0)
        (item["text"] as? JsonArray)?.forEach { bar -> fill(bar.jsonArray.map { it.jsonPrimitive.double }, textColor) }
        item.obj("hand")?.let { hand ->
            val centre = hand.doubles("centre")!!
            val radius = hand.doubles("radius")!!
            val color = hand.ints("color")!!
            val cx = centre[0] * w
            val cy = centre[1] * h
            val rx = radius[0] * w
            val ry = radius[1] * h
            for (y in 0 until h) for (x in 0 until w) {
                val dx = (x + 0.5 - cx) / rx
                val dy = (y + 0.5 - cy) / ry
                if (dx * dx + dy * dy <= 1) image[y * w + x] = color
            }
        }
        return IntArray(w * h) { (0xFF shl 24) or (image[it][0] shl 16) or (image[it][1] shl 8) or image[it][2] }
    }

    @Test
    fun vectorsFindTheWholeLabel() {
        val tolerance = root.getValue("tolerance").jsonPrimitive.double
        val cases = root.getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 8)
        for (item in cases) {
            val name = item.getValue("name").jsonPrimitive.content
            val w = item.getValue("width").jsonPrimitive.int
            val h = item.getValue("height").jsonPrimitive.int
            val seed = item["seed"]?.jsonPrimitive?.double
            val box = LabelPaper.find(draw(item), w, h, seed)
            val expected = item.doubles("expected")
            if (expected == null) {
                assertNull(box, name)
                continue
            }
            assertNotNull(box, name)
            val sides = listOf(box!!.left, box.top, box.right, box.bottom)
            for (i in 0 until 4) assertTrue(kotlin.math.abs(sides[i] - expected[i]) <= tolerance, "$name: $sides instead of $expected")
            assertTrue(box.confidence >= 0.6, name)
        }
    }

    @Test
    fun tinyOrMissingPixelsAreIgnored() {
        assertNull(LabelPaper.find(IntArray(4), 2, 2))
        assertNull(LabelPaper.find(IntArray(10), 100, 100))
    }
}
