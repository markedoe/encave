package app.encave.core

import java.time.LocalDate
import java.util.Locale

/** A line of text read on a label, with its relative size (taller = more prominent). */
data class LabelLine(val text: String, val height: Double)

/** What could be read on a label. */
data class LabelScanResult(
    val name: String? = null,
    val producer: String? = null,
    val vintage: Int? = null,
    val appellation: String? = null,
) {
    val isEmpty: Boolean get() = name == null && producer == null && vintage == null
}

/**
 * Turns recognized label text into wine fields with simple, predictable rules.
 * Runs entirely on device; nothing is sent anywhere.
 */
object LabelTextParser {
    internal val producerWords = listOf(
        "château", "chateau", "domaine", "maison", "clos", "cave", "caves", "weingut",
        "bodega", "bodegas", "tenuta", "cantina", "quinta", "estate", "winery", "vignoble", "mas",
    )
    internal val appellationWords = listOf(
        "appellation", "contrôlée", "controlee", "aoc", "aop", "doc", "docg", "igp", "dop", "grand cru", "premier cru", "1er cru",
    )
    internal val noiseWords = listOf(
        "mis en bouteille", "bottled", "product of", "produit de", "contains sulfites", "contient des sulfites",
        "alc.", "% vol", "%vol", "www.", "http", "tel", "tél",
    )

    private fun String.lower() = lowercase(Locale.ROOT)

    fun parse(lines: List<LabelLine>, currentYear: Int = LocalDate.now().year): LabelScanResult {
        val cleaned = lines.map { it.copy(text = it.text.trim()) }.filter { it.text.length >= 2 }

        // Vintage: a plausible year, preferring the most prominent one.
        val vintage = cleaned.mapNotNull { line -> firstYear(line.text, currentYear)?.let { it to line.height } }
            .maxByOrNull { it.second }?.first

        val candidates = cleaned.filter { line ->
            val lower = line.text.lower()
            when {
                noiseWords.any { lower.contains(it) } -> false
                isVolumeOrStrength(lower) -> false
                line.text.all { it.isDigit() || it.isWhitespace() || isPunctuation(it) } -> false
                else -> line.text.any { it.isLetter() }
            }
        }

        val appellationLine = candidates.firstOrNull { line -> appellationWords.any { line.text.lower().contains(it) } }
        val appellation = appellationLine?.let { stripAppellationBoilerplate(it.text) }

        val producerLine = candidates.firstOrNull { line ->
            line.text.lower().split(Regex("[^\\p{L}]+")).any { it in producerWords }
        }
        val producer = producerLine?.let { titleCase(remove(vintage, it.text)) }

        // Name: the most prominent remaining line.
        val nameLine = candidates
            .filter { it.text != producerLine?.text && it.text != appellationLine?.text }
            .maxByOrNull { it.height }
        var name = if (nameLine != null) titleCase(remove(vintage, nameLine.text)) else producer
        if (name?.isEmpty() == true) name = null
        return LabelScanResult(name = name, producer = producer, vintage = vintage, appellation = appellation)
    }

    internal fun firstYear(text: String, currentYear: Int): Int? {
        val digits = StringBuilder()
        for (character in "$text ") {
            if (character.isDigit()) {
                digits.append(character)
            } else {
                if (digits.length == 4) {
                    val year = digits.toString().toInt()
                    if (year in 1900..currentYear + 1) return year
                }
                digits.clear()
            }
        }
        return null
    }

    private fun isPunctuation(char: Char): Boolean = when (Character.getType(char).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
        Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION -> true
        else -> false
    }

    /** Removes the detected vintage from a line ("Clos X 2017" → "Clos X"). */
    internal fun remove(vintage: Int?, text: String): String {
        if (vintage == null) return text
        return text.split(" ").filter { it.isNotEmpty() && it != vintage.toString() }.joinToString(" ")
            .trim { it.isWhitespace() || isPunctuation(it) }
    }

    /** "13,5% vol", "75 cl", "750ml". */
    internal fun isVolumeOrStrength(lower: String): Boolean {
        val tokens = lower.replace(",", ".").split(" ").filter { it.isNotEmpty() }
        tokens.forEachIndexed { index, token ->
            for (unit in listOf("cl", "ml", "%", "l")) {
                if (token.endsWith(unit)) {
                    val number = token.dropLast(unit.length)
                    if (number.isNotEmpty() && number.toDoubleOrNull() != null) return true
                }
            }
            if (token in listOf("cl", "ml", "%", "vol") && index > 0 && tokens[index - 1].toDoubleOrNull() != null) return true
        }
        return false
    }

    internal fun stripAppellationBoilerplate(text: String): String {
        var result = text
        for (phrase in listOf("Appellation", "appellation", "Contrôlée", "Controlée", "Controlee", "contrôlée", "Protégée", "protégée", "d'Origine", "d’Origine", "Origine")) {
            result = result.replace(phrase, "")
        }
        val words = mutableListOf<String>()
        for (word in result.split(" ").filter { it.isNotEmpty() }) if (words.lastOrNull() != word) words += word
        return words.joinToString(" ").trim()
    }

    /** "CHÂTEAU DES TILLEULS" → "Château des Tilleuls"; mixed case is kept as is. */
    fun titleCase(text: String): String {
        if (text != text.uppercase(Locale.ROOT)) return text
        val small = setOf("de", "des", "du", "la", "le", "les", "et", "di", "del", "della", "von", "of", "the")
        return text.lower().split(" ").filter { it.isNotEmpty() }.mapIndexed { index, word ->
            if (index > 0 && word in small) word else word.replaceFirstChar { it.uppercase(Locale.ROOT) }
        }.joinToString(" ")
    }
}
