package app.encave.ui.components

import app.encave.core.Occasion
import android.content.res.Resources
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.WineBar
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.encave.R
import app.encave.core.CellarSort
import app.encave.core.StorageType
import app.encave.core.storageCount
import app.encave.core.storageType
import app.encave.core.DishCategory
import app.encave.core.Discovery
import app.encave.core.Maturity
import app.encave.core.PairingReason
import app.encave.core.Bottle
import app.encave.core.Cellar
import app.encave.core.Rack
import app.encave.core.RackCell
import app.encave.core.RackKind
import app.encave.core.Slot
import app.encave.core.SampleData
import app.encave.core.Wine
import app.encave.core.WineColor
import app.encave.ui.UiText
import app.encave.ui.theme.LocalMaturityPalette
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

val WineColor?.labelRes: Int
    get() = when (this) {
        WineColor.Red -> R.string.color_red
        WineColor.White -> R.string.color_white
        WineColor.Rose -> R.string.color_rose
        WineColor.Sparkling -> R.string.color_sparkling
        WineColor.Dessert -> R.string.color_dessert
        WineColor.Fortified -> R.string.color_fortified
        WineColor.Orange -> R.string.color_orange
        null -> R.string.color_none
    }

val Maturity.labelRes: Int
    get() = when (this) {
        Maturity.DrinkSoon -> R.string.maturity_drink_soon
        Maturity.Ready -> R.string.maturity_ready
        Maturity.PastPeak -> R.string.maturity_past_peak
        Maturity.Young -> R.string.maturity_young
        Maturity.Unknown -> R.string.maturity_unknown
    }

val Maturity.icon: ImageVector
    get() = when (this) {
        Maturity.DrinkSoon -> Icons.Outlined.HourglassBottom
        Maturity.Ready -> Icons.Outlined.CheckCircle
        Maturity.PastPeak -> Icons.Outlined.WarningAmber
        Maturity.Young -> Icons.Outlined.Eco
        Maturity.Unknown -> Icons.AutoMirrored.Outlined.HelpOutline
    }

/** Maturity token colors, for the symbol only: the words next to it stay in the text color. */
val Maturity.tint: Color
    @Composable @ReadOnlyComposable
    get() = when (this) {
        Maturity.DrinkSoon -> LocalMaturityPalette.current.drinkSoon
        Maturity.Ready -> LocalMaturityPalette.current.ready
        Maturity.PastPeak -> LocalMaturityPalette.current.pastPeak
        Maturity.Young -> LocalMaturityPalette.current.keep
        Maturity.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
    }

/** "Drink soon", "Ready", "Past peak", "Keep": the short words of a list row. */
val Maturity.shortLabelRes: Int
    get() = when (this) {
        Maturity.DrinkSoon -> R.string.maturity_short_drink_soon
        Maturity.Ready -> R.string.maturity_short_ready
        Maturity.PastPeak -> R.string.maturity_short_past_peak
        Maturity.Young -> R.string.maturity_short_keep
        Maturity.Unknown -> R.string.maturity_unknown
    }

val CellarSort.labelRes: Int
    get() = when (this) {
        CellarSort.Name -> R.string.sort_name
        CellarSort.Vintage -> R.string.sort_vintage
        CellarSort.RecentlyAdded -> R.string.sort_recent
        CellarSort.Readiness -> R.string.sort_readiness
        CellarSort.Region -> R.string.sort_region
    }

val RackKind.labelRes: Int
    get() = when (this) {
        RackKind.Grid -> R.string.kind_grid
        RackKind.Staggered -> R.string.kind_staggered
        RackKind.Shelves -> R.string.kind_shelves
        RackKind.Bins -> R.string.storage_type_crates
    }

val StorageType.labelRes: Int
    get() = when (this) {
        StorageType.Rack -> R.string.storage_type_rack
        StorageType.WineFridge -> R.string.storage_type_fridge
        StorageType.Crates -> R.string.storage_type_crates
    }

val StorageType.bodyRes: Int
    get() = when (this) {
        StorageType.Rack -> R.string.storage_type_rack_body
        StorageType.WineFridge -> R.string.storage_type_fridge_body
        StorageType.Crates -> R.string.storage_type_crates_body
    }

/** "Wine fridge · 4 shelves", "Rack · 6 rows", "Rack · 6 bins" (diamond bins) or "Crates · 10 bins", as in the Storage list. */
fun Rack.storageSummary(resources: Resources): String {
    val count = storageCount
    val detail = when (storageType) {
        StorageType.Rack ->
            resources.getQuantityString(if (kind == RackKind.Bins) R.plurals.storage_bins else R.plurals.storage_rows, count, count)
        StorageType.WineFridge -> resources.getQuantityString(R.plurals.storage_shelves, count, count)
        StorageType.Crates -> resources.getQuantityString(R.plurals.storage_bins, count, count)
    }
    return "${resources.getString(storageType.labelRes)} · $detail"
}

val RackKind.bodyRes: Int
    get() = when (this) {
        RackKind.Grid -> R.string.kind_grid_body
        RackKind.Staggered -> R.string.kind_staggered_body
        RackKind.Shelves -> R.string.kind_shelves_body
        RackKind.Bins -> R.string.storage_type_crates_body
    }

/** What a row is called in this kind of storage. */
val RackKind.rowsRes: Int
    get() = when (this) {
        RackKind.Grid, RackKind.Staggered -> R.string.rows_grid
        RackKind.Shelves -> R.string.rows_shelves
        RackKind.Bins -> R.string.rows_bins
    }

val RackKind.columnsRes: Int
    get() = when (this) {
        RackKind.Grid -> R.string.columns_grid
        RackKind.Staggered -> R.string.columns_staggered
        RackKind.Shelves -> R.string.columns_shelves
        RackKind.Bins -> R.string.columns_bins
    }

/**
 * "B3", "B3, back", "Shelf 2, back, position 3", "Shelf 1, back, top layer, position 3" or
 * "Bin B3", for people (files use "B3-2"). One order everywhere: shelf, side, layer, position.
 */
/** A no-break space before each number ("position 5"), so a number never starts a line alone. */
internal fun keepNumbersAttached(label: String): String = label.replace(Regex(" (?=\\d)"), "\u00A0")

fun Rack.displayLabel(slot: Slot, resources: Resources): String = keepNumbersAttached(slotLabel(slot, resources))

private fun Rack.slotLabel(slot: Slot, resources: Resources): String {
    // No side on a row one bottle deep, even when other rows are deeper (unless an older
    // version put a bottle behind it: that one reads "back").
    if (kind == RackKind.Bins || shownPositions(slot.row, slot.depth) <= 1) return displayLabel(slot.cell, resources)
    val side = depthName(slot.row, slot.depth, resources)
    // A fridge with bottles in layers names the side before the layer: "Shelf 3, front, bottom layer, position 2".
    shelfPlace(slot.row)?.let { place ->
        val res = when {
            place.layers == 1 -> R.string.place_shelf_band_single
            place.layer == 0 -> R.string.place_shelf_band_bottom
            place.layer == place.layers - 1 -> R.string.place_shelf_band_top
            else -> R.string.place_shelf_band_middle
        }
        return resources.getString(res, place.shelf + 1, side, slot.column + 1)
    }
    // Shelves one to three deep: "Shelf 2, back, position 3".
    if (kind == RackKind.Shelves) return resources.getString(R.string.place_shelf_band_single, slot.row + 1, side, slot.column + 1)
    return resources.getString(R.string.place_with_depth, displayLabel(slot.cell, resources), side)
}

/** Front, middle or back of a position in this row (rows can differ in depth). */
fun Rack.depthName(row: Int, depth: Int, resources: Resources): String = resources.getString(
    when (depth) {
        0 -> R.string.depth_front
        shownPositions(row, depth) - 1 -> R.string.depth_back
        else -> R.string.depth_middle
    },
)

fun Rack.displayLabel(cell: RackCell, resources: Resources): String = keepNumbersAttached(cellLabel(cell, resources))

private fun Rack.cellLabel(cell: RackCell, resources: Resources): String {
    // A fridge with bottles in layers: "Shelf 1, top layer, position 3".
    shelfPlace(cell.row)?.let { place ->
        val res = when {
            place.layers == 1 -> R.string.place_shelf_layer_single
            place.layer == 0 -> R.string.place_shelf_layer_bottom
            place.layer == place.layers - 1 -> R.string.place_shelf_layer_top
            else -> R.string.place_shelf_layer_middle
        }
        return resources.getString(res, place.shelf + 1, cell.column + 1)
    }
    return displayLabelByKind(cell, resources)
}

/** "Upper zone" and "Lower zone" for a fridge with two zones, else "Zone 2". */
fun Rack.zoneName(zone: Int, resources: Resources): String = when {
    zoneCount == 2 && zone == 0 -> resources.getString(R.string.rack_zone_upper)
    zoneCount == 2 -> resources.getString(R.string.rack_zone_lower)
    else -> resources.getString(R.string.rack_zone_number, zone + 1)
}

private fun Rack.displayLabelByKind(cell: RackCell, resources: Resources): String = when (kind) {
    RackKind.Grid, RackKind.Staggered -> label(cell)
    // Shelves are numbered from the top, as in layered fridges.
    RackKind.Shelves -> resources.getString(R.string.place_shelf, cell.row + 1, cell.column + 1)
    RackKind.Bins -> resources.getString(R.string.place_bin, label(cell))
}

/** "Main rack · Shelf 2, back, position 3", the storage note, or null. */
fun Cellar.displayLocation(bottle: Bottle, resources: Resources): String? {
    val slot = bottle.slot
    val rack = slot?.let { rack(it.rackId) }
    if (slot != null && rack != null) return "${rack.name} · ${rack.displayLabel(slot, resources)}"
    return locationLabel(bottle)
}

@Composable
fun Cellar.displayLocation(bottle: Bottle): String? = displayLocation(bottle, LocalResources.current)

val DishCategory.labelRes: Int
    get() = when (this) {
        DishCategory.RedMeat -> R.string.dish_red_meat
        DishCategory.Poultry -> R.string.dish_poultry
        DishCategory.Pork -> R.string.dish_pork
        DishCategory.Game -> R.string.dish_game
        DishCategory.Fish -> R.string.dish_fish
        DishCategory.Shellfish -> R.string.dish_shellfish
        DishCategory.Cheese -> R.string.dish_cheese
        DishCategory.Spicy -> R.string.dish_spicy
        DishCategory.Vegetarian -> R.string.dish_vegetarian
        DishCategory.Pasta -> R.string.dish_pasta
        DishCategory.Dessert -> R.string.dish_dessert
        DishCategory.Aperitif -> R.string.dish_aperitif
    }

val PairingReason.labelRes: Int
    get() = when (this) {
        PairingReason.Style -> R.string.reason_style
        PairingReason.Grape -> R.string.reason_grape
        PairingReason.Region -> R.string.reason_region
        PairingReason.ReadyToDrink -> R.string.reason_ready
    }

/**
 * Common bottle sizes in ml with their labels, by volume: also 50 cl, the Jura clavelin (62 cl),
 * the Swiss 70 cl and the litre.
 */
val bottleSizes = listOf(
    375 to R.string.size_375, 500 to R.string.size_500, 620 to R.string.size_620, 700 to R.string.size_700,
    750 to R.string.size_750, 1000 to R.string.size_1000, 1500 to R.string.size_1500, 3000 to R.string.size_3000,
)

/** The sizes as TalkBack reads them, units in words. */
private val spokenSizes = mapOf(
    375 to R.string.size_375_spoken, 500 to R.string.size_500_spoken, 620 to R.string.size_620_spoken,
    700 to R.string.size_700_spoken, 750 to R.string.size_750_spoken, 1000 to R.string.size_1000_spoken,
    1500 to R.string.size_1500_spoken, 3000 to R.string.size_3000_spoken,
)

/** "75 cl", "37,5 cl", "1,5 l": the volume alone, numbers as written in [locale]. */
fun volumeText(sizeML: Int, locale: java.util.Locale = java.util.Locale.getDefault()): String {
    val format = java.text.NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
    }
    return if (sizeML >= 1000) "${format.format(sizeML / 1000.0)}\u00A0l" else "${format.format(sizeML / 10.0)}\u00A0cl"
}

@Composable
fun sizeLabel(sizeML: Int): String {
    val known = bottleSizes.firstOrNull { it.first == sizeML }
    return if (known != null) stringResource(known.second) else stringResource(R.string.size_other, sizeML)
}

@Composable
fun spokenSizeLabel(sizeML: Int): String =
    spokenSizes[sizeML]?.let { stringResource(it) } ?: stringResource(R.string.size_other_spoken, sizeML)

@Composable
fun bottleCount(count: Int): String = pluralStringResource(R.plurals.bottle_count, count, count)

/** Between the parts of a line: the no-break space keeps "·" at the end of a line, never at the start of the next. */
const val SEPARATOR = "\u00A0· "

/** "Red · Domaine X · Pauillac": the style in words, then producer and appellation or region, as on Apple. */
@Composable
fun Wine.styleLine(): String =
    listOfNotNull(color?.let { stringResource(it.labelRes) }, producer, appellation ?: region)
        .filter { it.isNotBlank() }.joinToString(SEPARATOR)

/** "producer · appellation", else region or country, skipping empty parts, as on Apple. */
fun Wine.origin(): String =
    listOfNotNull(producer, appellation ?: region ?: country).filter { it.isNotBlank() }.joinToString(SEPARATOR)

fun UiText.resolve(resources: Resources): String = when (this) {
    is UiText.Res -> resources.getString(id, *args.toTypedArray())
    is UiText.Plural -> resources.getQuantityString(id, count, count)
    is UiText.Built -> build(resources)
}

@Composable
fun UiText.resolve(): String = resolve(LocalResources.current)

fun sampleLabels(resources: Resources) = SampleData.Labels(
    mainRack = resources.getString(R.string.sample_main_rack),
    piedmont = resources.getString(R.string.sample_region_piedmont),
    kakheti = resources.getString(R.string.sample_region_kakheti),
    italy = resources.getString(R.string.sample_country_italy),
    switzerland = resources.getString(R.string.sample_country_switzerland),
    georgia = resources.getString(R.string.sample_country_georgia),
    wineFridge = resources.getString(R.string.sample_wine_fridge),
    tastingNote = resources.getString(R.string.sample_note),
    kitchen = resources.getString(R.string.sample_kitchen),
    occasion = resources.getString(R.string.sample_occasion),
    nose = resources.getString(R.string.sample_nose),
    palate = resources.getString(R.string.sample_palate),
    pairing = resources.getString(R.string.sample_pairing),
    bins = resources.getString(R.string.sample_bins),
    deepRack = resources.getString(R.string.sample_deep_rack),
    honeycomb = resources.getString(R.string.sample_honeycomb),
    stairs = resources.getString(R.string.sample_stairs),
    largeFridge = resources.getString(R.string.sample_large_fridge),
    headToTailFridge = resources.getString(R.string.sample_head_to_tail_fridge),
    friendsPlace = resources.getString(R.string.sample_friends_place),
    friendsNote = resources.getString(R.string.sample_friends_note),
    restaurantPlace = resources.getString(R.string.sample_restaurant_place),
    restaurantNote = resources.getString(R.string.sample_restaurant_note),
    tagToBuy = resources.getString(R.string.sample_tag_to_buy),
    tagGift = resources.getString(R.string.sample_tag_gift),
    merchant = resources.getString(R.string.sample_merchant),
    tagBirthYear = resources.getString(R.string.sample_tag_birth_year),
    occasion2 = resources.getString(R.string.sample_occasion2),
    goldMedal = resources.getString(R.string.sample_gold_medal),
    criticNote = resources.getString(R.string.sample_critic_note),
)

val Discovery.Setting.labelRes: Int
    get() = when (this) {
        Discovery.Setting.Friends -> R.string.setting_friends
        Discovery.Setting.Restaurant -> R.string.setting_restaurant
        Discovery.Setting.Travel -> R.string.setting_travel
        Discovery.Setting.Shop -> R.string.setting_shop
        Discovery.Setting.Tasting -> R.string.setting_tasting
        Discovery.Setting.Other -> R.string.setting_other
    }

val Discovery.Setting.icon: ImageVector
    get() = when (this) {
        Discovery.Setting.Friends -> Icons.Outlined.Group
        Discovery.Setting.Restaurant -> Icons.Outlined.Restaurant
        Discovery.Setting.Travel -> Icons.Outlined.Flight
        Discovery.Setting.Shop -> Icons.Outlined.ShoppingBag
        Discovery.Setting.Tasting -> Icons.Outlined.WineBar
        Discovery.Setting.Other -> Icons.Outlined.Place
    }

object Formats {
    private fun locale() = Locale.getDefault()

    fun day(instant: Instant): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale()).format(instant.atZone(ZoneId.systemDefault()))

    /** "12 Oct" or "Oct 12", without the year. */
    fun dayMonth(instant: Instant): String {
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale(), "dMMM")
        return DateTimeFormatter.ofPattern(pattern, locale()).format(instant.atZone(ZoneId.systemDefault()))
    }

    /** "Oct 22" this year, the full date for an earlier year. */
    fun recentDay(instant: Instant): String =
        if (instant.atZone(ZoneId.systemDefault()).year == java.time.LocalDate.now().year) dayMonth(instant) else day(instant)

    fun money(amount: BigDecimal, code: String?): String {
        val format = NumberFormat.getCurrencyInstance(locale())
        // Codes are kept as typed ("eur" from a CSV): ISO codes are upper case.
        runCatching { if (code != null) format.currency = Currency.getInstance(code.trim().uppercase(Locale.ROOT)) }
        return format.format(amount)
    }

    /** "1,234" or "1 234": a count with the locale's grouping. */
    fun integer(value: Int): String = NumberFormat.getIntegerInstance(locale()).format(value)

    fun decimal(value: Double, digits: Int = 1): String =
        NumberFormat.getNumberInstance(locale()).apply {
            maximumFractionDigits = digits
            minimumFractionDigits = digits
        }.format(value)
}

/** Currencies offered in settings and the add form; the stored code can be any ISO 4217 code. */
val commonCurrencies = listOf("EUR", "CHF", "USD", "GBP", "CAD", "AUD", "NZD", "JPY", "SEK", "NOK", "DKK", "ZAR")

/** The occasion chips, the same on every device (labels from the shared table, translated here). */
val Occasion.labelRes: Int
    get() = when (this) {
        Occasion.DinnerAtHome -> R.string.occasion_dinner
        Occasion.WithFriends -> R.string.occasion_friends
        Occasion.Celebration -> R.string.occasion_celebration
        Occasion.Restaurant -> R.string.occasion_restaurant
        Occasion.Weeknight -> R.string.occasion_weeknight
        Occasion.Tasting -> R.string.occasion_tasting
    }
