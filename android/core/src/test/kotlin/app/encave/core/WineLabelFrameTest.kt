package app.encave.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The label's place read from the AI's answer, and the frame kept with the wine (as `WineLabelFrameTests.swift`). */
class WineLabelFrameTest {
    private fun parse(json: String) = AiLabelPlacement.parse(json)

    @Test
    fun readsTheBoxAndTheAngle() {
        assertEquals(
            AiLabelPlacement(0.2, 0.3, 0.5, 0.4, -4.5),
            parse("""{"name":"X","label_box":{"x":0.2,"y":0.3,"w":0.5,"h":0.4},"label_angle":-4.5}"""),
        )
    }

    @Test
    fun aMissingOrNullAngleIsLevel() {
        assertEquals(0.0, parse("""{"label_box":{"x":0.2,"y":0.3,"w":0.5,"h":0.4}}""")?.angle)
        assertEquals(0.0, parse("""{"label_box":{"x":0.2,"y":0.3,"w":0.5,"h":0.4},"label_angle":null}""")?.angle)
        assertEquals(3.0, parse("""{"label_box":{"x":0,"y":0,"w":1,"h":1},"label_angle":3}""")?.angle)
    }

    @Test
    fun noBoxGivesNothing() {
        assertNull(parse("""{"name":"X"}"""))
        assertNull(parse("""{"label_box":null,"label_angle":2}"""))
        assertNull(parse("not json"))
    }

    @Test
    fun rejectsABoxThatIsNotFourNumbers() {
        assertNull(parse("""{"label_box":"top"}"""))
        assertNull(parse("""{"label_box":{"x":0.2,"y":0.3,"w":0.5}}"""))
        assertNull(parse("""{"label_box":{"x":"0.2","y":0.3,"w":0.5,"h":0.4}}"""))
        assertNull(parse("""{"label_box":{"x":true,"y":0.3,"w":0.5,"h":0.4}}"""))
        assertNull(parse("""{"label_box":{"x":0.2,"y":0.3,"w":0.5,"h":0.4},"label_angle":"tilted"}"""))
    }

    @Test
    fun rejectsValuesOutsideZeroToOne() {
        assertNull(AiLabelPlacement.validated(-0.1, 0.2, 0.5, 0.5))
        assertNull(AiLabelPlacement.validated(0.2, 1.2, 0.5, 0.5))
        assertNull(AiLabelPlacement.validated(0.0, 0.0, 1.5, 0.5))
        assertNull(AiLabelPlacement.validated(0.0, 0.0, 0.5, Double.NaN))
        assertNull(AiLabelPlacement.validated(0.0, 0.0, 0.5, 0.5, Double.POSITIVE_INFINITY))
    }

    @Test
    fun rejectsABoxLeavingThePhoto() {
        assertNull(AiLabelPlacement.validated(0.6, 0.1, 0.5, 0.5))
        assertNull(AiLabelPlacement.validated(0.1, 0.7, 0.5, 0.4))
        // Up to the edge, with rounding.
        assertNotNull(AiLabelPlacement.validated(0.3, 0.7, 0.7, 0.3))
    }

    @Test
    fun rejectsATinyBox() {
        assertNull(AiLabelPlacement.validated(0.2, 0.2, 0.09, 0.5))
        assertNull(AiLabelPlacement.validated(0.2, 0.2, 0.5, 0.05))
        assertNotNull(AiLabelPlacement.validated(0.2, 0.2, 0.1, 0.1))
    }

    @Test
    fun rejectsASteepAngle() {
        assertNull(AiLabelPlacement.validated(0.2, 0.2, 0.5, 0.5, 45.5))
        assertNull(AiLabelPlacement.validated(0.2, 0.2, 0.5, 0.5, -60.0))
        assertNotNull(AiLabelPlacement.validated(0.2, 0.2, 0.5, 0.5, -45.0))
        assertNull(parse("""{"label_box":{"x":0.2,"y":0.3,"w":0.5,"h":0.4},"label_angle":90}"""))
    }

    @Test
    fun theFrameIsUsedOnlyForItsPhoto() {
        val frame = WineLabelFrame("sha256-a", 0.1, 0.2, 0.6, 0.5, 2.0)
        assertNull(Wine(name = "Test", photoId = "sha256-a").labelFrame("sha256-a"))
        val wine = Wine(name = "Test", photoId = "sha256-a", labelFrame = frame)
        assertEquals(frame, wine.labelFrame("sha256-a"))
        assertNull(wine.labelFrame("sha256-b"))
        assertNull(wine.labelFrame(null))
        // A new label photo: the frame is stale.
        val replaced = wine.copy(photoId = "sha256-b")
        assertNull(replaced.labelFrame(replaced.photoId))
    }

    @Test
    fun encodedOnlyWhenSetAndKeptByOlderReaders() {
        val plain = Wine(name = "Framed", photoId = "sha256-a")
        assertFalse(CellarJson.encode(Cellar(listOf(plain))).contains("labelFrame"))
        val wine = plain.copy(labelFrame = WineLabelFrame("sha256-a", 0.25, 0.125, 0.5, 0.625, -3.5))
        val decoded = CellarJson.decode(CellarJson.encode(Cellar(listOf(wine)))).wines[wine.id]
        assertEquals(wine, decoded)
        // A key added inside the frame by a newer version is written back.
        val newer = CellarJson.json.decodeFromString(
            Wine.serializer(),
            """{"id":"${wine.id}","name":"N","grapes":[],"labelFrame":{"photoID":"p","x":0.1,"y":0.1,"w":0.5,"h":0.5,"angle":0,"corners":[1,2]}}""",
        )
        assertTrue(CellarJson.json.encodeToString(Wine.serializer(), newer).contains("corners"))
        // Part of equality, so a merge sees the change.
        assertNotEquals(wine, wine.copy(labelFrame = wine.labelFrame?.copy(x = 0.3)))
    }

    @Test
    fun cropIsTheLabelWithItsMargin() {
        val crop = WineLabelFrame("p", 0.25, 0.5, 0.5, 0.25, 5.0).crop(1000.0, 2000.0)
        assertEquals(500.0, crop.centerX)
        assertEquals(1250.0, crop.centerY)
        assertEquals(560.0, crop.width, 1e-9)
        assertEquals(560.0, crop.height, 1e-9)
        val whole = WineLabelFrame("p", 0.0, 0.0, 1.0, 1.0).crop(800.0, 600.0)
        assertEquals(800.0, whole.width)
        assertEquals(600.0, whole.height)
    }

    @Test
    fun cacheTagChangesWithTheFrame() {
        val frame = WineLabelFrame("p", 0.25, 0.5, 0.5, 0.25, -2.5)
        assertEquals("f250-500-500-250-a-25", frame.cacheTag)
        assertNotEquals(frame.cacheTag, frame.copy(y = 0.45).cacheTag)
    }
}
