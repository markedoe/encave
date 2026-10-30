package app.encave.core

import java.text.Normalizer
import java.time.Instant
import java.util.Locale
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * What to expect from a wine when tasting it: aromas, structure, serving and pairings.
 * Written as "expectedProfile" on the wine (docs/SYNC.md §1), from the bundled profiles
 * ([ExpectedProfiles]), the AI (from the label photo, or from the wine's identity only) or
 * the AI with web search. As CaveKit's `ExpectedProfile` (`fixtures/expected-profile-vectors.json`).
 */
@KeepGeneratedSerializer
@Serializable(with = ExpectedProfileSerializer::class)
data class ExpectedProfile(
    /** Aroma ids of [Vocabulary], each once, in order. Ids this version does not know are kept. */
    val aromas: List<String>? = null,
    /** Levels 1 to 5 by structure attribute id ("acidity"); null when none. */
    val structure: Map<String, Int>? = null,
    /** Serving temperature, in degrees Celsius (0…30). */
    val servingMin: Int? = null,
    val servingMax: Int? = null,
    /** Minutes of decanting (0: none), up to a day. */
    val decantMinutes: Int? = null,
    /** [DishCategory] ids, each once. Ids this version does not know are kept. */
    val pairings: List<String>? = null,
    /** A sentence or two from the AI, in [language]; bundled profiles have their note in each language. */
    val text: String? = null,
    /** Language of [text] ("en", "fr"). */
    val language: String? = null,
    /** "bundled", "ai" or "web"; another value from a newer version is kept. */
    val source: String = "",
    /** The model that wrote it (ai, web). */
    val model: String? = null,
    /** The bundled profile's id ("pinotNoir"). */
    @SerialName("bundled") val bundledId: String? = null,
    /** When it was made. */
    @Serializable(with = InstantSerializer::class) val date: Instant? = null,
    /** Keys written by a newer version, kept and written back unchanged (docs/SYNC.md §1). */
    @Transient val unknownKeys: JsonObject = NoUnknownKeys,
) {
    /**
     * The same on every device: ids once and not empty, levels in 1…5, temperatures in
     * 0…30 (the lower first), decanting in 0…1440, empty texts, lists and objects left out.
     */
    fun normalized(): ExpectedProfile {
        val first = servingMin?.takeIf { it in 0..30 }
        val second = servingMax?.takeIf { it in 0..30 }
        val swapped = first != null && second != null && first > second
        val low = if (swapped) second else first
        val high = if (swapped) first else second
        val levels = structure.orEmpty().filterKeys { it.isNotEmpty() }.mapValues { it.value.coerceIn(1, 5) }
        return copy(
            aromas = Tasting.uniqueIds(aromas), pairings = Tasting.uniqueIds(pairings),
            structure = levels.ifEmpty { null }, servingMin = low, servingMax = high,
            decantMinutes = decantMinutes?.takeIf { it in 0..1440 },
            text = text?.ifEmpty { null }, language = language?.ifEmpty { null }, model = model?.ifEmpty { null },
            bundledId = bundledId?.ifEmpty { null },
        )
    }

    /** Nothing to show: no aromas, structure, serving, pairings or text. */
    val isEmpty: Boolean
        get() = aromas == null && structure == null && servingMin == null && servingMax == null && decantMinutes == null &&
            pairings == null && text == null

    /** The structure level of an attribute (1…5). */
    fun level(attribute: String): Int? = structure?.get(attribute)

    /** Aromas compared with those found (a tasting, a guess). */
    fun comparison(found: List<String>): AromaComparison = AromaComparison.of(aromas.orEmpty(), found)

    companion object {
        const val BUNDLED = "bundled"
        const val AI = "ai"
        const val WEB = "web"
    }
}

/** Keeps the keys of a newer version (see [KeepingUnknownKeys]), read as CaveKit reads it. */
object ExpectedProfileSerializer : KeepingUnknownKeys<ExpectedProfile>(ExpectedProfile.generatedSerializer()) {
    override fun unknownKeys(value: ExpectedProfile) = value.unknownKeys
    override fun withUnknownKeys(value: ExpectedProfile, keys: JsonObject) = value.copy(unknownKeys = keys)
    override fun deserialize(decoder: Decoder): ExpectedProfile = super.deserialize(decoder).normalized()
}

/** The expected aromas and those found: "You found 4 of 6 expected aromas", and the others found. */
data class AromaComparison(
    /** Expected and found, in the expected order. */
    val matched: List<String>,
    /** Expected, not found. */
    val missed: List<String>,
    /** Found, not expected, in the order found. */
    val extra: List<String>,
) {
    val expectedCount: Int get() = matched.size + missed.size

    companion object {
        fun of(expected: List<String>, found: List<String>): AromaComparison {
            val unique = expected.distinct()
            val foundSet = found.toSet()
            val expectedSet = unique.toSet()
            return AromaComparison(unique.filter { it in foundSet }, unique.filter { it !in foundSet }, found.distinct().filter { it !in expectedSet })
        }
    }
}

/**
 * The profiles bundled with the app, by appellation or region, blend, grape or color
 * (`fixtures/expected-profiles.json`, embedded as it is). Offline: nothing is sent. As CaveKit's `ExpectedProfiles`.
 */
object ExpectedProfiles {
    @Serializable
    data class Entry(
        val id: String,
        val appellations: List<String>? = null,
        val grapes: List<List<String>>? = null,
        val colors: List<String>? = null,
        val fallback: Boolean? = null,
        val aromas: List<String>,
        val structure: Map<String, Int>,
        val servingMin: Int,
        val servingMax: Int,
        val decantMinutes: Int,
        val pairings: List<String>,
        /** The note by language ("en", "fr"). */
        val note: Map<String, String>,
    ) {
        fun suits(color: WineColor?): Boolean = color == null || colors.isNullOrEmpty() || color.raw in colors
    }

    @Serializable
    private data class File(val profiles: List<Entry>)

    val entries: List<Entry> by lazy {
        Json { ignoreUnknownKeys = true }.decodeFromString(File.serializer(), expectedProfilesJson).profiles
    }

    fun entry(id: String): Entry? = entries.firstOrNull { it.id == id }

    /** The note of a bundled profile in a language, English otherwise. */
    fun note(id: String, language: String): String? = entry(id)?.let { it.note[language] ?: it.note["en"] }

    /** Accents removed, lower case, anything but letters and digits as one space, trimmed. */
    fun normalized(text: String): String {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
        val out = StringBuilder()
        var pendingSpace = false
        for (character in folded) {
            if (character.isLetterOrDigit()) {
                if (pendingSpace && out.isNotEmpty()) out.append(' ')
                pendingSpace = false
                out.append(character)
            } else {
                pendingSpace = true
            }
        }
        return out.toString()
    }

    /** The name is this one, or a longer one starting with it and a space. */
    private fun matches(name: String, listed: List<String>): Boolean = name.isNotEmpty() && listed.any { name == it || name.startsWith("$it ") }

    /** The wine's main grape: the largest share, else the first listed. */
    internal fun mainGrape(wine: Wine): String? {
        var best = wine.grapes.firstOrNull() ?: return null
        var bestShare = wine.share(best) ?: 0
        for (grape in wine.grapes.drop(1)) {
            val share = wine.share(grape) ?: 0
            if (share > bestShare) {
                best = grape
                bestShare = share
            }
        }
        return best
    }

    /** The first match: appellation, then region, then a blend, then the main grape, then the color. */
    fun entry(wine: Wine): Entry? {
        val candidates = entries.filter { it.suits(wine.color) }
        for (name in listOf(normalized(wine.appellation.orEmpty()), normalized(wine.region.orEmpty()))) {
            if (name.isEmpty()) continue
            candidates.firstOrNull { matches(name, it.appellations.orEmpty()) }?.let { return it }
        }
        val grapes = wine.grapes.map(::normalized).filter { it.isNotEmpty() }.toSet()
        if (grapes.size >= 2) {
            candidates.firstOrNull { entry ->
                val groups = entry.grapes ?: return@firstOrNull false
                groups.size >= 2 && groups.all { group -> group.any { it in grapes } }
            }?.let { return it }
        }
        val main = mainGrape(wine)?.let(::normalized)
        if (!main.isNullOrEmpty()) {
            candidates.firstOrNull { entry -> entry.grapes?.size == 1 && main in entry.grapes[0] }?.let { return it }
        }
        val color = wine.color ?: return null
        return candidates.firstOrNull { it.fallback == true && it.colors?.contains(color.raw) == true }
    }

    /** The bundled profile for a wine, as stored on a wine (without its note, given by language). */
    fun profile(wine: Wine): ExpectedProfile? = entry(wine)?.let(::profile)

    fun profile(entry: Entry): ExpectedProfile = ExpectedProfile(
        aromas = entry.aromas, structure = entry.structure, servingMin = entry.servingMin, servingMax = entry.servingMax,
        decantMinutes = entry.decantMinutes, pairings = entry.pairings, source = ExpectedProfile.BUNDLED, bundledId = entry.id,
    ).normalized()
}

/** What to expect: the profile kept with the wine, else the bundled one for its appellation, grapes or color. */
val Wine.expectedOrBundledProfile: ExpectedProfile?
    get() = expectedProfile?.takeUnless { it.isEmpty } ?: ExpectedProfiles.profile(this)
