package app.encave.ui.drink

import androidx.compose.runtime.saveable.SaverScope
import app.encave.core.CellarJson
import app.encave.core.RatingScale
import app.encave.core.Tasting
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tasting form saves what it holds and knows when something was typed. */
class TastingFormStateTest {
    private val date = Instant.parse("2026-03-01T19:00:00Z")

    @Test
    fun hidingTheFullNoteKeepsItsFields() {
        val form = TastingFormState(Tasting(date = date, nose = "Cherry", palate = "Supple"))
        assertTrue(form.expanded)
        form.expanded = false
        val saved = form.toTasting()
        assertEquals("Cherry", saved.nose)
        assertEquals("Supple", saved.palate)
    }

    @Test
    fun changesAreNoticedAndUndoneChangesAreNot() {
        val form = TastingFormState(Tasting(date = date, note = "Lovely"))
        assertFalse(form.isDirty)
        form.expanded = true
        assertFalse(form.isDirty)
        form.note = "Lovely, long finish"
        assertTrue(form.isDirty)
        form.note = "Lovely "
        assertFalse(form.isDirty)
        form.rating = 4
        assertTrue(form.isDirty)
    }

    @Test
    fun aNoteBeingTypedIsSavedAndStillKnownAsChanged() {
        // Saved state outlives the app's process (ended while the camera app was open).
        val form = TastingFormState(Tasting(date = date, note = "Lovely"))
        form.note = "Lovely, long finish"
        form.rating = 4
        form.expanded = true
        val saved = with(TastingFormState.Saver) { SaverScope { true }.save(form) }!!
        val restored = TastingFormState.Saver.restore(saved)!!
        assertEquals(form.toTasting(), restored.toTasting())
        assertTrue(restored.expanded)
        assertTrue(restored.isDirty)
        restored.note = "Lovely"
        restored.rating = null
        assertFalse(restored.isDirty)
    }

    /** A tasting from a newer version: a key this one does not know, kept by the stored record. */
    private fun guided(): Tasting = CellarJson.json.decodeFromString(
        Tasting.serializer(),
        """{"date":"2026-03-01T19:00:00Z","rating":5,"score100":95,"aromas":["fruit.redCherry","floral.violet","future.aroma"],""" +
            """"acidity":4,"tannin":3,"intensity":"medium","hue":"ruby","nose":"Cherry","sparkle":{"level":2}}""",
    )

    @Test
    fun editingKeepsEveryFieldAndTheKeysOfANewerVersion() {
        val stored = guided()
        assertTrue(stored.unknownKeys.containsKey("sparkle"))
        val form = TastingFormState(stored)
        // Shown open: it holds a guided note.
        assertTrue(form.expanded)
        assertFalse(form.isDirty)
        form.note = "Lovely"
        val saved = form.toTasting()
        assertEquals(stored.copy(note = "Lovely"), saved)
        assertEquals(stored.unknownKeys, saved.unknownKeys)
        assertEquals(95.0, saved.score100)
        assertEquals(listOf("fruit.redCherry", "floral.violet", "future.aroma"), saved.aromas)
    }

    @Test
    fun theGuidedSheetSetsAromasLevelsAndAppearance() {
        val form = TastingFormState(Tasting(date = date))
        form.toggleAroma("fruit.plum")
        form.toggleAroma("spice.clove")
        form.toggleAroma("fruit.plum")
        form.setLevel("tannin", 4)
        form.setLevel("body", 9)
        form.setLevel("acidity", 2)
        form.setLevel("acidity", null)
        form.intensity = "deep"
        form.hue = "garnet"
        val saved = form.toTasting()
        assertEquals(listOf("spice.clove"), saved.aromas)
        assertEquals(4, saved.tannin)
        assertEquals(5, saved.body)
        assertEquals(null, saved.acidity)
        assertEquals("deep", saved.intensity)
        assertEquals("garnet", saved.hue)
        assertTrue(form.isDirty)
        // Everything taken back: nothing changed, and no empty list is written.
        form.toggleAroma("spice.clove")
        form.setLevel("tannin", null)
        form.setLevel("body", null)
        form.intensity = null
        form.hue = null
        assertEquals(null, form.toTasting().aromas)
        assertFalse(form.isDirty)
    }

    @Test
    fun aScoreIsKeptOnlyWithItsStars() {
        val form = TastingFormState(Tasting(date = date))
        form.rate(17.5, RatingScale.Twenty)
        assertEquals(4, form.rating)
        assertEquals(93.75, form.toTasting().score100)
        // Stars chosen afterwards: the score no longer matches and is left out.
        form.rating = 5
        form.score100 = null
        assertEquals(null, form.toTasting().score100)
        form.rate(95.0, RatingScale.Hundred)
        assertEquals(5, form.toTasting().rating)
        assertEquals(95.0, form.toTasting().score100)
        // A rating changed to other stars while a score is still there: the stale score is not saved.
        form.rating = 3
        assertEquals(null, form.toTasting().score100)
        form.rate(null, RatingScale.Hundred)
        assertEquals(null, form.toTasting().rating)
    }

    @Test
    fun aGuidedNoteBeingTypedIsSavedWithTheKeysOfANewerVersion() {
        val form = TastingFormState(guided().copy(date = Instant.parse("2026-03-01T19:00:00.250Z")))
        form.toggleAroma("oak.vanilla")
        form.setLevel("length", 5)
        val saved = with(TastingFormState.Saver) { SaverScope { true }.save(form) }!!
        val restored = TastingFormState.Saver.restore(saved)!!
        assertEquals(form.toTasting(), restored.toTasting())
        assertEquals(Instant.parse("2026-03-01T19:00:00.250Z"), restored.toTasting().date)
        assertTrue(restored.toTasting().unknownKeys.containsKey("sparkle"))
        assertTrue(restored.isDirty)
        restored.toggleAroma("oak.vanilla")
        restored.setLevel("length", null)
        assertFalse(restored.isDirty)
    }
}
