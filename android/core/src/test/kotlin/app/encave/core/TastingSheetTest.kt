package app.encave.core

import java.io.File
import java.math.BigDecimal
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A file of `fixtures/` shared with CaveKit (tests run in android/core). */
internal fun sharedFixture(name: String): JsonObject = Json.parseToJsonElement(File("../../fixtures/$name").readText()).jsonObject

/** JSON compared by value: numbers as decimals ("90" is "90.0"), object keys in any order. */
internal fun canonical(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.toSortedMap().mapValues { canonical(it.value) })
    is JsonArray -> JsonArray(element.map(::canonical))
    is JsonNull -> element
    is JsonPrimitive -> if (!element.isString && element.booleanOrNull == null) {
        JsonPrimitive(BigDecimal(element.content).stripTrailingZeros().toPlainString())
    } else element
}

private val JsonElement?.double: Double? get() = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.doubleOrNull
private val JsonElement?.int: Int? get() = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.intOrNull

/** The rating conversion from `fixtures/rating-scale-vectors.json`, as CaveKit's RatingScaleVectorTests. */
class RatingScaleVectorTest {
    private val vectors = sharedFixture("rating-scale-vectors.json")
    private fun rows(key: String): List<JsonElement> = vectors.getValue(key).jsonArray.also { assertTrue(it.isNotEmpty(), key) }
    private fun scale(name: JsonElement?): RatingScale = RatingScale.fromRaw(name?.jsonPrimitive?.content)!!

    @Test
    fun normalizedStarsAndStarScores() {
        for (pair in rows("normalized").map { it.jsonArray }) assertEquals(pair[1].double, Score.normalized(pair[0].double), "$pair")
        for (pair in rows("stars").map { it.jsonArray }) assertEquals(pair[1].int, Score.stars(pair[0].double!!), "$pair")
        for (pair in rows("starScores").map { it.jsonArray }) assertEquals(pair[1].double, Score.score100(pair[0].int!!), "$pair")
        assertNull(Score.normalized(Double.NaN))
        assertNull(Score.normalized(Double.POSITIVE_INFINITY))
    }

    @Test
    fun entered() {
        for (row in rows("entered").map { it.jsonObject }) {
            val result = row.getValue("result").jsonArray
            val entered = Score.entered(row["value"].double!!, scale(row["scale"]))
            assertEquals(result[0].int, entered?.rating, "$row")
            assertEquals(result[1].double, entered?.score100, "$row")
        }
    }

    @Test
    fun shownOnEveryScale() {
        for (row in rows("shown").map { it.jsonObject }) {
            val rating = row["rating"].int
            val score = row["score100"].double
            for (scale in RatingScale.entries) assertEquals(row[scale.raw].double, Score.shown(rating, score, scale), "$row $scale")
            assertEquals(row.getValue("fromStars").jsonPrimitive.booleanOrNull, Score.isFromStars(rating, score), "$row")
        }
    }

    @Test
    fun keptOnlyWithItsStars() {
        for (row in rows("kept").map { it.jsonObject }) assertEquals(row["result"].double, Score.kept(row["score100"].double, row["rating"].int), "$row")
    }

    @Test
    fun table() {
        for (row in rows("table").map { it.jsonObject }) {
            val score = Score.score100FromTwenty(row["twenty"].double!!)
            assertEquals(row["hundred"].double, score)
            assertEquals(row["stars"].int, Score.stars(score!!))
            assertEquals(row["twenty"].double, Score.twenty(score))
        }
    }

    @Test
    fun settingARatingKeepsBothInStep() {
        var tasting = Tasting(rating = 3).withRating(17.5, RatingScale.Twenty)
        assertEquals(4, tasting.rating)
        assertEquals(93.75, tasting.score100)
        tasting = tasting.withRating(4.0, RatingScale.Stars)
        assertEquals(4, tasting.rating)
        assertNull(tasting.score100)
        // A rating changed without its score: the score is stale when stored.
        tasting = tasting.withRating(90.0, RatingScale.Hundred).copy(rating = 2).normalized()
        assertNull(tasting.score100)
        tasting = tasting.withRating(null, RatingScale.Twenty)
        assertNull(tasting.rating)
        val discovery = Discovery(wineId = java.util.UUID.randomUUID(), rating = 1).withRating(16.0, RatingScale.Twenty)
        assertEquals(4, discovery.rating)
        assertEquals(90.0, discovery.score100)
    }
}

/** The code's vocabulary is `fixtures/tasting-vocabulary.json`, as CaveKit's VocabularyTests checks. */
class VocabularyTest {
    private val file = sharedFixture("tasting-vocabulary.json")
    private fun strings(element: JsonElement?): List<String>? = (element as? JsonArray)?.map { it.jsonPrimitive.content }
    private fun colors(element: JsonElement?): Set<WineColor> = strings(element).orEmpty().mapNotNull(WineColor::fromRaw).toSet()

    @Test
    fun tablesMatchTheSharedFile() {
        val families = file.getValue("aromaFamilies").jsonArray.map { it.jsonObject }
        assertEquals(families.map { it.getValue("id").jsonPrimitive.content }, Vocabulary.aromaFamilies.map { it.id })
        for ((expected, family) in families.zip(Vocabulary.aromaFamilies)) {
            val aromas = expected.getValue("aromas").jsonArray.map { it.jsonObject }
            assertEquals(aromas.map { it.getValue("id").jsonPrimitive.content }, family.aromas.map { it.id }, family.id)
            assertEquals(aromas.map { colors(it["colors"]) }, family.aromas.map { it.colors }, family.id)
        }
        val structure = file.getValue("structure").jsonArray.map { it.jsonObject }
        assertEquals(structure.map { it.getValue("id").jsonPrimitive.content }, Vocabulary.structure.map { it.id })
        assertEquals(structure.map { it.getValue("scale").jsonPrimitive.content }, Vocabulary.structure.map { it.scale })
        assertEquals(structure.map { it["colors"]?.let(::colors) }, Vocabulary.structure.map { it.colors })
        assertEquals(file.getValue("structureScales").jsonObject.mapValues { strings(it.value) }, Vocabulary.levels)
        assertEquals(strings(file["intensities"]), Vocabulary.intensities)
        val hues = file.getValue("hues").jsonArray.map { it.jsonObject }
        assertEquals(hues.map { it.getValue("id").jsonPrimitive.content }, Vocabulary.hues.map { it.id })
        assertEquals(hues.map { colors(it["colors"]) }, Vocabulary.hues.map { it.colors })
        assertEquals(strings(file["dosage"]), Vocabulary.dosages)
        assertEquals(strings(file["sweetness"]), Vocabulary.sweetnesses)
        assertEquals(strings(file["ageing"]), Vocabulary.ageings)
        assertEquals(strings(file["farming"]), Vocabulary.farmings)
        assertEquals(strings(file["closure"]), Vocabulary.closures)
        assertEquals(strings(file["classificationSuggestions"]), Vocabulary.classificationSuggestions)
    }

    @Test
    fun idsAreUniqueWithinAKind() {
        for (kind in Vocabulary.Kind.entries) {
            val ids = Vocabulary.ids(kind)
            assertTrue(ids.isNotEmpty() && ids.toSet().size == ids.size, "$kind")
        }
        assertTrue(Vocabulary.aromaIds.size in 35..60)
    }

    @Test
    fun offeredByColor() {
        val tannin = Vocabulary.structure.single { it.id == "tannin" }
        assertTrue(tannin.isOffered(WineColor.Red) && tannin.isOffered(null))
        assertFalse(tannin.isOffered(WineColor.White) || tannin.isOffered(WineColor.Sparkling))
        assertTrue(tannin.isOffered(WineColor.White, value = 2))
        val violet = Vocabulary.aromaFamilies.flatMap { it.aromas }.single { it.id == "floral.violet" }
        assertTrue(violet.suits(WineColor.Red) && !violet.suits(WineColor.White) && violet.suits(null))
        assertEquals("floral", Vocabulary.familyOfAroma("floral.violet")?.id)
        assertNull(Vocabulary.familyOfAroma("future.aroma"))
        assertEquals("mediumPlus", Vocabulary.level(4, tannin))
        assertNull(Vocabulary.level(0, tannin))
    }
}

/**
 * Reading and writing the guided tasting fields, the precise score and the wine details:
 * `fixtures/tasting-sheet-vectors.json`, as CaveKit's TastingSheetTests.
 */
class TastingSheetTest {
    private val vectors = sharedFixture("tasting-sheet-vectors.json")
    private val wineId = "00000000-0000-0000-0000-0000000000A1"
    private val bottleId = "00000000-0000-0000-0000-0000000000B1"
    private val wine = JsonObject(mapOf("id" to JsonPrimitive(wineId), "name" to JsonPrimitive("Clos Test"), "grapes" to JsonArray(emptyList())))
    private val now = Instant.ofEpochSecond(1_790_000_000)

    @AfterEach
    fun resetLabels() {
        Vocabulary.labelProvider = { _, _ -> null }
    }

    /** A cellar file holding the record, as the app reads it. */
    private fun cellarText(wine: JsonObject = this.wine, tasting: JsonObject? = null, discovery: JsonObject? = null): String {
        val bottles = if (tasting == null) JsonArray(emptyList()) else JsonArray(
            listOf(
                JsonPrimitive(bottleId),
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(bottleId), "wineID" to JsonPrimitive(wineId), "status" to JsonPrimitive("consumed"),
                        "addedAt" to JsonPrimitive(800_000_000), "sizeML" to JsonPrimitive(750), "tasting" to tasting,
                    ),
                ),
            ),
        )
        val discoveries = if (discovery == null) JsonArray(emptyList()) else JsonArray(listOf(discovery.getValue("id"), discovery))
        return JsonObject(
            mapOf(
                "wines" to JsonArray(listOf(JsonPrimitive(wineId), wine)), "bottles" to bottles, "racks" to JsonArray(emptyList()),
                "discoveries" to discoveries, "schemaVersion" to JsonPrimitive(2),
                "settings" to JsonObject(mapOf("currency" to JsonPrimitive("EUR"))),
            ),
        ).toString()
    }

    /** The record of a kind, as the cellar writes it back. */
    private fun written(cellar: Cellar, kind: String): JsonElement {
        val root = Json.parseToJsonElement(CellarJson.encode(cellar)).jsonObject
        return when (kind) {
            "wines" -> root.getValue("wines").jsonArray.last()
            "discoveries" -> root.getValue("discoveries").jsonArray.last()
            else -> root.getValue("bottles").jsonArray.last().jsonObject.getValue("tasting")
        }
    }

    private fun cellarFor(kind: String, record: JsonObject): Cellar = CellarJson.decode(
        when (kind) {
            "tastings" -> cellarText(tasting = record)
            "wines" -> cellarText(wine = record)
            else -> cellarText(discovery = record)
        },
    )

    @Test
    fun recordsAreReadTheSameWayEverywhere() {
        for (kind in listOf("tastings", "wines", "discoveries")) {
            val cases = vectors.getValue(kind).jsonArray.map { it.jsonObject }
            assertTrue(cases.isNotEmpty(), kind)
            for (item in cases) {
                val about = item.getValue("about").jsonPrimitive.content
                val cellar = cellarFor(kind, item.getValue("input").jsonObject)
                assertEquals(canonical(item.getValue("read")), canonical(written(cellar, kind)), about)
                // What is written reads back the same.
                assertEquals(cellar, CellarJson.decode(CellarJson.encode(cellar)), about)
            }
        }
    }

    /**
     * A version that knows only the previous keys keeps the new ones and writes them back
     * unchanged; after its edit, this version reads what the vectors say.
     */
    @Test
    fun anOlderVersionKeepsTheNewKeys() {
        val old = vectors.getValue("oldVersion").jsonObject
        val keys = old.getValue("keys").jsonObject.mapValues { (_, list) -> list.jsonArray.map { it.jsonPrimitive.content }.toSet() }
        for ((name, kind) in listOf("tasting" to "tastings", "wine" to "wines", "discovery" to "discoveries")) {
            val item = old.getValue(name).jsonObject
            val record = item.getValue("record").jsonObject
            val known = keys.getValue(name)
            val oldRecord = OldRecord(record, known)
            assertTrue(oldRecord.unknown.isNotEmpty(), "$name has new keys")
            assertEquals(record.keys - known, oldRecord.unknown.keys)
            assertEquals(canonical(record), canonical(oldRecord.written()), name)
            val edited = oldRecord.edited(item.getValue("edit").jsonObject).written()
            assertEquals(canonical(item.getValue("afterEdit")), canonical(written(cellarFor(kind, edited), kind)), name)
        }
    }

    @Test
    fun formEditsKeepTheGuidedFields() {
        val cellar = SampleData.cellar(year = 2026, now = now)
        val bottle = cellar.consumedBottles.first { it.tasting?.aromas != null }
        cellar.updateTasting(bottle.id, bottle.tasting!!.copy(note = "Edited"))
        val stored = cellar.bottles.getValue(bottle.id).tasting!!
        assertEquals("Edited", stored.note)
        assertEquals(bottle.tasting!!.aromas, stored.aromas)
        assertEquals(95.0, stored.score100)
        assertEquals(4, stored.acidity)
        assertEquals("ruby", stored.hue)
    }

    @Test
    fun searchFindsTheNewFieldsByTheirLabels() {
        val french = mapOf(
            "aroma/fruit.redCherry" to "Cerise rouge", "aroma/fruit.raspberry" to "Framboise", "farming/organic" to "Agriculture biologique",
            "closure/naturalCork" to "Bouchon de liège", "ageing/oakBarrel" to "Fût de chêne", "hue/ruby" to "Rubis",
        )
        Vocabulary.labelProvider = { kind, id -> french["${kind.raw}/$id"] }
        val cellar = SampleData.cellar(year = 2026, now = now)
        assertEquals(1, cellar.diary(query = "cerise").size)
        assertEquals(1, cellar.diary(query = "rubis").size)
        assertEquals(1, cellar.diary(query = "framboise").size)
        assertTrue(cellar.diary(query = "groseille").isEmpty())
        val wines = { query: String -> cellar.wines.values.filter { it.matches(query) }.map { it.name }.toSet() }
        assertEquals(setOf("Clos des Tilleuls"), wines("biologique"))
        assertEquals(setOf("Clos des Tilleuls"), wines("Cru Bourgeois"))
        assertEquals(setOf("Clos des Tilleuls"), wines("liège"))
        assertEquals(setOf("Clos des Tilleuls"), wines("fût"))
        assertEquals(setOf("Clos des Tilleuls"), wines("Concours Pauillac"))
    }

    @Test
    fun statisticsCountAromasFarmingAndClosure() {
        val cellar = SampleData.cellar(year = 2026, now = now)
        val stats = cellar.statistics(2026, now)
        assertEquals(listOf("earth.forestFloor", "floral.violet", "fruit.raspberry", "fruit.redCherry", "spice.clove"), stats.topAromas.map { it.label })
        assertTrue(stats.topAromas.all { it.count == 1 })
        val flagship = cellar.bottlesInCellar.count { cellar.wines[it.wineId]?.farming == "organic" }
        assertTrue(flagship > 0)
        assertEquals(listOf(CellarStatistics.Slice("organic", flagship)), stats.byFarming)
        assertEquals(listOf(CellarStatistics.Slice("naturalCork", flagship)), stats.byClosure)
    }

    @Test
    fun mergingDuplicatesKeepsCriticsAndDetails() {
        val target = Wine(name = "A", grapes = listOf("Syrah"), criticScores = listOf(CriticScore("X", score = BigDecimal(90), scale = 100)))
        val source = Wine(
            name = "A", grapes = listOf("Syrah"), abv = BigDecimal(14), farming = "organic", farmingCertified = true,
            grapeShares = mapOf("Syrah" to 100),
            criticScores = listOf(CriticScore("X", score = BigDecimal(90), scale = 100), CriticScore("Y", award = "Gold")),
        )
        val cellar = Cellar(listOf(target, source))
        cellar.mergeWine(source.id, target.id)
        val merged = cellar.wines.getValue(target.id)
        assertEquals(listOf("X", "Y"), merged.criticScores?.map { it.source })
        assertEquals(BigDecimal(14), merged.abv)
        assertEquals("organic", merged.farming)
        assertEquals(true, merged.farmingCertified)
        assertEquals(mapOf("Syrah" to 100), merged.grapeShares)
    }

    @Test
    fun alcoholIsStoredWithoutTrailingZeros() {
        // As DecimalSerializer reads it, so a form saving "20" equals the stored value.
        val wine = Wine(name = "Port", abv = BigDecimal("20.0")).normalized()
        assertEquals(BigDecimal("20").stripTrailingZeros(), wine.abv)
        assertEquals(wine, CellarJson.decode(CellarJson.encode(Cellar(listOf(wine)))).wines.getValue(wine.id))
    }

    @Test
    fun wineDetailsHelpers() {
        val wine = Wine(name = "X", grapes = listOf("Syrah", "Grenache"))
        assertFalse(wine.hasMoreDetails)
        val shared = wine.copy(grapeShares = mapOf("syrah" to 60, "Grenache" to 40, "Mourvèdre" to 10)).normalized()
        assertEquals(mapOf("syrah" to 60, "Grenache" to 40), shared.grapeShares)
        assertEquals(60, shared.share(" SYRAH "))
        // Removing a grape drops its share.
        assertEquals(mapOf("syrah" to 60), shared.copy(grapes = listOf("Syrah")).normalized().grapeShares)
        val aged = wine.copy(ageing = listOf("amphora", "amphora"), criticScores = emptyList()).normalized()
        assertEquals(listOf("amphora"), aged.ageing)
        assertNull(aged.criticScores)
        assertTrue(aged.hasMoreDetails)
        assertEquals("17.5/20", CriticScore("A", score = BigDecimal("17.5"), scale = 20).value)
        assertEquals(93.75, CriticScore("A", score = BigDecimal("17.5"), scale = 20).score100)
        assertEquals(94.0, CriticScore("A", score = BigDecimal(94), scale = 100).score100)
        assertEquals("Gold", CriticScore("A", award = " Gold ").value)
        assertNull(CriticScore("A", score = BigDecimal(8), scale = 10).score100)
    }
}

/**
 * A record as a version that knows only [known] keys holds it: those values, and the others
 * kept as unknown keys and written back unchanged (docs/SYNC.md §1).
 */
private class OldRecord(record: JsonObject, private val known: Set<String>) {
    var values: Map<String, JsonElement> = record.filterKeys { it in known }
    val unknown: Map<String, JsonElement> = record.filterKeys { it !in known }

    fun edited(edit: JsonObject): OldRecord = also { values = values + edit }

    fun written(): JsonObject = JsonObject(values + unknown.filterKeys { it !in values })
}
