package app.encave.core

import java.math.BigDecimal
import java.time.Instant
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The AI's "expected" answer and what is sent for it, as CaveKit's ExpectedRequestTests. */
class ExpectedRequestTest {
    @Test
    fun answerVectors() {
        val answers = sharedFixture("expected-profile-vectors.json").getValue("answers").jsonArray.map { it.jsonObject }
        assertTrue(answers.isNotEmpty())
        for (row in answers) {
            val profile = ExpectedRequest.profileFromAnswer(row.getValue("answer").jsonPrimitive.content, "ai", "m", "fr", Instant.EPOCH)
            val expected = row["profile"]
            if (expected == null || expected is JsonNull) {
                assertNull(profile, row["note"].toString())
                continue
            }
            val written = CellarJson.json.encodeToJsonElement(ExpectedProfile.serializer(), profile!!).jsonObject
            assertEquals(canonical(expected), canonical(JsonObject(written - "date")), row["note"].toString())
        }
    }

    /** From the identity alone: producer, name, appellation, vintage, colour and grapes, nothing else. */
    @Test
    fun identityRequestSendsOnlyTheIdentity() {
        val wine = Wine(
            name = "Les Galets", producer = "Domaine Martin", vintage = 2021, color = WineColor.White, region = "Bourgogne",
            country = "France", appellation = "Chablis", grapes = listOf("Chardonnay"), notes = "Secret note", photoId = "sha256-x",
            currentPrice = BigDecimal(42), currentPriceCurrency = "EUR", tags = listOf("Gift from Anna"),
        )
        val prompt = ExpectedRequest.prompt(WineIdentity(wine), "fr")
        for (sent in listOf("Producer: Domaine Martin", "Name: Les Galets", "Appellation: Chablis", "Vintage: 2021", "Colour: white", "Grapes: Chardonnay", "in French")) {
            assertTrue(sent in prompt, sent)
        }
        for (kept in listOf("Secret note", "42", "EUR", "Gift", "sha256", "Bourgogne", "France")) assertFalse(kept in prompt, kept)
        assertEquals(listOf("expected"), ExpectedRequest.answerSchema.getValue("required").jsonArray.map { it.jsonPrimitive.content })
    }
}
