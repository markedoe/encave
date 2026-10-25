package app.encave.core

/**
 * A request for an evening's selection, as text for an assistant app the user picks
 * (Share Evening Request in Tonight): a short instruction in the user's language, then the
 * bottles of the cellar, one line per wine. It is built on the device and handed to the
 * share sheet; Encave sends nothing. Prices, notes, places of purchase and photos are never
 * in it. The same text as CaveKit (fixtures/evening-request-vectors.json).
 */
data class EveningRequest(
    /** The occasion, dishes or guests, as the user typed them. */
    val occasion: String = "",
    /** Bottles the user wants; null lets the assistant choose. */
    val bottlesWanted: Int? = null,
    /** The list holds only bottles ready to drink or to drink soon. */
    val onlyReady: Boolean = true,
    val language: Language = Language.En,
    val year: Int,
    val wines: List<Line>,
    /** Wines left out to stay within the limit. */
    val omitted: Int = 0,
) {
    enum class Language {
        En, Fr;

        companion object {
            /** French for a French language code, else English. */
            fun of(code: String?): Language = if (code?.lowercase()?.startsWith("fr") == true) Fr else En
        }
    }

    /** One wine of the list. [style] and [maturity] are the English words of the cellar report. */
    data class Line(
        val name: String,
        val vintage: Int? = null,
        val producer: String? = null,
        val style: String? = null,
        val appellation: String? = null,
        val drinkFrom: Int? = null,
        val drinkUntil: Int? = null,
        val maturity: String,
        val bottles: Int,
        val places: List<String> = emptyList(),
    )

    val text: String
        get() {
            val fr = language == Language.Fr
            val occasion = clean(occasion).trim()
            val wanted = bottlesWanted?.takeIf { it > 0 }?.coerceAtMost(24)
            val lines = mutableListOf<String>()
            lines += if (fr) "Aide-moi à choisir du vin de ma cave pour ce soir." else "Help me choose wine from my cellar for this evening."
            if (occasion.isNotEmpty()) lines += (if (fr) "Occasion : " else "Occasion: ") + occasion
            val count = when {
                wanted != null && fr -> if (wanted > 1) "$wanted bouteilles, prises" else "$wanted bouteille, prise"
                wanted != null -> "$wanted bottle${if (wanted == 1) "" else "s"}, taken"
                fr -> "quelques bouteilles, prises"
                else -> "a few bottles, taken"
            }
            lines += if (fr) "Propose $count uniquement dans la liste ci-dessous." else "Propose $count only from the list below."
            lines += if (fr) {
                "Pour chaque bouteille, dis pourquoi elle convient, à quel moment la servir dans la soirée (ordre de service), " +
                    "sa température de service et où elle se trouve dans la cave (sa position)."
            } else {
                "For each bottle, say why it fits, when to serve it in the evening (order of service), " +
                    "its serving temperature and where it is in the cellar (its position)."
            }
            if (onlyReady) {
                lines += if (fr) "La liste ne contient que des bouteilles prêtes à boire ou à boire bientôt."
                else "The list holds only bottles ready to drink or to drink soon."
            }
            lines += if (fr) "Réponds en français." else "Answer in English."
            lines += ""

            val bottles = wines.sumOf { it.bottles }
            val n = wines.size
            var header = if (fr) {
                "Ma cave en $year : $n vin${if (n > 1) "s" else ""}, $bottles bouteille${if (bottles > 1) "s" else ""}"
            } else {
                "My cellar in $year: $n wine${if (n == 1) "" else "s"}, $bottles bottle${if (bottles == 1) "" else "s"}"
            }
            if (omitted > 0) {
                val s = if (omitted > 1) "s" else ""
                header += if (fr) " ($omitted autre$s vin$s non listé$s)" else " ($omitted more wine${if (omitted == 1) "" else "s"} left out)"
            }
            lines += "$header."
            if (wines.isEmpty()) return lines.joinToString("\n")
            lines += if (fr) "Colonnes : vin | producteur | style | appellation | fenêtre | maturité | bouteilles | positions"
            else "Columns: wine | producer | style | appellation | window | maturity | bottles | positions"
            wines.forEachIndexed { index, wine ->
                val from = wine.drinkFrom
                val until = wine.drinkUntil
                val window = when {
                    from != null && until != null -> "$from-$until"
                    from != null -> if (fr) "dès $from" else "from $from"
                    until != null -> if (fr) "jusqu’en $until" else "until $until"
                    else -> ""
                }
                val fields = listOf(
                    wine.vintage?.let { "${wine.name} $it" } ?: wine.name,
                    wine.producer.orEmpty(), style(wine.style, fr), wine.appellation.orEmpty(), window,
                    maturity(wine.maturity, fr), wine.bottles.toString(), wine.places.joinToString("; "),
                )
                lines += "${index + 1}. " + fields.joinToString(" | ") { clean(it) }
            }
            return lines.joinToString("\n")
        }

    companion object {
        /** The English words of the cellar report (Get Cellar Contents on Apple). */
        fun maturityWord(maturity: Maturity): String = when (maturity) {
            Maturity.DrinkSoon -> "drink soon"
            Maturity.Ready -> "ready"
            Maturity.Young -> "too young"
            Maturity.PastPeak -> "past its peak"
            Maturity.Unknown -> "window unknown"
        }

        internal fun style(style: String?, fr: Boolean): String {
            if (style == null) return ""
            if (!fr) return style
            return when (style) {
                "red" -> "rouge"
                "white" -> "blanc"
                "rose" -> "rosé"
                "sparkling" -> "effervescent"
                "dessert" -> "liquoreux"
                "fortified" -> "muté"
                else -> style
            }
        }

        internal fun maturity(maturity: String, fr: Boolean): String {
            if (!fr) return maturity
            return when (maturity) {
                "drink soon" -> "à boire bientôt"
                "ready" -> "prêt"
                "too young" -> "trop jeune"
                "past its peak" -> "après l’apogée"
                "window unknown" -> "fenêtre inconnue"
                else -> maturity
            }
        }

        private val newlines = setOf('\n', '\u000B', '\u000C', '\r', '\u0085', ' ', ' ')

        /** Keeps a field on one line and free of the separator. */
        internal fun clean(field: String): String =
            field.replace("|", "/").map { if (it in newlines) ' ' else it }.joinToString("")
    }
}

/**
 * The request for an evening: with [onlyReady], the bottles to drink soon then those ready,
 * leaving out those kept for an occasion (as Tonight); else every bottle in the cellar, by
 * name. At most [limit] wines. [location] names where a bottle is (the app's words).
 */
fun Cellar.eveningRequest(
    occasion: String = "",
    bottlesWanted: Int? = null,
    onlyReady: Boolean = true,
    language: EveningRequest.Language,
    year: Int,
    limit: Int = 500,
    location: (Bottle) -> String? = { locationLabel(it) },
): EveningRequest {
    val groups = if (onlyReady) {
        groupsByMaturity(year).filter { it.maturity == Maturity.DrinkSoon || it.maturity == Maturity.Ready }.flatMap { it.groups }
    } else {
        groups(CellarFilter(), CellarSort.Name, year)
    }
    val lines = groups.take(maxOf(0, limit)).map { group ->
        val wine = group.wine
        EveningRequest.Line(
            name = wine.name, vintage = wine.vintage, producer = wine.producer, style = wine.color?.raw,
            appellation = wine.appellation ?: wine.region, drinkFrom = wine.drinkFrom, drinkUntil = wine.drinkUntil,
            maturity = EveningRequest.maturityWord(Maturity.of(wine, year)), bottles = group.count,
            places = group.bottles.mapNotNull(location),
        )
    }
    return EveningRequest(occasion, bottlesWanted, onlyReady, language, year, lines, maxOf(0, groups.size - lines.size))
}
