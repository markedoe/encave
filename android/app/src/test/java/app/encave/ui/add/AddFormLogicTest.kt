package app.encave.ui.add

import androidx.compose.runtime.saveable.SaverScope
import app.encave.core.Cellar
import app.encave.core.Rack
import app.encave.core.Wine
import app.encave.core.WineColor
import app.encave.data.ai.LabelAnalysis
import app.encave.data.ai.completed
import app.encave.data.ai.completing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AddFormLogicTest {
    @Test
    fun bottlesGoToTheLastRackUsedWhileItHasRoom() {
        val cellar = Cellar()
        val full = Rack(name = "Full", rows = 1, columns = 1)
        val roomy = Rack(name = "Roomy", rows = 2, columns = 2)
        cellar.addRack(full)
        cellar.addRack(roomy)
        cellar.addBottles(Wine(name = "Filler"), 1, listOf(app.encave.core.Slot(full.id, 0, 0)))
        assertEquals(roomy.id, initialLocation(cellar, null, "rack:${roomy.id}").rackId)
        // The rack used last is full: the first one with room.
        assertEquals(roomy.id, initialLocation(cellar, null, "rack:${full.id}").rackId)
        assertEquals(roomy.id, initialLocation(cellar, null, null).rackId)
        // Opened from a rack: that one.
        assertEquals(full.id, initialLocation(cellar, full, "rack:${roomy.id}").rackId)
        val elsewhere = initialLocation(cellar, null, "other:Kitchen")
        assertNull(elsewhere.rackId)
        assertEquals("Kitchen", elsewhere.storage)
        assertNull(initialLocation(Cellar(), null, null).rackId)
    }

    @Test
    fun theFormSaysWhetherTheAiAddedAnything() {
        val form = WineFormState(Wine(name = "Clos des Tilleuls", producer = "Domaine Martin"), 2026)
        // Typed fields are kept: nothing to add, no "Completed by AI".
        assertEquals(false, form.apply(LabelAnalysis(name = "Clos Tilleuls", producer = "Martin")))
        assertNull(form.aiCompletedAt)
        assertEquals(true, form.apply(LabelAnalysis(region = "Bourgogne")))
        assertEquals("Bourgogne", form.region)
        assertNotNull(form.aiCompletedAt)
        // The same answer again changes nothing.
        assertEquals(false, form.apply(LabelAnalysis(region = "Bourgogne")))
    }

    @Test
    fun anAnswerWithNothingNewIsNotACompletion() {
        val saved = Wine(name = "Clos des Tilleuls", producer = "Domaine Martin", color = WineColor.Red)
        // Only what is filled already, or nothing at all: the wine stays as it was, without the AI's date.
        assertEquals(saved, LabelAnalysis(name = "Other name", producer = "Martin", color = "white").completing(saved))
        assertEquals(saved, LabelAnalysis().completing(saved))
        assertNull(LabelAnalysis().completing(saved).aiCompletedAt)
    }

    @Test
    fun theAiCompletesOnlyWhatIsEmptyAndWhatWasNotChangedSince() {
        val saved = Wine(name = "Clos des Tilleuls", producer = "Domaine Martin", vintage = 2019)
        val analysis = LabelAnalysis(
            name = "Clos Tilleuls", producer = "Martin", vintage = 2018, color = "red", region = "Bourgogne",
            grapes = listOf("Pinot Noir"), drinkFrom = 2023, drinkUntil = 2030,
        )
        val completed = analysis.completing(saved)
        assertEquals("Clos des Tilleuls", completed.name)
        assertEquals("Domaine Martin", completed.producer)
        assertEquals(2019, completed.vintage)
        assertEquals(WineColor.Red, completed.color)
        assertEquals("Bourgogne", completed.region)
        assertEquals(listOf("Pinot Noir"), completed.grapes)
        assertEquals(2023, completed.drinkFrom)
        assertNotNull(completed.aiCompletedAt)
        // The region was typed on another device meanwhile: it is kept.
        val live = saved.copy(region = "Côte de Nuits")
        val updated = live.completed(from = saved, to = completed)
        assertEquals("Côte de Nuits", updated.region)
        assertEquals(WineColor.Red, updated.color)
        assertEquals(completed.aiCompletedAt, updated.aiCompletedAt)
        // Undo puts back what the AI filled, and only that.
        val undone = updated.copy(country = "France").completed(from = updated, to = live)
        assertNull(undone.color)
        assertEquals("Côte de Nuits", undone.region)
        assertEquals("France", undone.country)
        assertNull(undone.aiCompletedAt)
    }

    @Test
    fun whatWasTypedIsSavedWithoutThePhoto() {
        // Saved state outlives the app's process (ended while the camera app was open); the
        // photo, too big for it, is kept apart while the activity is recreated.
        val photo = WineFormPhoto(null)
        val form = WineFormState(null, 2026, photo)
        form.name = "Clos des Tilleuls"
        form.producer = "Domaine Martin"
        form.vintageText = "2019"
        form.color = WineColor.Red
        form.grapes.add("Pinot noir")
        form.tagInput = "gift"
        form.manualWindow = 2024..2032
        form.newPhoto = byteArrayOf(1, 2, 3)
        assertEquals(true, form.apply(LabelAnalysis(region = "Bourgogne")))
        val saver = WineFormState.saver(null, 2026, photo)
        val saved = with(saver) { SaverScope { true }.save(form) }!!
        val restored = saver.restore(saved)!!
        assertEquals(form.id, restored.id)
        assertEquals(form.snapshot(), restored.snapshot())
        assertEquals(3, restored.newPhoto?.size)
        // The process ended: the photo is gone, what was typed is not.
        val again = WineFormState.saver(null, 2026, WineFormPhoto(null)).restore(saved)!!
        assertEquals("Clos des Tilleuls", again.name)
        assertNull(again.newPhoto)
        // A region filled from the label may still be replaced by the next answer.
        again.apply(LabelAnalysis(region = "Côte de Nuits"))
        assertEquals("Côte de Nuits", again.region)
    }
}
