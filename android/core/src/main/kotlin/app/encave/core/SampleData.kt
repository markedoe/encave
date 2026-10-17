package app.encave.core

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * A small fictional cellar for trying the app and for previews.
 * Producers are invented; appellations are real places.
 */
object SampleData {
    /** Labels are passed in so the app can localize them. */
    data class Labels(
        val mainRack: String = "Main rack",
        val wineFridge: String = "Wine fridge",
        val bins: String = "Diamond bins",
        val deepRack: String = "Double-depth rack",
        val honeycomb: String = "Honeycomb rack",
        val stairs: String = "Under the stairs",
        val tastingNote: String = "Silky, red fruit, long finish.",
        val kitchen: String = "Kitchen",
        val occasion: String = "Dinner with friends",
        val nose: String = "Cherry, violet, a touch of undergrowth",
        val palate: String = "Fine tannins, fresh acidity",
        val pairing: String = "Roast duck",
        val friendsPlace: String = "At Julie and Marc's",
        val friendsNote: String = "Very fresh, perfect with the cheese platter.",
        val restaurantPlace: String = "Bistro on the harbour, Lisbon",
        val restaurantNote: String = "Mineral and salty, great with grilled fish.",
        val tagToBuy: String = "For summer",
        val tagGift: String = "Gift idea",
    )

    fun cellar(year: Int = LocalDate.now().year, labels: Labels = Labels(), now: Instant = ReferenceDates.now()): Cellar {
        val cellar = Cellar()
        val main = Rack(name = labels.mainRack, rows = 6, columns = 8)
        val small = Rack(name = labels.wineFridge, kind = RackKind.Shelves, rows = 4, columns = 6, depth = 2)
        val bins = Rack(name = labels.bins, kind = RackKind.Bins, rows = 2, columns = 3, binCapacity = 10)
        // One rack of every kind, so the sample shows them all.
        val deep = Rack(name = labels.deepRack, rows = 4, columns = 6, depth = 2)
        val honeycomb = Rack(name = labels.honeycomb, kind = RackKind.Staggered, rows = 5, columns = 8)
        val stairs = Rack(name = labels.stairs, rows = 5, columns = 6, rowColumns = listOf(2, 3, 4, 5, 6))
        listOf(main, small, bins, deep, honeycomb, stairs).forEach { cellar.addRack(it) }

        val entries: List<Triple<Wine, Int, Rack?>> = listOf(
            Triple(
                Wine(name = "Clos des Tilleuls", producer = "Domaine Arnaud", vintage = year - 9, color = WineColor.Red,
                    region = "Bordeaux", country = "France", appellation = "Pauillac", grapes = listOf("Cabernet Sauvignon", "Merlot"),
                    drinkFrom = year - 2, drinkUntil = year + 8),
                3, main,
            ),
            Triple(
                Wine(name = "Les Pierres Blanches", producer = "Maison Clément", vintage = year - 4, color = WineColor.White,
                    region = "Bourgogne", country = "France", appellation = "Meursault", grapes = listOf("Chardonnay"),
                    drinkFrom = year - 1, drinkUntil = year),
                2, main,
            ),
            Triple(
                Wine(name = "Cuvée du Lac", producer = "Cave des Coteaux", vintage = year - 2, color = WineColor.White,
                    region = "Vaud", country = "Switzerland", grapes = listOf("Chasselas"),
                    drinkFrom = year - 1, drinkUntil = year + 2),
                4, small,
            ),
            Triple(
                Wine(name = "Terrasses du Sud", producer = "Domaine Ferrand", vintage = year - 3, color = WineColor.Red,
                    region = "Rhône", country = "France", appellation = "Gigondas", grapes = listOf("Grenache", "Syrah"),
                    drinkFrom = year + 1, drinkUntil = year + 10),
                6, bins,
            ),
            Triple(
                Wine(name = "Rosé de Provence", producer = "Château Lumière", vintage = year - 5, color = WineColor.Rose,
                    region = "Provence", country = "France", grapes = listOf("Grenache", "Cinsault"),
                    drinkFrom = year - 4, drinkUntil = year - 2),
                1, null,
            ),
            Triple(
                Wine(name = "Brut Réserve", producer = "Maison Aubry", color = WineColor.Sparkling,
                    region = "Champagne", country = "France", grapes = listOf("Chardonnay", "Pinot Noir")),
                2, small,
            ),
            Triple(
                Wine(name = "Vendanges Tardives", producer = "Domaine Weber", vintage = year - 12, color = WineColor.Dessert,
                    region = "Alsace", country = "France", grapes = listOf("Gewurztraminer"),
                    drinkFrom = year - 6, drinkUntil = year + 15),
                1, main,
            ),
            Triple(
                Wine(name = "Riserva Vigna Alta", producer = "Cascina Ferri", vintage = year - 10, color = WineColor.Red,
                    region = "Piedmont", country = "Italy", appellation = "Barolo", grapes = listOf("Nebbiolo"),
                    drinkFrom = year - 2, drinkUntil = year + 12),
                8, deep,
            ),
            Triple(
                Wine(name = "Savagnin Ouillé", producer = "Domaine des Granges", vintage = year - 5, color = WineColor.White,
                    region = "Jura", country = "France", appellation = "Arbois", grapes = listOf("Savagnin"),
                    drinkFrom = year - 2, drinkUntil = year + 6),
                5, honeycomb,
            ),
            Triple(
                Wine(name = "Qvevri Amber", producer = "Marani Tsiskari", vintage = year - 3, color = WineColor.Orange,
                    region = "Kakheti", country = "Georgia", grapes = listOf("Rkatsiteli"),
                    drinkFrom = year - 1, drinkUntil = year + 4),
                3, honeycomb,
            ),
            Triple(
                Wine(name = "Vintage Port", producer = "Quinta da Serra", vintage = year - 15, color = WineColor.Fortified,
                    region = "Douro", country = "Portugal", appellation = "Porto", grapes = listOf("Touriga Nacional"),
                    drinkFrom = year - 5, drinkUntil = year + 25),
                4, stairs,
            ),
        )

        val prices = listOf(38, 54, 16, 24, 14, 36, 29, 62, 27, 22, 48).map { BigDecimal(it) }
        entries.forEachIndexed { index, (wine, quantity, rack) ->
            val slots = rack?.let { cellar.freeSlots(it).take(quantity) } ?: emptyList()
            val date = now - Duration.ofDays(30L * (index + 1))
            val purchase = PurchaseInfo(
                date = date, price = prices[index % prices.size],
                currency = cellar.settings.currency, storageNote = if (rack == null) labels.kitchen else null,
            )
            runCatching { cellar.addBottles(wine, quantity, slots, purchase, addedAt = date) }
        }

        // A few past tastings for the journal.
        val tasted = Wine(name = "Vieilles Vignes", producer = "Domaine Arnaud", vintage = year - 7, color = WineColor.Red,
            region = "Bourgogne", country = "France", appellation = "Gevrey-Chambertin", grapes = listOf("Pinot Noir"),
            drinkFrom = year - 3, drinkUntil = year + 4)
        val bottles = cellar.addBottles(tasted, quantity = 2, addedAt = now)
        cellar.drink(bottles[0].id, Tasting(date = now - Duration.ofDays(12), rating = 5,
            note = labels.tastingNote, occasion = labels.occasion, nose = labels.nose,
            palate = labels.palate, foodPairing = labels.pairing))
        cellar.drink(bottles[1].id, Tasting(date = now - Duration.ofDays(40), rating = 4))

        // Wines found elsewhere, one of them on the shopping list.
        val atFriends = Wine(name = "Coteaux du Layon", producer = "Domaine des Sablons", vintage = year - 6,
            color = WineColor.Dessert, region = "Loire", country = "France", grapes = listOf("Chenin Blanc"))
        cellar.addDiscovery(
            Discovery(wineId = atFriends.id, date = now - Duration.ofDays(9), setting = Discovery.Setting.Friends,
                place = labels.friendsPlace, rating = 4, note = labels.friendsNote, tags = listOf(labels.tagGift), wantsToBuy = true),
            atFriends,
        )
        val onTrip = Wine(name = "Alvarinho Reserva", producer = "Quinta do Mar", vintage = year - 2, color = WineColor.White,
            region = "Vinho Verde", country = "Portugal", grapes = listOf("Alvarinho"))
        cellar.addDiscovery(
            Discovery(wineId = onTrip.id, date = now - Duration.ofDays(25), setting = Discovery.Setting.Restaurant,
                place = labels.restaurantPlace, rating = 5, note = labels.restaurantNote, tags = listOf(labels.tagToBuy),
                price = BigDecimal(32), currency = cellar.settings.currency, wantsToBuy = true),
            onTrip,
        )
        return cellar
    }
}
