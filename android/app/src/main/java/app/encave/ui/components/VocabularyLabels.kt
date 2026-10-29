package app.encave.ui.components

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources
import app.encave.R
import app.encave.core.Vocabulary

/**
 * The labels of the shared vocabulary (guided tasting, wine details): each id of
 * `fixtures/tasting-vocabulary.json` has the string `vocab_<kind>_<id>`, its dots made
 * underscores (android/tools/strings.py, which checks that every id has one in English and
 * French). An id this version does not know (written by a newer one) shows as it is.
 */
object VocabularyLabels {
    val table: Map<Vocabulary.Kind, Map<String, Int>> = mapOf(
        Vocabulary.Kind.Aroma to mapOf(
            "fruit.strawberry" to R.string.vocab_aroma_fruit_strawberry,
            "fruit.raspberry" to R.string.vocab_aroma_fruit_raspberry,
            "fruit.redCherry" to R.string.vocab_aroma_fruit_redCherry,
            "fruit.redcurrant" to R.string.vocab_aroma_fruit_redcurrant,
            "fruit.blackberry" to R.string.vocab_aroma_fruit_blackberry,
            "fruit.blackcurrant" to R.string.vocab_aroma_fruit_blackcurrant,
            "fruit.blackCherry" to R.string.vocab_aroma_fruit_blackCherry,
            "fruit.plum" to R.string.vocab_aroma_fruit_plum,
            "fruit.lemon" to R.string.vocab_aroma_fruit_lemon,
            "fruit.grapefruit" to R.string.vocab_aroma_fruit_grapefruit,
            "fruit.orangePeel" to R.string.vocab_aroma_fruit_orangePeel,
            "fruit.greenApple" to R.string.vocab_aroma_fruit_greenApple,
            "fruit.pear" to R.string.vocab_aroma_fruit_pear,
            "fruit.peach" to R.string.vocab_aroma_fruit_peach,
            "fruit.apricot" to R.string.vocab_aroma_fruit_apricot,
            "fruit.pineapple" to R.string.vocab_aroma_fruit_pineapple,
            "fruit.passionFruit" to R.string.vocab_aroma_fruit_passionFruit,
            "fruit.lychee" to R.string.vocab_aroma_fruit_lychee,
            "floral.rose" to R.string.vocab_aroma_floral_rose,
            "floral.violet" to R.string.vocab_aroma_floral_violet,
            "floral.acacia" to R.string.vocab_aroma_floral_acacia,
            "floral.elderflower" to R.string.vocab_aroma_floral_elderflower,
            "herbal.cutGrass" to R.string.vocab_aroma_herbal_cutGrass,
            "herbal.greenPepper" to R.string.vocab_aroma_herbal_greenPepper,
            "herbal.mint" to R.string.vocab_aroma_herbal_mint,
            "herbal.garrigue" to R.string.vocab_aroma_herbal_garrigue,
            "spice.blackPepper" to R.string.vocab_aroma_spice_blackPepper,
            "spice.clove" to R.string.vocab_aroma_spice_clove,
            "spice.cinnamon" to R.string.vocab_aroma_spice_cinnamon,
            "spice.liquorice" to R.string.vocab_aroma_spice_liquorice,
            "oak.vanilla" to R.string.vocab_aroma_oak_vanilla,
            "oak.toast" to R.string.vocab_aroma_oak_toast,
            "oak.smoke" to R.string.vocab_aroma_oak_smoke,
            "oak.cedar" to R.string.vocab_aroma_oak_cedar,
            "earth.flint" to R.string.vocab_aroma_earth_flint,
            "earth.petrol" to R.string.vocab_aroma_earth_petrol,
            "earth.mushroom" to R.string.vocab_aroma_earth_mushroom,
            "earth.forestFloor" to R.string.vocab_aroma_earth_forestFloor,
            "animal.leather" to R.string.vocab_aroma_animal_leather,
            "animal.game" to R.string.vocab_aroma_animal_game,
            "animal.tobacco" to R.string.vocab_aroma_animal_tobacco,
            "pastry.brioche" to R.string.vocab_aroma_pastry_brioche,
            "pastry.butter" to R.string.vocab_aroma_pastry_butter,
            "pastry.hazelnut" to R.string.vocab_aroma_pastry_hazelnut,
            "dried.honey" to R.string.vocab_aroma_dried_honey,
            "dried.raisin" to R.string.vocab_aroma_dried_raisin,
            "dried.fig" to R.string.vocab_aroma_dried_fig,
            "dried.walnut" to R.string.vocab_aroma_dried_walnut,
        ),
        Vocabulary.Kind.AromaFamily to mapOf(
            "redFruit" to R.string.vocab_aromaFamily_redFruit,
            "blackFruit" to R.string.vocab_aromaFamily_blackFruit,
            "citrus" to R.string.vocab_aromaFamily_citrus,
            "orchardFruit" to R.string.vocab_aromaFamily_orchardFruit,
            "stoneTropicalFruit" to R.string.vocab_aromaFamily_stoneTropicalFruit,
            "floral" to R.string.vocab_aromaFamily_floral,
            "herbal" to R.string.vocab_aromaFamily_herbal,
            "spice" to R.string.vocab_aromaFamily_spice,
            "oakToast" to R.string.vocab_aromaFamily_oakToast,
            "earthMineral" to R.string.vocab_aromaFamily_earthMineral,
            "animalLeather" to R.string.vocab_aromaFamily_animalLeather,
            "pastryButter" to R.string.vocab_aromaFamily_pastryButter,
            "honeyDriedFruit" to R.string.vocab_aromaFamily_honeyDriedFruit,
        ),
        Vocabulary.Kind.Structure to mapOf(
            "sweetness" to R.string.vocab_structure_sweetness,
            "acidity" to R.string.vocab_structure_acidity,
            "tannin" to R.string.vocab_structure_tannin,
            "body" to R.string.vocab_structure_body,
            "alcohol" to R.string.vocab_structure_alcohol,
            "length" to R.string.vocab_structure_length,
        ),
        Vocabulary.Kind.Level to mapOf(
            "low" to R.string.vocab_level_low,
            "mediumMinus" to R.string.vocab_level_mediumMinus,
            "medium" to R.string.vocab_level_medium,
            "mediumPlus" to R.string.vocab_level_mediumPlus,
            "high" to R.string.vocab_level_high,
            "dry" to R.string.vocab_level_dry,
            "offDry" to R.string.vocab_level_offDry,
            "sweet" to R.string.vocab_level_sweet,
            "luscious" to R.string.vocab_level_luscious,
            "light" to R.string.vocab_level_light,
            "full" to R.string.vocab_level_full,
            "short" to R.string.vocab_level_short,
            "long" to R.string.vocab_level_long,
        ),
        Vocabulary.Kind.Intensity to mapOf(
            "pale" to R.string.vocab_intensity_pale,
            "medium" to R.string.vocab_intensity_medium,
            "deep" to R.string.vocab_intensity_deep,
        ),
        Vocabulary.Kind.Hue to mapOf(
            "purple" to R.string.vocab_hue_purple,
            "ruby" to R.string.vocab_hue_ruby,
            "garnet" to R.string.vocab_hue_garnet,
            "tawny" to R.string.vocab_hue_tawny,
            "lemonGreen" to R.string.vocab_hue_lemonGreen,
            "lemon" to R.string.vocab_hue_lemon,
            "gold" to R.string.vocab_hue_gold,
            "amber" to R.string.vocab_hue_amber,
            "pink" to R.string.vocab_hue_pink,
            "salmon" to R.string.vocab_hue_salmon,
            "onionSkin" to R.string.vocab_hue_onionSkin,
            "orange" to R.string.vocab_hue_orange,
            "brown" to R.string.vocab_hue_brown,
        ),
        Vocabulary.Kind.Dosage to mapOf(
            "brutNature" to R.string.vocab_dosage_brutNature,
            "extraBrut" to R.string.vocab_dosage_extraBrut,
            "brut" to R.string.vocab_dosage_brut,
            "extraDry" to R.string.vocab_dosage_extraDry,
            "sec" to R.string.vocab_dosage_sec,
            "demiSec" to R.string.vocab_dosage_demiSec,
            "doux" to R.string.vocab_dosage_doux,
        ),
        Vocabulary.Kind.Sweetness to mapOf(
            "dry" to R.string.vocab_sweetness_dry,
            "offDry" to R.string.vocab_sweetness_offDry,
            "mediumSweet" to R.string.vocab_sweetness_mediumSweet,
            "sweet" to R.string.vocab_sweetness_sweet,
        ),
        Vocabulary.Kind.Ageing to mapOf(
            "oakBarrel" to R.string.vocab_ageing_oakBarrel,
            "newOak" to R.string.vocab_ageing_newOak,
            "foudre" to R.string.vocab_ageing_foudre,
            "stainlessSteel" to R.string.vocab_ageing_stainlessSteel,
            "concrete" to R.string.vocab_ageing_concrete,
            "amphora" to R.string.vocab_ageing_amphora,
            "onLees" to R.string.vocab_ageing_onLees,
        ),
        Vocabulary.Kind.Farming to mapOf(
            "organic" to R.string.vocab_farming_organic,
            "biodynamic" to R.string.vocab_farming_biodynamic,
            "natural" to R.string.vocab_farming_natural,
            "sustainable" to R.string.vocab_farming_sustainable,
            "conventional" to R.string.vocab_farming_conventional,
        ),
        Vocabulary.Kind.Closure to mapOf(
            "naturalCork" to R.string.vocab_closure_naturalCork,
            "technicalCork" to R.string.vocab_closure_technicalCork,
            "screwCap" to R.string.vocab_closure_screwCap,
            "glass" to R.string.vocab_closure_glass,
            "synthetic" to R.string.vocab_closure_synthetic,
            "crownCap" to R.string.vocab_closure_crownCap,
        ),
    )

    /** The level words of the attributes that are feminine in French (l’acidité, la longueur). */
    private val feminineLevels: Map<String, Int> = mapOf(
        "low" to R.string.level_feminine_low,
        "mediumMinus" to R.string.level_feminine_mediumMinus,
        "medium" to R.string.level_feminine_medium,
        "mediumPlus" to R.string.level_feminine_mediumPlus,
        "high" to R.string.level_feminine_high,
        "short" to R.string.level_feminine_short,
        "long" to R.string.level_feminine_long,
    )
    private val feminineAttributes = setOf("acidity", "length")

    fun res(kind: Vocabulary.Kind, id: String): Int? = table[kind]?.get(id)

    fun label(resources: Resources, kind: Vocabulary.Kind, id: String): String = res(kind, id)?.let(resources::getString) ?: id

    /** The word for a level (1 to 5) of a structure attribute, agreeing with it ("Acidité : élevée", "Corps : léger"). */
    fun level(resources: Resources, attribute: Vocabulary.Attribute, value: Int): String? {
        val id = Vocabulary.level(value, attribute) ?: return null
        val res = (if (attribute.id in feminineAttributes) feminineLevels[id] else null) ?: res(Vocabulary.Kind.Level, id)
        return res?.let(resources::getString) ?: id
    }

    /** Lets the core search match these labels in the person's language (wine and tasting search). */
    fun install(context: Context) {
        val app = context.applicationContext
        Vocabulary.labelProvider = { kind, id -> res(kind, id)?.let { app.resources.getString(it) } }
    }
}

/** The label of a vocabulary id in the person's language. */
@Composable
@ReadOnlyComposable
fun vocabLabel(kind: Vocabulary.Kind, id: String): String = VocabularyLabels.label(LocalResources.current, kind, id)
