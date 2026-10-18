package app.encave.core.sync

import app.encave.core.Bottle
import app.encave.core.Cellar
import app.encave.core.CellarError
import app.encave.core.CellarException
import app.encave.core.CellarJson
import app.encave.core.Discovery
import app.encave.core.ImportError
import app.encave.core.ImportException
import app.encave.core.PurchaseInfo
import app.encave.core.Rack
import app.encave.core.Wine
import app.encave.core.parseUuid
import java.util.Base64
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Inboxes that refuse every new file, as if each name were taken. */
private class FullInboxStore : MailboxStore {
    private val base = MemoryMailboxStore()
    override fun mailbox(id: String, token: String): Mailbox = base.mailbox(id, token)
    override fun inbox(id: String, token: String): Mailbox = object : Mailbox {
        override suspend fun list(prefix: String): List<String> = emptyList()
        override suspend fun get(path: String): ByteArray? = null
        override suspend fun create(path: String, data: ByteArray): Boolean = false
        override suspend fun delete(path: String) = Unit
    }
}

/** Counts the photos read in full. */
private class CountingPhotos(initial: Map<String, ByteArray>) : PhotoAccess {
    private val photos = initial.toMutableMap()
    var reads = 0
    override fun data(id: String): ByteArray? = photos[id].also { reads++ }
    override fun save(id: String, data: ByteArray) {
        photos[id] = data
    }
    override fun exists(id: String): Boolean = id in photos
}

/** Mirrors CaveKit's ProtocolStrictnessTests. */
class ProtocolStrictnessTests {
    @Test
    fun grantNamesHaveARandomPartAndParse() {
        val sender = identity()
        val recipient = identity()
        val key = CellarKey.random(UUID.randomUUID(), 3)
        val grant = KeyGrant(key, sender.id, null)
        val first = grant.sealed(sender, recipient.publicKey).first
        val second = grant.sealed(sender, recipient.publicKey).first
        assertNotEquals(first, second)
        val name = GrantPath.parse(first)
        assertNotNull(name)
        assertEquals(Triple(key.cellarId, 3L, sender.id), Triple(name!!.cellarId, name.epoch, name.sender))
        assertTrue(RelayPath.isValid(first))
        val cellar = key.cellarId.toString()
        val from = sender.id.toString()
        assertNull(GrantPath.parse("grant/$cellar-00000003-$from"))
        assertNull(GrantPath.parse("grant/$cellar-0000003-$from-0a1b2c3d"))
        assertNull(GrantPath.parse("grant/$cellar-00000003-$from-0A1B2C3D"))
        assertNull(GrantPath.parse("grant/${cellar.uppercase()}-00000003-$from-0a1b2c3d"))
        assertEquals(3L, GrantPath.parse("grant/$cellar-00000003-$from-0a1b2c3d")?.epoch)
    }

    @Test
    fun aSquatterCannotBlockAGrant() = runBlocking {
        val store = MemoryMailboxStore()
        val devices = household(2)
        val key = devices[0].key!!
        val inbox = store.inbox(devices[1].identity.inboxId, "")
        // Someone who knows the inbox, the cellar and the sender fills the old grant name with junk.
        inbox.create("grant/${key.cellarId}-00000001-${devices[0].identity.id}", ByteArray(100) { 7 })
        grantAll(devices, store, null)
        val received = RemoteSync.receiveGrant(store, devices[1].identity, devices[1].snapshot.membership, null)
        assertEquals(key, received?.cellarKey)
        assertEquals(1, inbox.list("grant/").size)
    }

    @Test
    fun aGrantThatCannotBeCreatedIsAnError() = runBlocking {
        val devices = household(2)
        val error = assertThrows<RemoteSyncException> {
            runBlocking { RemoteSync.grant(devices[0].key!!, devices[0].snapshot.membership.active, FullInboxStore(), devices[0].identity, null) }
        }
        assertEquals(RemoteSyncError.Unavailable("grant not created"), error.error)
    }

    @Test
    fun grantsOfUnknownDevicesAreKeptOnlyAFew() = runBlocking {
        val store = MemoryMailboxStore()
        val recipient = household(2)[1]
        val inbox = store.inbox(recipient.identity.inboxId, "")
        repeat(RemoteSync.MAX_WAITING_GRANTS + 5) {
            inbox.create(GrantPath(recipient.snapshot.membership.cellarId, 2, UUID.randomUUID()).string, ByteArray(200) { 1 })
        }
        RemoteSync.receiveGrant(store, recipient.identity, recipient.snapshot.membership, null)
        assertEquals(RemoteSync.MAX_WAITING_GRANTS, inbox.list("grant/").size)
    }

    @Test
    fun aGrantOverFourKilobytesIsDeleted() = runBlocking {
        val store = MemoryMailboxStore()
        val devices = household(2)
        val inbox = store.inbox(devices[1].identity.inboxId, "")
        inbox.create(GrantPath(devices[1].snapshot.membership.cellarId, 1, devices[0].identity.id).string, ByteArray(KeyGrant.MAX_SIZE + 1))
        RemoteSync.receiveGrant(store, devices[1].identity, devices[1].snapshot.membership, null)
        assertTrue(inbox.list("grant/").isEmpty())
    }

    @Test
    fun clocksOverTwoToThe52AreRefused() {
        SyncState.check(SyncState(clock = 1L shl 52))
        assertTrue(runCatching { SyncState.check(SyncState(clock = (1L shl 52) + 1)) }.exceptionOrNull() is SyncException)
    }

    @Test
    fun aSnapshotMustHaveTheClockOfItsName() = runBlocking {
        val store = MemoryMailboxStore()
        val devices = household(2)
        grantAll(devices, store, null)
        devices.forEach { it.sync(store) }
        val key = devices[0].key!!
        val forged = devices[1].snapshot.copy(state = devices[1].snapshot.state.copy(clock = 5))
        val path = SnapshotPath(devices[1].identity.id, 900).string
        store.mailbox(key.mailboxId, key.token).create(path, Envelope.seal(RemoteSync.encodeSnapshot(forged), Envelope.Kind.Snapshot, path, key.dataKey))
        val result = RemoteSync.run(store, devices[0].identity, key, devices[0].snapshot, devices[0].progress, devices[0].photos)
        assertEquals(listOf(devices[1].identity.id), result.unreadable)
    }

    @Test
    fun aSnapshotIsNeverWrittenTwiceWithOneClock() = runBlocking {
        val store = MemoryMailboxStore()
        val devices = household(2)
        val key = devices[0].key!!
        val first = RemoteSync.run(store, devices[0].identity, key, devices[0].snapshot, RemoteProgress(), PhotoAccess.NONE)
        // The progress of that round was lost (the app stopped before saving it).
        val second = RemoteSync.run(store, devices[0].identity, key, devices[0].snapshot, RemoteProgress(), PhotoAccess.NONE)
        assertEquals(first.clock + 1, second.clock)
        val mine = store.mailbox(key.mailboxId, key.token).list("snap/${devices[0].identity.id}/").mapNotNull { SnapshotPath.parse(it) }
        assertEquals(mine.size, mine.map { it.clock }.toSet().size)
    }

    @Test
    fun aKeyGrantedWithAnotherStorageEndsTheRound() = runBlocking {
        val store = MemoryMailboxStore()
        val devices = household(2)
        val old = devices[0].key!!
        val new = CellarKey.random(old.cellarId, 2)
        RemoteSync.grant(new, devices[0].snapshot.membership.active, store, devices[0].identity, "https://new.example/")
        val result = RemoteSync.run(store, devices[1].identity, old, devices[1].snapshot, RemoteProgress(), PhotoAccess.NONE, "https://old.example/")
        assertEquals(new, result.newKey)
        assertEquals("https://new.example/", result.storage)
        assertNull(result.progress.uploadedClock)
        assertEquals(2L, result.progress.epoch)
        assertNull(store.allFiles[new.mailboxId], "nothing written under the new key on the old storage")
        val same = RemoteSync.run(store, devices[1].identity, old, devices[1].snapshot, RemoteProgress(), PhotoAccess.NONE, "https://new.example/")
        assertEquals(new, same.newKey)
        assertNotNull(same.progress.uploadedClock)
    }

    @Test
    fun aMultistatusInUtf16IsRefused() {
        val xml = """<?xml version="1.0" encoding="UTF-16"?><!DOCTYPE d [<!ENTITY x "y">]>""" +
            """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/a/</d:href></d:response></d:multistatus>"""
        assertNull(WebDavMultistatus.parse(xml.toByteArray(Charsets.UTF_16)))
        assertNotNull(WebDavMultistatus.parse(xml.replace(Regex("<!DOCTYPE[^]]*]>"), "").replace("UTF-16", "UTF-8").toByteArray()))
    }

    @Test
    fun aCellarKeyIsThirtyTwoBytes() {
        val cellar = UUID.randomUUID().toString().uppercase()
        val short = """{"cellarID":"$cellar","epoch":1,"key":"${Base64.getEncoder().encodeToString(ByteArray(16))}"}"""
        assertTrue(runCatching { SyncJson.decode(CellarKey.serializer(), short.toByteArray()) }.isFailure)
        val right = """{"cellarID":"$cellar","epoch":1,"key":"${Base64.getEncoder().encodeToString(ByteArray(32))}"}"""
        assertEquals(32, SyncJson.decode(CellarKey.serializer(), right.toByteArray()).key.size)
    }

    @Test
    fun codesAndNamesUseAsciiDigitsInEveryLanguage() {
        val saved = Locale.getDefault()
        try {
            // Persian and Arabic format numbers with their own digits by default.
            for (tag in listOf("fa", "ar-EG")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                val ascii = Regex("^[ -~]+$")
                assertTrue(ascii.matches(KeySchedule.confirmationCode(ByteArray(32) { 3 })), tag)
                assertTrue(PairingCode.random().isValid, tag)
                assertTrue(ascii.matches(GrantPath(UUID.randomUUID(), 2, UUID.randomUUID()).string), tag)
                assertTrue(RelayPath.isValid(GrantPath(UUID.randomUUID(), 2, UUID.randomUUID()).string), tag)
                assertTrue(RelayPath.isValid(SnapshotPath(UUID.randomUUID(), 42).string), tag)
                assertNotNull(SnapshotPath.parse(SnapshotPath(UUID.randomUUID(), 42).string), tag)
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun aCancelledRoundIsNotASkippedSnapshot() {
        val devices = household(2)
        val store = object : MailboxStore {
            val base = MemoryMailboxStore()
            override fun inbox(id: String, token: String) = base.inbox(id, token)
            override fun mailbox(id: String, token: String) = object : Mailbox {
                override suspend fun list(prefix: String) = listOf(SnapshotPath(devices[1].identity.id, 7).string)
                override suspend fun get(path: String): ByteArray = throw CancellationException("stopped")
                override suspend fun create(path: String, data: ByteArray) = true
                override suspend fun delete(path: String) = Unit
            }
        }
        assertThrows<CancellationException> {
            runBlocking { RemoteSync.run(store, devices[0].identity, devices[0].key!!, devices[0].snapshot, RemoteProgress(), devices[0].photos) }
        }
    }

    @Test
    fun roundsCheckWhetherPhotosExistWithoutReadingThem() = runBlocking {
        val store = MemoryMailboxStore()
        val devices = household(2)
        grantAll(devices, store, null)
        val label = "label".toByteArray()
        val id = PhotoId.make(label)
        val photos = CountingPhotos(mapOf(id to label))
        devices[0].edit { it.addBottles(Wine(name = "Pictured", photoId = id), 1) }
        val key = devices[0].key!!
        val first = RemoteSync.run(store, devices[0].identity, key, devices[0].snapshot, RemoteProgress(), photos)
        assertEquals(1, photos.reads, "read once, to upload it")
        RemoteSync.run(store, devices[0].identity, key, devices[0].snapshot, first.progress, photos)
        assertEquals(1, photos.reads, "a round with nothing new reads no photo")
    }

    @Test
    fun uuidsAndBase64AreReadStrictly() {
        assertNull(parseUuid("1-2-3-4-5"))
        assertNull(parseUuid("0000000000000000000000000000000000001"))
        assertNotNull(parseUuid("A1B2C3D4-0000-4000-8000-000000000001"))
        val wine = """{"id":"1-2-3-4-5","name":"W"}"""
        assertTrue(runCatching { CellarJson.json.decodeFromString(Wine.serializer(), wine) }.isFailure)
        for (bad in listOf("AAAA\nAAAA", "AAA", "AA=A", "AA AA")) {
            val message = """{"type":"x","key":"$bad"}"""
            assertTrue(runCatching { WireMessage.decode(message.toByteArray()) }.isFailure, bad)
        }
        assertEquals(3, WireMessage.decode("""{"type":"x","key":"AAAA"}""".toByteArray()).key?.size)
    }

    @Test
    fun recordsAreReadStrictlyLikeSwift() {
        val wine = UUID.randomUUID().toString().uppercase()
        val id = UUID.randomUUID().toString().uppercase()
        // Missing dates are not "now", missing IDs not random.
        assertTrue(runCatching { SyncJson.json.decodeFromString(Bottle.serializer(), """{"id":"$id","wineID":"$wine"}""") }.isFailure)
        assertTrue(runCatching { SyncJson.json.decodeFromString(Bottle.serializer(), """{"wineID":"$wine","addedAt":1}""") }.isFailure)
        assertTrue(runCatching { SyncJson.json.decodeFromString(Discovery.serializer(), """{"id":"$id","wineID":"$wine"}""") }.isFailure)
        assertTrue(runCatching { SyncJson.json.decodeFromString(Stamp.serializer(), """{"counter":1,"device":"$id"}""") }.isFailure)
        assertTrue(runCatching { SyncJson.json.decodeFromString(SyncState.serializer(), """{"version":1,"clock":0,"stamps":{}}""") }.isFailure)
        val bottle = SyncJson.json.decodeFromString(Bottle.serializer(), """{"id":"$id","wineID":"$wine","addedAt":1}""")
        assertEquals(Bottle.Status.InCellar, bottle.status)
        // A date as a string in the device file, or a number in a backup, is refused, as in Swift.
        assertTrue(runCatching { SyncJson.json.decodeFromString(Bottle.serializer(), """{"id":"$id","wineID":"$wine","addedAt":"2026-01-01T00:00:00Z"}""") }.isFailure)
        // Backups too: an unknown color fails instead of becoming no color.
        val backup = CellarJson.backup(Cellar(wines = listOf(Wine(name = "W", color = app.encave.core.WineColor.Red))))
        CellarJson.backupFrom(backup)
        val error = assertThrows<ImportException> { CellarJson.backupFrom(backup.replace("\"red\"", "\"blue\"")) }
        assertEquals(ImportError.NotAnEncaveBackup, error.error)
        // Dictionaries keyed by UUID are arrays of an even length, as Swift writes them.
        assertTrue(runCatching { CellarJson.decode("""{"wines":["$wine"]}""") }.isFailure)
        assertTrue(runCatching { CellarJson.decode("""{"wines":{"$wine":{"id":"$wine","name":"W"}}}""") }.isFailure)
    }

    @Test
    fun aBottleOnOrderHasNoPlace() {
        val cellar = Cellar(racks = listOf(Rack(name = "R", rows = 2, columns = 2)))
        val bottle = cellar.addBottles(Wine(name = "Coming"), 1, purchase = PurchaseInfo(isOrdered = true)).single()
        val error = assertThrows<CellarException> { cellar.move(bottle.id, app.encave.core.Slot(cellar.racks[0].id, 0, 0)) }
        assertEquals(CellarError.NotReceived, error.error)
    }
}
