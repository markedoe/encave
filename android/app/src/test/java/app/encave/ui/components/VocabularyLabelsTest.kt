package app.encave.ui.components

import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import app.encave.core.CriticScore
import app.encave.core.RatingScale
import app.encave.core.Vocabulary
import app.encave.core.Wine
import app.encave.ui.cellar.CriticScoreTexts
import app.encave.ui.cellar.CriticKind
import app.encave.ui.cellar.criticScore
import app.encave.ui.cellar.wineDetailRows
import java.math.BigDecimal
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every id of the shared vocabulary has a label in English and French; ratings and critics' scores read well in both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class VocabularyLabelsTest {
    private fun resources(language: String): Resources {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        return context.createConfigurationContext(configuration).resources
    }

    @Test
    fun everyIdOfEveryKindHasALabelInBothLanguages() {
        val english = resources("en")
        val french = resources("fr")
        Vocabulary.Kind.entries.forEach { kind ->
            Vocabulary.ids(kind).forEach { id ->
                assertNotNull("$kind $id has no label", VocabularyLabels.res(kind, id))
                val en = VocabularyLabels.label(english, kind, id)
                val fr = VocabularyLabels.label(french, kind, id)
                assertNotEquals("$kind $id shows its id", id, en.takeIf { it.contains('.') })
                assertTrue(en.isNotBlank() && fr.isNotBlank())
            }
        }
        assertEquals("Red cherry", VocabularyLabels.label(english, Vocabulary.Kind.Aroma, "fruit.redCherry"))
        assertEquals("Cerise rouge", VocabularyLabels.label(french, Vocabulary.Kind.Aroma, "fruit.redCherry"))
        // The same id means different things by kind, and an unknown id shows as it is.
        assertEquals("Moyenne", VocabularyLabels.label(french, Vocabulary.Kind.Intensity, "medium"))
        assertEquals("future.aroma", VocabularyLabels.label(french, Vocabulary.Kind.Aroma, "future.aroma"))
    }

    @Test
    fun levelWordsAgreeWithTheirAttributeInFrench() {
        val french = resources("fr")
        fun attribute(id: String) = Vocabulary.structure.first { it.id == id }
        assertEquals("Élevée", VocabularyLabels.level(french, attribute("acidity"), 5))
        assertEquals("Élevé", VocabularyLabels.level(french, attribute("alcohol"), 5))
        assertEquals("Longue", VocabularyLabels.level(french, attribute("length"), 5))
        assertEquals("Léger", VocabularyLabels.level(french, attribute("body"), 1))
        assertEquals("Sec", VocabularyLabels.level(french, attribute("sweetness"), 1))
        assertEquals("High", VocabularyLabels.level(resources("en"), attribute("acidity"), 5))
    }

    @Test
    fun theCoreSearchMatchesLabelsInThePersonsLanguage() {
        val previous = Vocabulary.labelProvider
        try {
            VocabularyLabels.install(ApplicationProvider.getApplicationContext())
            val wine = Wine(name = "Clos", closure = "screwCap")
            assertTrue(wine.matches("screw cap"))
        } finally {
            Vocabulary.labelProvider = previous
        }
    }

    @Test
    fun ratingsReadOnEachScale() {
        val english = resources("en")
        val french = resources("fr")
        assertNull(RatingTexts.score(english, 4, null, RatingScale.Stars))
        assertEquals("17.5/20", RatingTexts.score(english, 4, 93.75, RatingScale.Twenty))
        assertEquals("17,5/20", RatingTexts.score(french, 4, 93.75, RatingScale.Twenty, ))
        assertEquals("95/100", RatingTexts.score(english, 5, 95.0, RatingScale.Hundred))
        // A rating in stars shows as an approximate score.
        assertEquals("≈ 90/100", RatingTexts.score(english, 4, null, RatingScale.Hundred))
        assertEquals("17.5 out of 20", RatingTexts.spoken(english, 4, 93.75, RatingScale.Twenty))
        assertEquals("environ 90 sur 100", RatingTexts.spoken(french, 4, null, RatingScale.Hundred))
        assertEquals("4 stars", RatingTexts.spoken(english, 4, 93.75, RatingScale.Stars))
        assertNull(RatingTexts.spoken(english, null, null, RatingScale.Twenty))
    }

    @Test
    fun criticsScoresReadInThePersonsLanguage() {
        val english = resources("en")
        val score = CriticScore("Cellar Review", score = BigDecimal("17.5"), scale = 20, note = "Tasted from barrel")
        assertEquals("17.5/20 · Tasted from barrel", CriticScoreTexts.line(score, Locale.ENGLISH))
        assertEquals("17,5/20 · Tasted from barrel", CriticScoreTexts.line(score, Locale.FRENCH))
        assertEquals("Cellar Review, 17.5 out of 20, Tasted from barrel", CriticScoreTexts.spoken(english, score, Locale.ENGLISH))
        assertEquals("Gold medal", CriticScoreTexts.line(CriticScore("Concours", award = "Gold medal"), Locale.ENGLISH))
    }

    @Test
    fun theDialogKeepsWhatANewerVersionAddedAndChecksTheScore() {
        val stored = CriticScore("Guide", score = BigDecimal(8), scale = 10)
        // Another scale, kept while no kind is chosen.
        assertEquals(stored.copy(score = BigDecimal(9)), criticScore(stored, "Guide", null, "9", ""))
        assertNull(criticScore(stored, "Guide", null, "11", ""))
        assertEquals(BigDecimal("93"), criticScore(null, " Review ", CriticKind.Hundred, "93", "")?.score)
        assertEquals("Review", criticScore(null, " Review ", CriticKind.Hundred, "93", "")?.source)
        assertNull(criticScore(null, "Review", CriticKind.Twenty, "21", ""))
        assertNull(criticScore(null, "", CriticKind.Twenty, "15", ""))
        val award = criticScore(stored, "Concours", CriticKind.Award, "Gold medal", " 2015 ")!!
        assertNull(award.score)
        assertNull(award.scale)
        assertEquals("2015", award.note)
    }

    @Test
    fun theWinePageListsTheDetailsInWords() {
        val french = resources("fr")
        val wine = Wine(
            name = "Clos", grapes = listOf("Cabernet Sauvignon", "Merlot"), abv = BigDecimal("13.5"),
            grapeShares = mapOf("Cabernet Sauvignon" to 70, "Merlot" to 30), ageing = listOf("oakBarrel", "newOak"),
            farming = "organic", farmingCertified = true, closure = "naturalCork",
        )
        val rows = wineDetailRows(wine, french, Locale.FRENCH).map { french.getString(it.first) to it.second }.toMap()
        assertEquals("13,5 %", rows["Alcool"]?.replace(' ', ' '))
        assertEquals("Fût de chêne, Fût neuf", rows["Élevage"])
        assertEquals("Biologique, certifiée", rows["Viticulture"])
        assertEquals("Liège naturel", rows["Bouchage"])
        assertTrue(rows.getValue("Part des cépages").startsWith("Cabernet Sauvignon 70"))
    }
}
