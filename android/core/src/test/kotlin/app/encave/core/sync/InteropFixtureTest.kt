package app.encave.core.sync

import app.encave.core.Bottle
import app.encave.core.Cellar
import app.encave.core.CellarFilter
import app.encave.core.CellarJson
import app.encave.core.CellarSettings
import app.encave.core.Discovery
import app.encave.core.InstantSerializer
import app.encave.core.Maturity
import app.encave.core.Rack
import app.encave.core.RackKind
import app.encave.core.SavedSearch
import app.encave.core.Slot
import app.encave.core.Tasting
import app.encave.core.Wine
import app.encave.core.WineColor
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The files shared with CaveKit, in `fixtures/interop` at the top of the repository:
 * reads what Swift wrote (`swift-*.json`, made by CaveKit's InteropFixtureTests) and
 * writes it back as the same JSON, and checks that Kotlin still writes `kotlin-*.json`,
 * which the Swift tests read. Run with `ENCAVE_WRITE_FIXTURES=1` to write the Kotlin
 * files again after a format change. The model is the same as `InteropFixture` in Swift.
 */
object InteropFixture {
    fun id(suffix: String): UUID = UUID.fromString("00000000-0000-4000-8000-$suffix")
    fun date(seconds: Double): Instant = InstantSerializer.fromReferenceSeconds(seconds)

    val iphone = id("000000000001")
    val android = id("000000000002")
    val removed = id("000000000003")
    val cellarId = id("0000000000aa")
    val fullWine = id("000000000011")
    val plainWine = id("000000000012")
    val grid = id("000000000031")
    val bins = id("000000000032")
    val label = PhotoId.make("label".toByteArray())
    val back = PhotoId.make("back".toByteArray())
    val cover = PhotoId.make("cover".toByteArray())

    fun cellar(): Cellar {
        val wine = Wine(
            id = fullWine, name = "Château Test", producer = "Domaine d'Exemple", vintage = 2015, color = WineColor.Red,
            region = "Bourgogne", country = "France", appellation = "Gevrey-Chambertin", grapes = listOf("Pinot Noir", "Gamay"),
            drinkFrom = 2022, drinkUntil = 2035, notes = "1/2 bottle, \"quoted\"\nsecond line", photoId = label,
            barcodes = listOf("3760123456789"), tags = listOf("Gift", "for summer"), currentPrice = BigDecimal("45.9"),
            currentPriceCurrency = "EUR", currentPriceDate = date(790_000_000.5), extraPhotoIds = listOf(back), coverPhotoId = cover,
        )
        val plain = Wine(id = plainWine, name = "Vin de table")
        val bottles = listOf(
            Bottle(
                id = id("000000000021"), wineId = fullWine, slot = Slot(grid, 1, 2, 1), addedAt = date(782_000_000.25),
                purchaseDate = date(-1000.75), purchasePrice = BigDecimal("12.3"), currency = "EUR", purchasePlace = "Cave du coin",
                receivedFrom = "Paul", sizeML = 1500, reservedFor = "Paul's 40th",
            ),
            Bottle(
                id = id("000000000022"), wineId = fullWine, status = Bottle.Status.Consumed, storageNote = "Kitchen",
                addedAt = date(782_000_100.0), purchasePrice = BigDecimal("1234.5"), currency = "CHF",
                tasting = Tasting(
                    date(785_000_000.125), 4, "Lovely", "Dinner", appearance = "Ruby", nose = "Cherry", palate = "Silky",
                    finish = "Long", foodPairing = "Duck",
                ),
            ),
            Bottle(id = id("000000000023"), wineId = fullWine, status = Bottle.Status.Ordered, addedAt = date(782_000_200.0), expectedAt = date(783_000_000.0)),
            Bottle(id = id("000000000024"), wineId = plainWine, status = Bottle.Status.Gone, addedAt = date(700_000_000.0)),
            Bottle(id = id("000000000025"), wineId = plainWine, slot = Slot(bins, 0, 1, 3), addedAt = date(781_000_000.5), sizeML = 375),
        )
        val discovery = Discovery(
            id = id("000000000041"), wineId = plainWine, date = date(781_500_000.75), setting = Discovery.Setting.Restaurant,
            place = "Lyon", rating = 5, note = "By the glass", tags = listOf("Trip", "Lyon"), price = BigDecimal("8.5"),
            currency = "EUR", wantsToBuy = true, boughtAt = date(781_600_000.0),
        )
        val racks = listOf(
            Rack(grid, "Cave/left wall", RackKind.Grid, rows = 4, columns = 6, depth = 2, rowColumns = listOf(6, 6, 5, 4)),
            Rack(bins, "Bins", RackKind.Bins, rows = 2, columns = 3, binCapacity = 10),
        )
        val searches = listOf(
            SavedSearch.of(
                "Burgundy, ready",
                CellarFilter("bourgogne", setOf(WineColor.White, WineColor.Red), setOf(Maturity.Ready, Maturity.DrinkSoon), grid, "Gift", "Paul's 40th", "Pinot Noir"),
                id("000000000051"),
            ),
            SavedSearch.of("All", CellarFilter(), id("000000000052")),
        )
        return Cellar(listOf(wine, plain), bottles, racks, CellarSettings("CHF", SavedSearch.ordered(searches)), listOf(discovery))
    }

    fun state(): SyncState = SyncState(
        device = iphone, clock = 42,
        stamps = mapOf(
            "wine:$fullWine" to Stamp(3, iphone),
            "wine:$plainWine" to Stamp(7, android),
            "bottle:${id("000000000021")}" to Stamp(40, android),
            "bottle:${id("000000000099")}" to Stamp(41, iphone, deleted = true),
            "discovery:${id("000000000041")}" to Stamp(9, iphone),
            "rack:$grid" to Stamp(2, iphone),
            "search:${id("000000000051")}" to Stamp(11, android),
            "settings" to Stamp(12, android),
            "rack-order" to Stamp(13, iphone),
        ),
    )

    fun members(): Membership = Membership(
        cellarId,
        mapOf(
            iphone to Member(iphone, "Marie's iPhone", ByteArray(32) { 1 }, "ios", date(780_000_000.5), "i2a17946aea22c15127c53bac03a59f8a"),
            android to Member(android, "Pixel", ByteArray(32) { 2 }, "android", date(781_000_000.0)),
        ),
        mapOf(iphone to Stamp(1, iphone), android to Stamp(2, iphone), removed to Stamp(4, android, deleted = true)),
    )

    val exportedAt: Instant = date(790_000_000.0)

    /** The files, by name, as the Kotlin core writes them (sync payloads and a backup). */
    fun files(): Map<String, String> = mapOf(
        "cellar.json" to SyncJson.encodeCellar(cellar()).decodeToString(),
        "sync-state.json" to SyncJson.encode(SyncState.serializer(), state()).decodeToString(),
        "members.json" to SyncJson.encode(Membership.serializer(), members()).decodeToString(),
        "backup.json" to CellarJson.backup(cellar(), exportedAt),
    )

    /** Tests run in android/core. */
    val folder = File("../../fixtures/interop")
}

class InteropFixtureTest {
    private val write = System.getenv("ENCAVE_WRITE_FIXTURES") != null
    private fun swift(name: String) = File(InteropFixture.folder, "swift-$name").readText()
    private fun tree(text: String) = Json.parseToJsonElement(text)

    @Test
    fun readsWhatCaveKitWrites() {
        val cellar = SyncJson.decodeCellar(swift("cellar.json").toByteArray())
        val state = SyncJson.decode(SyncState.serializer(), swift("sync-state.json").toByteArray())
        val members = SyncJson.decode(Membership.serializer(), swift("members.json").toByteArray())
        assertEquals(InteropFixture.cellar(), cellar)
        assertEquals(InteropFixture.state(), state)
        assertEquals(InteropFixture.members(), members)
        val backup = CellarJson.backupFrom(swift("backup.json"))
        assertEquals(InteropFixture.exportedAt, backup.exportedAt)
    }

    @Test
    fun writesBackWhatCaveKitWrote() {
        // Same keys, values and number spellings ("12.3", "782000000.25"), in the same order.
        val rewrites = mapOf<String, (String) -> String>(
            "cellar.json" to { SyncJson.encodeCellar(SyncJson.decodeCellar(it.toByteArray())).decodeToString() },
            "sync-state.json" to { SyncJson.encode(SyncState.serializer(), SyncJson.decode(SyncState.serializer(), it.toByteArray())).decodeToString() },
            "members.json" to { SyncJson.encode(Membership.serializer(), SyncJson.decode(Membership.serializer(), it.toByteArray())).decodeToString() },
            "backup.json" to { CellarJson.backup(CellarJson.backupFrom(it).cellar, CellarJson.backupFrom(it).exportedAt) },
        )
        for ((name, rewrite) in rewrites) {
            val original = swift(name)
            assertEquals(tree(original), tree(rewrite(original)), name)
        }
    }

    @Test
    fun theKotlinCoreStillWritesTheSharedFiles() {
        for ((name, fresh) in InteropFixture.files()) {
            val file = File(InteropFixture.folder, "kotlin-$name")
            val same = file.exists() && file.readText() == fresh
            if (write && !same) file.writeText(fresh)
            assertTrue(same || write, "$name changed: run the tests with ENCAVE_WRITE_FIXTURES=1, then the Swift tests")
        }
    }
}
