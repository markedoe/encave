package app.encave.ui.drink

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
}
