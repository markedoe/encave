package app.encave.ui.components

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
import androidx.compose.material.icons.outlined.HelpOutline
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
import app.encave.core.DishCategory
import app.encave.core.Discovery
import app.encave.core.Maturity
import app.encave.core.PairingReason
import app.encave.core.RackLayout
import app.encave.core.SampleData
import app.encave.core.Wine
import app.encave.core.WineColor
import app.encave.ui.UiText
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
        Maturity.Unknown -> Icons.Outlined.HelpOutline
    }

/** Theme roles for maturity; always shown with an icon and a label. */
val Maturity.tint: Color
    @Composable @ReadOnlyComposable
    get() = when (this) {
        Maturity.DrinkSoon -> MaterialTheme.colorScheme.tertiary
        Maturity.Ready -> MaterialTheme.colorScheme.primary
        Maturity.PastPeak -> MaterialTheme.colorScheme.error
        Maturity.Young -> MaterialTheme.colorScheme.secondary
        Maturity.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
    }

val CellarSort.labelRes: Int
    get() = when (this) {
        CellarSort.Name -> R.string.sort_name
        CellarSort.Vintage -> R.string.sort_vintage
        CellarSort.RecentlyAdded -> R.string.sort_recent
        CellarSort.Readiness -> R.string.sort_readiness
        CellarSort.Region -> R.string.sort_region
    }

val RackLayout.labelRes: Int
    get() = when (this) {
        RackLayout.Grid -> R.string.layout_grid
        RackLayout.Diamond -> R.string.layout_diamond
    }

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

/** Common bottle sizes in ml with their labels. */
val bottleSizes = listOf(375 to R.string.size_375, 750 to R.string.size_750, 1500 to R.string.size_1500, 3000 to R.string.size_3000)

@Composable
fun sizeLabel(sizeML: Int): String {
    val known = bottleSizes.firstOrNull { it.first == sizeML }
    return if (known != null) stringResource(known.second) else stringResource(R.string.size_other, sizeML)
}

@Composable
fun bottleCount(count: Int): String = pluralStringResource(R.plurals.bottle_count, count, count)

/** "producer · region · country", skipping empty parts. */
fun Wine.origin(): String = listOfNotNull(producer, region ?: country).filter { it.isNotBlank() }.joinToString(" · ")

fun UiText.resolve(resources: Resources): String = when (this) {
    is UiText.Res -> resources.getString(id, *args.toTypedArray())
    is UiText.Plural -> resources.getQuantityString(id, count, count)
}

@Composable
fun UiText.resolve(): String = resolve(LocalResources.current)

fun sampleLabels(resources: Resources) = SampleData.Labels(
    mainRack = resources.getString(R.string.sample_main_rack),
    wineFridge = resources.getString(R.string.sample_wine_fridge),
    tastingNote = resources.getString(R.string.sample_note),
    kitchen = resources.getString(R.string.sample_kitchen),
    occasion = resources.getString(R.string.sample_occasion),
    nose = resources.getString(R.string.sample_nose),
    palate = resources.getString(R.string.sample_palate),
    pairing = resources.getString(R.string.sample_pairing),
    friendsPlace = resources.getString(R.string.sample_friends_place),
    friendsNote = resources.getString(R.string.sample_friends_note),
    restaurantPlace = resources.getString(R.string.sample_restaurant_place),
    restaurantNote = resources.getString(R.string.sample_restaurant_note),
    tagToBuy = resources.getString(R.string.sample_tag_to_buy),
    tagGift = resources.getString(R.string.sample_tag_gift),
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

    fun money(amount: BigDecimal, code: String?): String {
        val format = NumberFormat.getCurrencyInstance(locale())
        runCatching { if (code != null) format.currency = Currency.getInstance(code) }
        return format.format(amount)
    }

    fun decimal(value: Double, digits: Int = 1): String =
        NumberFormat.getNumberInstance(locale()).apply {
            maximumFractionDigits = digits
            minimumFractionDigits = digits
        }.format(value)
}

/** Currencies offered in settings and the add form; the stored code can be any ISO 4217 code. */
val commonCurrencies = listOf("EUR", "CHF", "USD", "GBP", "CAD", "AUD", "NZD", "JPY", "SEK", "NOK", "DKK", "ZAR")
