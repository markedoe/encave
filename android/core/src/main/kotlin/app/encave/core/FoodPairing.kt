package app.encave.core

import java.util.Locale
import java.util.UUID

/** Kinds of dishes for food pairing. Works offline from classic pairing rules. */
enum class DishCategory(val raw: String) {
    RedMeat("redMeat"), Poultry("poultry"), Pork("pork"), Game("game"), Fish("fish"), Shellfish("shellfish"),
    Cheese("cheese"), Spicy("spicy"), Vegetarian("vegetarian"), Pasta("pasta"), Dessert("dessert"), Aperitif("aperitif"),
}

/** One suggested bottle for a dish. */
data class PairingSuggestion(
    val group: WineGroup,
    /** 0..100, higher is better. */
    val score: Int,
    /** Why it matches, as reason codes the UI turns into text. */
    val reasons: List<PairingReason>,
) {
    val id: UUID get() = group.id
}

enum class PairingReason { Style, Grape, Region, ReadyToDrink }

/** Classic pairing rules: preferred styles, grapes and regions per dish. */
data class PairingRule(val colors: Map<WineColor, Int>, val grapes: List<String>, val regions: List<String>) {
    companion object {
        fun forDish(dish: DishCategory): PairingRule = when (dish) {
            DishCategory.RedMeat -> PairingRule(
                mapOf(WineColor.Red to 60),
                listOf("cabernet sauvignon", "syrah", "shiraz", "malbec", "merlot", "nebbiolo", "tempranillo", "mourvèdre"),
                listOf("bordeaux", "rhône", "rhone", "rioja", "piemonte", "mendoza"),
            )
            DishCategory.Poultry -> PairingRule(
                mapOf(WineColor.White to 45, WineColor.Red to 40, WineColor.Rose to 30),
                listOf("chardonnay", "pinot noir", "gamay", "chenin blanc", "viognier"),
                listOf("bourgogne", "burgundy", "beaujolais", "loire"),
            )
            DishCategory.Pork -> PairingRule(
                mapOf(WineColor.Red to 45, WineColor.White to 40, WineColor.Rose to 35),
                listOf("pinot noir", "riesling", "gamay", "grenache", "chenin blanc"),
                listOf("alsace", "beaujolais", "loire"),
            )
            DishCategory.Game -> PairingRule(
                mapOf(WineColor.Red to 60),
                listOf("pinot noir", "syrah", "nebbiolo", "grenache", "mourvèdre"),
                listOf("bourgogne", "burgundy", "rhône", "rhone", "piemonte"),
            )
            DishCategory.Fish -> PairingRule(
                mapOf(WineColor.White to 60, WineColor.Sparkling to 35, WineColor.Rose to 30),
                listOf("sauvignon blanc", "chardonnay", "chasselas", "albariño", "muscadet", "melon de bourgogne", "riesling"),
                listOf("loire", "bourgogne", "chablis", "vaud", "galicia"),
            )
            DishCategory.Shellfish -> PairingRule(
                mapOf(WineColor.White to 60, WineColor.Sparkling to 50),
                listOf("muscadet", "melon de bourgogne", "chablis", "chardonnay", "sauvignon blanc", "chasselas", "albariño"),
                listOf("loire", "chablis", "champagne", "vaud"),
            )
            DishCategory.Cheese -> PairingRule(
                mapOf(WineColor.Red to 40, WineColor.White to 40, WineColor.Dessert to 40, WineColor.Fortified to 35),
                listOf("chardonnay", "pinot noir", "chenin blanc", "savagnin", "riesling"),
                listOf("jura", "bourgogne", "burgundy", "sauternes", "porto", "douro"),
            )
            DishCategory.Spicy -> PairingRule(
                mapOf(WineColor.White to 50, WineColor.Rose to 45, WineColor.Orange to 40),
                listOf("riesling", "gewurztraminer", "gewürztraminer", "pinot gris", "grenache"),
                listOf("alsace", "mosel"),
            )
            DishCategory.Vegetarian -> PairingRule(
                mapOf(WineColor.White to 45, WineColor.Rose to 40, WineColor.Red to 30, WineColor.Orange to 40),
                listOf("sauvignon blanc", "pinot noir", "grüner veltliner", "gamay", "chenin blanc"),
                listOf("loire", "beaujolais", "provence"),
            )
            DishCategory.Pasta -> PairingRule(
                mapOf(WineColor.Red to 50, WineColor.White to 35),
                listOf("sangiovese", "barbera", "nebbiolo", "montepulciano", "primitivo", "grenache"),
                listOf("toscana", "tuscany", "piemonte", "rhône", "rhone"),
            )
            DishCategory.Dessert -> PairingRule(
                mapOf(WineColor.Dessert to 70, WineColor.Sparkling to 35, WineColor.Fortified to 55),
                listOf("sémillon", "semillon", "muscat", "riesling", "gewurztraminer", "chenin blanc"),
                listOf("sauternes", "tokaj", "porto", "alsace", "layon"),
            )
            DishCategory.Aperitif -> PairingRule(
                mapOf(WineColor.Sparkling to 70, WineColor.White to 45, WineColor.Rose to 40),
                listOf("chardonnay", "chasselas", "sauvignon blanc", "riesling"),
                listOf("champagne", "vaud", "loire", "alsace"),
            )
        }
    }
}

/** Bottles in the cellar that go well with a dish, best first. Bottles reserved for an occasion are left out, as in Tonight. */
fun Cellar.pairings(dish: DishCategory, year: Int, limit: Int = 10): List<PairingSuggestion> {
    val rule = PairingRule.forDish(dish)
    val collator = Cellar.collator()
    return groups().mapNotNull { all ->
        val free = all.bottles.filter { it.reservedFor == null }
        if (free.isEmpty()) return@mapNotNull null
        val group = WineGroup(all.wine, free)
        val wine = group.wine
        var score = 0
        val reasons = mutableListOf<PairingReason>()
        val points = wine.color?.let { rule.colors[it] }
        if (points != null) {
            score += points
            reasons += PairingReason.Style
        }
        val grapes = wine.grapes.map { it.lowercase(Locale.ROOT) }
        if (grapes.any { grape -> rule.grapes.any { grape.contains(it) } }) {
            score += 25
            reasons += PairingReason.Grape
        }
        val places = listOfNotNull(wine.region, wine.appellation, wine.country).map { it.lowercase(Locale.ROOT) }
        if (places.any { place -> rule.regions.any { place.contains(it) } }) {
            score += 15
            reasons += PairingReason.Region
        }
        if (score <= 0) return@mapNotNull null
        when (Maturity.of(wine, year)) {
            Maturity.Ready, Maturity.DrinkSoon -> {
                score += 10
                reasons += PairingReason.ReadyToDrink
            }
            Maturity.Young -> score -= 15
            Maturity.PastPeak -> score -= 5
            Maturity.Unknown -> Unit
        }
        PairingSuggestion(group, score.coerceIn(0, 100), reasons)
    }
        .sortedWith(compareByDescending<PairingSuggestion> { it.score }.then { a, b -> Cellar.nameOrder(a.group.wine, b.group.wine, collator) })
        .take(limit)
}
