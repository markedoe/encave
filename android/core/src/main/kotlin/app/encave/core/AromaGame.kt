package app.encave.core

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * "Guess the aromas": the player picks aromas (and, if they like, structure levels) before
 * seeing what to expect, then gets points. Games stay on the device that played: never
 * synced, not in the cellar file, backups or exports ([AromaGameStore]). As CaveKit's
 * `AromaGame` (`fixtures/aroma-game-vectors.json`).
 */
object AromaGame {
    const val MAX_PICKS = 6
    const val EXACT_POINTS = 10
    const val FAMILY_POINTS = 4
    const val STRUCTURE_POINTS = 5

    /**
     * Each picked aroma (the first [MAX_PICKS], each once) that is expected gets 10; then each
     * other pick whose family has an expected aroma not found yet gets 4 (one expected aroma
     * per pick, in the order picked); each structure level guessed within one level of the
     * expected one gets 5. Accuracy: the points out of 10 per expected aroma and 5 per
     * expected level guessed, as a whole percentage (halves up).
     */
    fun score(guesses: List<String>, structure: Map<String, Int>, expected: List<String>, expectedStructure: Map<String, Int>): AromaGameScore {
        val picks = guesses.filter { it.isNotEmpty() }.distinct().take(MAX_PICKS)
        val remaining = expected.filter { it.isNotEmpty() }.distinct().toMutableList()
        val expectedCount = remaining.size
        val exact = mutableListOf<String>()
        val family = mutableListOf<Pair<String, String>>()
        val wrong = mutableListOf<String>()
        for (pick in picks) {
            if (remaining.remove(pick)) exact += pick
        }
        for (pick in picks.filter { it !in exact }) {
            val familyId = Vocabulary.familyOfAroma(pick)?.id
            val index = if (familyId == null) -1 else remaining.indexOfFirst { Vocabulary.familyOfAroma(it)?.id == familyId }
            if (index >= 0) family += pick to remaining.removeAt(index) else wrong += pick
        }
        val levels = Vocabulary.structure.map { it.id }.mapNotNull { attribute ->
            val guess = structure[attribute] ?: return@mapNotNull null
            val target = expectedStructure[attribute] ?: return@mapNotNull null
            AromaGameScore.LevelMatch(attribute, guess, target)
        }
        val points = exact.size * EXACT_POINTS + family.size * FAMILY_POINTS + levels.count { it.isClose } * STRUCTURE_POINTS
        val maxPoints = expectedCount * EXACT_POINTS + levels.size * STRUCTURE_POINTS
        val accuracy = if (maxPoints > 0) (points * 100 + maxPoints / 2) / maxPoints else 0
        return AromaGameScore(points, maxPoints, accuracy, exact, family, remaining.toList(), wrong, levels)
    }
}

data class AromaGameScore(
    val points: Int,
    val maxPoints: Int,
    /** 0…100. */
    val accuracy: Int,
    /** Picked and expected. */
    val exact: List<String>,
    /** Picked (first), of the family of an expected aroma (second). */
    val family: List<Pair<String, String>>,
    /** Expected, neither picked nor matched by family. */
    val missed: List<String>,
    /** Picked, no credit. */
    val wrong: List<String>,
    val levels: List<LevelMatch>,
) {
    data class LevelMatch(val attribute: String, val guess: Int, val expected: Int) {
        /** Within one level: 5 points. */
        val isClose: Boolean get() = kotlin.math.abs(guess - expected) <= 1
    }
}

/** A game played, kept on this device only. */
@Serializable
data class AromaGameRecord(
    @Serializable(with = UuidSerializer::class) val id: UUID = UUID.randomUUID(),
    @Serializable(with = UuidSerializer::class) val wineId: UUID,
    @Serializable(with = UuidSerializer::class) val bottleId: UUID? = null,
    @Serializable(with = InstantSerializer::class) val date: Instant,
    val guesses: List<String>,
    val structure: Map<String, Int> = emptyMap(),
    val expected: List<String>,
    val expectedStructure: Map<String, Int> = emptyMap(),
) {
    val score: AromaGameScore get() = AromaGame.score(guesses, structure, expected, expectedStructure)
}

/** "My palate": what the games played on this device add up to. As CaveKit's `Palate`. */
data class Palate(
    val games: Int,
    val points: Int,
    val level: Level,
    /** All points out of all possible points, 0…100 (halves up); null before a game. */
    val accuracy: Int?,
    /** Days in a row with a game, ending today or yesterday. */
    val streak: Int,
    /** The aroma families found best and worst, among those expected at least twice. */
    val bestFamily: String?,
    val weakestFamily: String?,
    /** The latest games, newest first (at most 5). */
    val recent: List<AromaGameRecord>,
) {
    enum class Level(val raw: String, val nextAt: Int?) {
        Novice("novice", 100), Amateur("amateur", 500), Connoisseur("connoisseur", 1500), Sommelier("sommelier", null);

        companion object {
            /** The level reached with these points: 0, 100, 500 and 1500. */
            fun of(points: Int): Level = when {
                points < 100 -> Novice
                points < 500 -> Amateur
                points < 1500 -> Connoisseur
                else -> Sommelier
            }
        }
    }

    companion object {
        fun of(records: List<AromaGameRecord>, today: Instant, zone: ZoneId = ZoneId.systemDefault()): Palate {
            val scores = records.map { it to it.score }
            val points = scores.sumOf { it.second.points }
            val possible = scores.sumOf { it.second.maxPoints }
            val days = records.map { LocalDate.ofInstant(it.date, zone) }.toSet()
            var day = LocalDate.ofInstant(today, zone)
            if (day !in days) day = day.minusDays(1)
            var streak = 0
            while (day in days) {
                streak++
                day = day.minusDays(1)
            }
            val expectedCount = mutableMapOf<String, Int>()
            val found = mutableMapOf<String, Int>()
            for ((record, score) in scores) {
                val exact = score.exact.toSet()
                for (aroma in record.expected.toSet()) {
                    val family = Vocabulary.familyOfAroma(aroma)?.id ?: continue
                    expectedCount[family] = (expectedCount[family] ?: 0) + 1
                    if (aroma in exact) found[family] = (found[family] ?: 0) + 1
                }
            }
            val order = Vocabulary.aromaFamilies.map { it.id }
            data class Rated(val id: String, val rate: Double, val count: Int)
            val rated = order.mapNotNull { family ->
                val count = expectedCount[family]?.takeIf { it >= 2 } ?: return@mapNotNull null
                Rated(family, (found[family] ?: 0).toDouble() / count, count)
            }
            // Higher rate first; then more often expected; then the vocabulary's order.
            val best = rated.minWithOrNull(compareBy<Rated>({ -it.rate }, { -it.count }, { order.indexOf(it.id) }))
            val weakest = rated.minWithOrNull(compareBy<Rated>({ it.rate }, { -it.count }, { order.indexOf(it.id) }))
            return Palate(
                games = records.size, points = points, level = Level.of(points),
                accuracy = if (possible > 0) (points * 100 + possible / 2) / possible else null,
                streak = streak, bestFamily = best?.id, weakestFamily = weakest?.id?.takeIf { it != best?.id },
                recent = records.sortedByDescending { it.date }.take(5),
            )
        }
    }
}

/**
 * Where the games are kept: a file of this device, never in the cellar, sync, backups or
 * exports (and left out of Android's backups and device transfer). The latest 500 games.
 */
class AromaGameStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(AromaGameRecord.serializer())

    fun load(): List<AromaGameRecord> =
        runCatching { if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyList() }.getOrDefault(emptyList())

    fun add(record: AromaGameRecord) = save((load() + record).takeLast(LIMIT))

    fun removeAll() {
        file.delete()
    }

    private fun save(records: List<AromaGameRecord>) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(json.encodeToString(serializer, records))
        if (!temporary.renameTo(file)) {
            file.writeText(temporary.readText())
            temporary.delete()
        }
    }

    companion object {
        const val LIMIT = 500
    }
}
