package app.encave.core

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The details marked "Estimated by AI", from `fixtures/ai-label-answer-vectors.json`, as CaveKit's LabelSourcesTests. */
class LabelSourcesTest {
    @Test
    fun answers() {
        val answers = sharedFixture("ai-label-answer-vectors.json").getValue("answers").jsonArray
        assertTrue(answers.isNotEmpty())
        for (row in answers.map { it.jsonObject }) {
            val expected = row.getValue("estimated").jsonArray.map { it.jsonPrimitive.content }.toSet()
            assertEquals(expected, AiLabelSources.estimated(row.getValue("answer").jsonPrimitive.content), row["note"].toString())
        }
    }
}
