package app.encave.core

/**
 * The shared vocabulary of the guided tasting sheet and the wine details: stable ids, never
 * labels, as in CaveKit's `Vocabulary`. `fixtures/tasting-vocabulary.json` is the reference
 * (checked by `VocabularyTest`); the app gives each id an English and a French label.
 * Records keep ids this version does not know and write them back unchanged.
 */
object Vocabulary {
    /** What an id names: labels are looked up by kind and id, as the same id ("medium", "orange") can mean different things. */
    enum class Kind(val raw: String) {
        Aroma("aroma"), AromaFamily("aromaFamily"), Structure("structure"), Level("level"), Intensity("intensity"),
        Hue("hue"), Dosage("dosage"), Sweetness("sweetness"), Ageing("ageing"), Farming("farming"), Closure("closure"),
    }

    /** An id and the wine colors it is offered for first (empty: every color). */
    data class Entry(val id: String, val colorNames: List<String>) {
        val colors: Set<WineColor> = colorNames.mapNotNull { name -> WineColor.entries.firstOrNull { it.raw == name } }.toSet()

        /** Offered first for a wine of this color; everything is for a wine without a color. */
        fun suits(color: WineColor?): Boolean = color == null || colors.isEmpty() || color in colors
    }

    data class AromaFamily(val id: String, val aromas: List<Entry>)

    /** A structure attribute of a tasting, noted 1 to 5; [scale] names the words for its levels (see [levels]). */
    data class Attribute(val id: String, val scale: String, val colorNames: List<String>? = null) {
        /** Offered for these colors only, unless already set (tannin); null for every color. */
        val colors: Set<WineColor>? = colorNames?.mapNotNull { name -> WineColor.entries.firstOrNull { it.raw == name } }?.toSet()

        /** Shown for a wine of this color: always when it has a value, or for a wine without a color. */
        fun isOffered(color: WineColor?, value: Int? = null): Boolean = value != null || colors == null || color == null || color in colors
    }

    val aromaFamilies: List<AromaFamily> = listOf(
        AromaFamily(
            "redFruit",
            listOf(
                Entry("fruit.strawberry", listOf("red", "rose", "sparkling")),
                Entry("fruit.raspberry", listOf("red", "rose", "sparkling")),
                Entry("fruit.redCherry", listOf("red", "rose", "fortified")),
                Entry("fruit.redcurrant", listOf("red", "rose")),
            ),
        ),
        AromaFamily(
            "blackFruit",
            listOf(
                Entry("fruit.blackberry", listOf("red", "fortified")),
                Entry("fruit.blackcurrant", listOf("red", "fortified")),
                Entry("fruit.blackCherry", listOf("red", "fortified")),
                Entry("fruit.plum", listOf("red", "fortified")),
            ),
        ),
        AromaFamily(
            "citrus",
            listOf(
                Entry("fruit.lemon", listOf("white", "rose", "sparkling", "orange")),
                Entry("fruit.grapefruit", listOf("white", "rose", "sparkling", "orange")),
                Entry("fruit.orangePeel", listOf("white", "sparkling", "dessert", "fortified", "orange")),
            ),
        ),
        AromaFamily(
            "orchardFruit",
            listOf(
                Entry("fruit.greenApple", listOf("white", "sparkling", "orange")),
                Entry("fruit.pear", listOf("white", "sparkling", "dessert")),
            ),
        ),
        AromaFamily(
            "stoneTropicalFruit",
            listOf(
                Entry("fruit.peach", listOf("white", "rose", "sparkling", "dessert")),
                Entry("fruit.apricot", listOf("white", "dessert", "orange")),
                Entry("fruit.pineapple", listOf("white", "dessert")),
                Entry("fruit.passionFruit", listOf("white", "sparkling")),
                Entry("fruit.lychee", listOf("white", "rose")),
            ),
        ),
        AromaFamily(
            "floral",
            listOf(
                Entry("floral.rose", listOf("red", "white", "rose")),
                Entry("floral.violet", listOf("red")),
                Entry("floral.acacia", listOf("white", "sparkling", "dessert")),
                Entry("floral.elderflower", listOf("white", "sparkling")),
            ),
        ),
        AromaFamily(
            "herbal",
            listOf(
                Entry("herbal.cutGrass", listOf("white", "sparkling")),
                Entry("herbal.greenPepper", listOf("red", "white")),
                Entry("herbal.mint", listOf("red", "white")),
                Entry("herbal.garrigue", listOf("red", "white", "rose")),
            ),
        ),
        AromaFamily(
            "spice",
            listOf(
                Entry("spice.blackPepper", listOf("red", "fortified")),
                Entry("spice.clove", listOf("red", "dessert", "fortified")),
                Entry("spice.cinnamon", listOf("red", "white", "dessert", "fortified")),
                Entry("spice.liquorice", listOf("red", "fortified")),
            ),
        ),
        AromaFamily(
            "oakToast",
            listOf(
                Entry("oak.vanilla", listOf("red", "white", "fortified")),
                Entry("oak.toast", listOf("red", "white", "sparkling")),
                Entry("oak.smoke", listOf("red", "white")),
                Entry("oak.cedar", listOf("red")),
            ),
        ),
        AromaFamily(
            "earthMineral",
            listOf(
                Entry("earth.flint", listOf("white", "sparkling")),
                Entry("earth.petrol", listOf("white")),
                Entry("earth.mushroom", listOf("red", "white")),
                Entry("earth.forestFloor", listOf("red")),
            ),
        ),
        AromaFamily(
            "animalLeather",
            listOf(
                Entry("animal.leather", listOf("red", "fortified")),
                Entry("animal.game", listOf("red")),
                Entry("animal.tobacco", listOf("red", "fortified")),
            ),
        ),
        AromaFamily(
            "pastryButter",
            listOf(
                Entry("pastry.brioche", listOf("white", "sparkling")),
                Entry("pastry.butter", listOf("white")),
                Entry("pastry.hazelnut", listOf("white", "sparkling", "dessert", "fortified", "orange")),
            ),
        ),
        AromaFamily(
            "honeyDriedFruit",
            listOf(
                Entry("dried.honey", listOf("white", "sparkling", "dessert", "fortified", "orange")),
                Entry("dried.raisin", listOf("dessert", "fortified")),
                Entry("dried.fig", listOf("red", "dessert", "fortified")),
                Entry("dried.walnut", listOf("white", "dessert", "fortified", "orange")),
            ),
        ),
    )

    /** Every aroma id, family by family. */
    val aromaIds: List<String> get() = aromaFamilies.flatMap { family -> family.aromas.map { it.id } }

    /** The family of an aroma id, null for an id this version does not know. */
    fun familyOfAroma(id: String): AromaFamily? = aromaFamilies.firstOrNull { family -> family.aromas.any { it.id == id } }

    val structure: List<Attribute> = listOf(
        Attribute("sweetness", "sweetness"),
        Attribute("acidity", "level"),
        Attribute("tannin", "level", listOf("red", "orange", "fortified")),
        Attribute("body", "body"),
        Attribute("alcohol", "level"),
        Attribute("length", "length"),
    )

    /** The words for levels 1 to 5 of each scale (ids of kind [Kind.Level]). */
    val levels: Map<String, List<String>> = mapOf(
        "level" to listOf("low", "mediumMinus", "medium", "mediumPlus", "high"),
        "sweetness" to listOf("dry", "offDry", "medium", "sweet", "luscious"),
        "body" to listOf("light", "mediumMinus", "medium", "mediumPlus", "full"),
        "length" to listOf("short", "mediumMinus", "medium", "mediumPlus", "long"),
    )

    /** The word for a level (1 to 5) of an attribute, as an id of kind [Kind.Level]. */
    fun level(value: Int, attribute: Attribute): String? = levels[attribute.scale]?.getOrNull(value - 1)

    val intensities = listOf("pale", "medium", "deep")

    val hues: List<Entry> = listOf(
        Entry("purple", listOf("red")),
        Entry("ruby", listOf("red", "fortified")),
        Entry("garnet", listOf("red", "fortified")),
        Entry("tawny", listOf("red", "fortified", "dessert")),
        Entry("lemonGreen", listOf("white", "sparkling")),
        Entry("lemon", listOf("white", "sparkling", "dessert")),
        Entry("gold", listOf("white", "sparkling", "dessert", "orange")),
        Entry("amber", listOf("white", "dessert", "fortified", "orange")),
        Entry("pink", listOf("rose", "sparkling")),
        Entry("salmon", listOf("rose", "sparkling")),
        Entry("onionSkin", listOf("rose", "sparkling")),
        Entry("orange", listOf("rose", "orange")),
        Entry("brown", listOf("red", "white", "dessert", "fortified")),
    )

    val dosages = listOf("brutNature", "extraBrut", "brut", "extraDry", "sec", "demiSec", "doux")
    val sweetnesses = listOf("dry", "offDry", "mediumSweet", "sweet")
    val ageings = listOf("oakBarrel", "newOak", "foudre", "stainlessSteel", "concrete", "amphora", "onLees")
    val farmings = listOf("organic", "biodynamic", "natural", "sustainable", "conventional")
    val closures = listOf("naturalCork", "technicalCork", "screwCap", "glass", "synthetic", "crownCap")

    /** Offered while typing a classification, which stays free text. */
    val classificationSuggestions = listOf(
        "Grand Cru", "Premier Cru", "Grand Cru Classé", "Premier Grand Cru Classé", "Cru Classé", "Cru Bourgeois", "Cru Artisan", "Village", "DOCG", "Riserva", "Reserva", "Gran Reserva", "Grosses Gewächs", "Erste Lage",
    )

    /** The ids of a kind, in the order shown. */
    fun ids(kind: Kind): List<String> = when (kind) {
        Kind.Aroma -> aromaIds
        Kind.AromaFamily -> aromaFamilies.map { it.id }
        Kind.Structure -> structure.map { it.id }
        Kind.Level -> levels.values.flatten().toSortedSet().toList()
        Kind.Intensity -> intensities
        Kind.Hue -> hues.map { it.id }
        Kind.Dosage -> dosages
        Kind.Sweetness -> sweetnesses
        Kind.Ageing -> ageings
        Kind.Farming -> farmings
        Kind.Closure -> closures
    }

    /**
     * How the app names an id in the person's language, for search; set once at launch.
     * Without it (or for an id it does not know) the id itself is used.
     */
    @Volatile var labelProvider: (Kind, String) -> String? = { _, _ -> null }

    /** The label of an id, or the id itself when none is known (an id from a newer version). */
    fun label(kind: Kind, id: String): String = labelProvider(kind, id) ?: id
}
