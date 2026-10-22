package app.encave.core

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

/**
 * A small fictional cellar for trying the app and for previews.
 * Producers are invented; appellations are real places.
 *
 * Its IDs are fixed ([id], the same as in Swift), so the app can tell the sample apart
 * from the user's own records and remove it on its own.
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
        val largeFridge: String = "Large wine fridge",
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
        val merchant: String = "Wine merchant",
        val tagBirthYear: String = "Birth year",
        val occasion2: String = "Christmas dinner",
    )

    /** A wine fridge with two zones: layers of each shelf from the top, bottles of each row from the top. Also the editor's preset. */
    val TWO_ZONE_FRIDGE_LAYERS = listOf(1, 2, 1, 1, 1, 2, 2, 2, 1, 1)
    val TWO_ZONE_FRIDGE_ROWS = listOf(6, 5, 6, 6, 6, 6, 4, 5, 4, 5, 4, 5, 6, 6)

    fun cellar(year: Int = LocalDate.now().year, labels: Labels = Labels(), now: Instant = ReferenceDates.now()): Cellar {
        val cellar = Cellar()
        val ids = Ids()
        val main = Rack(id = ids.next(Kind.Rack), name = labels.mainRack, rows = 6, columns = 8)
        val small = Rack(id = ids.next(Kind.Rack), name = labels.wineFridge, kind = RackKind.Shelves, rows = 4, columns = 6, depth = 2)
        val bins = Rack(id = ids.next(Kind.Rack), name = labels.bins, kind = RackKind.Bins, rows = 2, columns = 3, binCapacity = 10)
        // One rack of every kind, so the sample shows them all.
        val deep = Rack(id = ids.next(Kind.Rack), name = labels.deepRack, rows = 4, columns = 6, depth = 2)
        val honeycomb = Rack(id = ids.next(Kind.Rack), name = labels.honeycomb, kind = RackKind.Staggered, rows = 5, columns = 8)
        val stairs = Rack(id = ids.next(Kind.Rack), name = labels.stairs, rows = 5, columns = 6, rowColumns = listOf(2, 3, 4, 5, 6))
        // A fridge with two temperature zones (shelves 1–4, then 5–10), two deep: shelf 2 holds
        // 6 bottles and 5 on top, shelves 6–8 hold 5 and 4 on top, the others one layer of 6.
        val largeFridge = Rack(
            id = ids.next(Kind.Rack), name = labels.largeFridge, kind = RackKind.Staggered, rows = TWO_ZONE_FRIDGE_ROWS.size, columns = 6, depth = 2,
            rowColumns = TWO_ZONE_FRIDGE_ROWS, shelfLayers = TWO_ZONE_FRIDGE_LAYERS, zoneBreaks = listOf(4),
        )
        listOf(main, small, bins, deep, honeycomb, stairs, largeFridge).forEach { cellar.addRack(it) }

        val entries: List<Triple<Wine, Int, Rack?>> = listOf(
            Triple(
                Wine(id = ids.next(Kind.Wine), name = "Clos des Tilleuls", producer = "Domaine Arnaud", vintage = year - 9, color = WineColor.Red,
                    region = "Bordeaux", country = "France", appellation = "Pauillac", grapes = listOf("Cabernet Sauvignon", "Merlot"),
                    drinkFrom = year - 2, drinkUntil = year + 8),
                3, main,
            ),
            Triple(
                Wine(id = ids.next(Kind.Wine), name = "Les Pierres Blanches", producer = "Maison Clément", vintage = year - 4, color = WineColor.White,
                    region = "Bourgogne", country = "France", appellation = "Meursault", grapes = listOf("Chardonnay"),
                    drinkFrom = year - 1, drinkUntil = year),
                2, main,
            ),
            Triple(
                Wine(id = ids.next(Kind.Wine), name = "Cuvée du Lac", producer = "Cave des Coteaux", vintage = year - 2, color = WineColor.White,
                    region = "Vaud", country = "Switzerland", grapes = listOf("Chasselas"),
                    drinkFrom = year - 1, drinkUntil = year + 2),
                4, small,
            ),
            Triple(
                Wine(id = ids.next(Kind.Wine), name = "Terrasses du Sud", producer = "Domaine Ferrand", vintage = year - 3, color = WineColor.Red,
                    region = "Rhône", country = "France", appellation = "Gigondas", grapes = listOf("Grenache", "Syrah"),
                    drinkFrom = year + 1, drinkUntil = year + 10),
                6, bins,
            ),
            Triple(
                Wine(id = ids.next(Kind.Wine), name = "Rosé de Provence", producer = "Château Lumière", vintage = year - 5, color = WineColor.Rose,
                    region = "Provence", country = "France", grapes = listOf("Grenache", "Cinsault"),
                    drinkFrom = year - 4, drinkUntil = year - 2),
                1, null,
            ),
            Triple(
                Wine(id = ids.next(Kind.Wine), name = "Brut Réserve", producer = "Maison Aubry", color = WineColor.Sparkling,
                    region = "Champagne", country = "France", grapes = listOf("Chardonnay", "Pinot Noir")),
                2, small,
            ),
            Triple(
                Wine(id = ids.next(Kind.Wine), name = "Vendanges Tardives", producer = "Domaine Weber", vintage = year - 12, color = WineColor.Dessert,
                    region = "Alsace", country = "France", grapes = listOf("Gewurztraminer"),
                    drinkFrom = year - 6, drinkUntil = year + 15),
                1, main,
            ),
            Triple(
                Wine(id = ids.next(Kind.Wine), name = "Riserva Vigna Alta", producer = "Cascina Ferri", vintage = year - 10, color = WineColor.Red,
                    region = "Piedmont", country = "Italy", appellation = "Barolo", grapes = listOf("Nebbiolo"),
                    drinkFrom = year - 2, drinkUntil = year + 12),
                8, deep,
            ),
            Triple(
                Wine(id = ids.next(Kind.Wine), name = "Savagnin Ouillé", producer = "Domaine des Granges", vintage = year - 5, color = WineColor.White,
                    region = "Jura", country = "France", appellation = "Arbois", grapes = listOf("Savagnin"),
                    drinkFrom = year - 2, drinkUntil = year + 6),
                5, honeycomb,
            ),
            Triple(
                Wine(id = ids.next(Kind.Wine), name = "Qvevri Amber", producer = "Marani Tsiskari", vintage = year - 3, color = WineColor.Orange,
                    region = "Kakheti", country = "Georgia", grapes = listOf("Rkatsiteli"),
                    drinkFrom = year - 1, drinkUntil = year + 4),
                3, largeFridge,
            ),
            Triple(
                Wine(id = ids.next(Kind.Wine), name = "Vintage Port", producer = "Quinta da Serra", vintage = year - 15, color = WineColor.Fortified,
                    region = "Douro", country = "Portugal", appellation = "Porto", grapes = listOf("Touriga Nacional"),
                    drinkFrom = year - 5, drinkUntil = year + 25, tags = listOf(labels.tagBirthYear)),
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
            runCatching { cellar.addBottles(wine, quantity, slots, purchase, addedAt = date) }.onSuccess { ids.rekey(it, cellar) }
        }

        // Two bottles kept for an occasion.
        cellar.wines.values.firstOrNull { it.name == "Riserva Vigna Alta" }?.let { barolo ->
            runCatching { cellar.reserve(cellar.bottlesOf(barolo.id).take(2).map { it.id }, labels.occasion2) }
        }

        // Last year's vintage bought en primeur, not delivered yet.
        val primeur = Wine(id = ids.next(Kind.Wine), name = "Clos des Tilleuls", producer = "Domaine Arnaud", vintage = year - 1, color = WineColor.Red,
            region = "Bordeaux", country = "France", appellation = "Pauillac",
            grapes = listOf("Cabernet Sauvignon", "Merlot"), drinkFrom = year + 6, drinkUntil = year + 20)
        val ordered = cellar.addBottles(
            primeur, quantity = 6, addedAt = now,
            purchase = PurchaseInfo(
                date = now - Duration.ofDays(60), price = BigDecimal(41), currency = cellar.settings.currency,
                place = labels.merchant, isOrdered = true, expectedAt = now + Duration.ofDays(75),
            ),
        )

        ids.rekey(ordered, cellar)

        // A few past tastings for the journal.
        val tasted = Wine(id = ids.next(Kind.Wine), name = "Vieilles Vignes", producer = "Domaine Arnaud", vintage = year - 7, color = WineColor.Red,
            region = "Bourgogne", country = "France", appellation = "Gevrey-Chambertin", grapes = listOf("Pinot Noir"),
            drinkFrom = year - 3, drinkUntil = year + 4)
        val bottles = ids.rekey(cellar.addBottles(tasted, quantity = 2, addedAt = now), cellar)
        cellar.drink(bottles[0].id, Tasting(date = now - Duration.ofDays(12), rating = 5,
            note = labels.tastingNote, occasion = labels.occasion, nose = labels.nose,
            palate = labels.palate, foodPairing = labels.pairing))
        cellar.drink(bottles[1].id, Tasting(date = now - Duration.ofDays(40), rating = 4))

        // Wines found elsewhere, one of them on the shopping list.
        val atFriends = Wine(id = ids.next(Kind.Wine), name = "Coteaux du Layon", producer = "Domaine des Sablons", vintage = year - 6,
            color = WineColor.Dessert, region = "Loire", country = "France", grapes = listOf("Chenin Blanc"))
        cellar.addDiscovery(
            Discovery(id = ids.next(Kind.Discovery), wineId = atFriends.id, date = now - Duration.ofDays(9), setting = Discovery.Setting.Friends,
                place = labels.friendsPlace, rating = 4, note = labels.friendsNote, tags = listOf(labels.tagGift), wantsToBuy = true),
            atFriends,
        )
        val onTrip = Wine(id = ids.next(Kind.Wine), name = "Alvarinho Reserva", producer = "Quinta do Mar", vintage = year - 2, color = WineColor.White,
            region = "Vinho Verde", country = "Portugal", grapes = listOf("Alvarinho"))
        cellar.addDiscovery(
            Discovery(id = ids.next(Kind.Discovery), wineId = onTrip.id, date = now - Duration.ofDays(25), setting = Discovery.Setting.Restaurant,
                place = labels.restaurantPlace, rating = 5, note = labels.restaurantNote, tags = listOf(labels.tagToBuy),
                price = BigDecimal(32), currency = cellar.settings.currency, wantsToBuy = true),
            onTrip,
        )
        return cellar
    }

    // region Fixed IDs

    /** The kinds of records of the sample, each numbered from 0. */
    enum class Kind(val raw: String) { Rack("rack"), Wine("wine"), Bottle("bottle"), Discovery("discovery") }

    /** The namespace of the sample's name-based IDs. */
    val NAMESPACE: UUID = UUID.fromString("3B0B9C8E-5E7A-4F0D-9A61-2D4C8E1F7A20")

    /** More than the sample uses of each kind, so a longer sample keeps working. */
    private const val MAXIMUM_PER_KIND = 128

    /**
     * The ID of the [index]-th record of a kind in the sample: a version 5 UUID of the name
     * "rack-0", "wine-3"... in [NAMESPACE]. The same in Swift.
     */
    fun id(kind: Kind, index: Int): UUID = nameBasedUuid(NAMESPACE, "${kind.raw}-$index")

    /** Every ID the sample may use. */
    val ids: Set<UUID> by lazy { Kind.entries.flatMap { kind -> (0 until MAXIMUM_PER_KIND).map { id(kind, it) } }.toSet() }

    /** A version 5 (SHA-1, name-based) UUID, as in RFC 9562. */
    internal fun nameBasedUuid(namespace: UUID, name: String): UUID {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(ByteBuffer.allocate(16).putLong(namespace.mostSignificantBits).putLong(namespace.leastSignificantBits).array())
        val hash = digest.digest(name.toByteArray(Charsets.UTF_8)).copyOf(16)
        hash[6] = ((hash[6].toInt() and 0x0F) or 0x50).toByte()
        hash[8] = ((hash[8].toInt() and 0x3F) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(hash)
        return UUID(buffer.long, buffer.long)
    }

    /** Hands out the sample's IDs in order while it is built. */
    private class Ids {
        private val counts = mutableMapOf<Kind, Int>()

        fun next(kind: Kind): UUID {
            val index = counts[kind] ?: 0
            counts[kind] = index + 1
            return id(kind, index)
        }

        /** Gives bottles just added their fixed IDs, in order; returns them as now stored. */
        fun rekey(added: List<Bottle>, cellar: Cellar): List<Bottle> = added.map { bottle ->
            bottle.copy(id = next(Kind.Bottle)).also { cellar.rekeyBottle(bottle.id, it) }
        }
    }

    // endregion
}

/** Some records of the sample cellar are still here. */
val Cellar.containsSample: Boolean
    get() {
        val ids = SampleData.ids
        return wines.keys.any { it in ids } || bottles.keys.any { it in ids } || racks.any { it.id in ids } ||
            discoveries.keys.any { it in ids }
    }

/**
 * Nothing but the sample cellar (or what is left of it): joining another device replaces
 * it rather than putting it together with the real cellar.
 */
val Cellar.isOnlySample: Boolean
    get() {
        val ids = SampleData.ids
        return !isEmpty && wines.keys.all { it in ids } && bottles.keys.all { it in ids } && racks.all { it.id in ids } &&
            discoveries.keys.all { it in ids }
    }

/**
 * The cellar without the sample's records, as a new cellar. A sample wine stays while one of
 * the user's bottles or discoveries refers to it, and a sample rack while it holds one of the
 * user's bottles. Settings are kept.
 */
fun Cellar.removingSample(): Cellar {
    val ids = SampleData.ids
    val keptBottles = bottles.values.filter { it.id !in ids }
    val keptDiscoveries = discoveries.values.filter { it.id !in ids }
    val usedWines = keptBottles.map { it.wineId }.toSet() + keptDiscoveries.map { it.wineId }
    val usedRacks = keptBottles.mapNotNull { it.slot?.rackId }.toSet()
    return Cellar(
        wines = wines.values.filter { it.id !in ids || it.id in usedWines },
        bottles = keptBottles,
        racks = racks.filter { it.id !in ids || it.id in usedRacks },
        settings = settings,
        discoveries = keptDiscoveries,
    ).also { it.schemaVersion = schemaVersion }
}
