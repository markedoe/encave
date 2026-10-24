package app.encave.core.sync

import app.encave.core.Attachment
import app.encave.core.AttachmentTarget
import app.encave.core.Bottle
import app.encave.core.Cellar
import app.encave.core.CellarError
import app.encave.core.CellarException
import app.encave.core.CellarJson
import app.encave.core.FileKind
import app.encave.core.FileRef
import app.encave.core.InstantSerializer
import app.encave.core.Wine
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Timeout

/** Mirrors CaveKit's AttachmentTests, with the same table of names. */
// Sockets and pipes: a hang fails the test instead of blocking CI.
@Timeout(120, unit = TimeUnit.SECONDS)
class AttachmentTest {
    private val date = InstantSerializer.fromReferenceSeconds(782_000_000.5)

    private fun doc(content: String, name: String = "Invoice.pdf") =
        Attachment(PhotoId.make(content.toByteArray()), name, "application/pdf", content.length.toLong(), date)

    private val names = listOf(
        "  Invoice.pdf  " to "Invoice.pdf",
        "a/b\\c.pdf" to "a-b-c.pdf",
        "line\nbreak\t.pdf" to "line break .pdf",
        " 　Name " to "Name",
        "" to "",
        "   " to "",
        ".." to "..",
        "\u007Fx\u0085" to "x",
        "é".repeat(250) to "é".repeat(200),
        "a".repeat(199) + " b" to "a".repeat(199),
        "é".repeat(150) to "é".repeat(100),
        "Facture 🍷.pdf" to "Facture 🍷.pdf",
    )

    @Test
    fun namesAreNormalizedTheSameWayEverywhere() {
        for ((input, expected) in names) {
            assertEquals(expected, Attachment.normalizedName(input), input)
            assertEquals(expected, Attachment.normalizedName(expected))
        }
        val cellar = Cellar()
        val wine = Wine(name = "A", attachments = listOf(doc("x", " a/b ")))
        cellar.addBottles(wine, 1)
        assertEquals("a-b", cellar.wines.getValue(wine.id).attachments!!.single().name)
    }

    @Test
    fun documentsAreKeptOnceAndNullWhenNone() {
        val wine = Wine(name = "A", attachments = listOf(doc("a"), doc("b"), doc("a", "Again.pdf"))).normalized()
        assertEquals(listOf("Invoice.pdf", "Invoice.pdf"), wine.attachments!!.map { it.name })
        assertNull(wine.copy(attachments = emptyList()).normalized().attachments)
        val bottle = Bottle(wineId = wine.id, attachments = listOf(doc("a"), doc("a"))).normalized()
        assertEquals(1, bottle.attachments!!.size)

        val cellar = CellarJson.decode(
            """{"wines":["${wine.id}",{"id":"${wine.id}","name":"A","grapes":[],"attachments":[
              {"id":"sha256-1","name":" One ","contentType":"application/pdf","byteCount":3,"addedAt":1},
              {"id":"sha256-1","name":"Two","contentType":"application/pdf","byteCount":3,"addedAt":2}]}],
              "bottles":["${bottle.id}",{"id":"${bottle.id}","wineID":"${wine.id}","addedAt":1,"attachments":[]}]}""",
        )
        assertEquals(listOf("One"), cellar.wines.getValue(wine.id).attachments!!.map { it.name })
        assertNull(cellar.bottles.getValue(bottle.id).attachments)
    }

    @Test
    fun noDocumentsLeavesTheFieldOut() {
        val cellar = Cellar().apply { addBottles(Wine(name = "A"), 1) }
        assertFalse(CellarJson.encode(cellar).contains("attachments"))
    }

    @Test
    fun everyFieldIsRequired() {
        val fields = mapOf("id" to "\"sha256-1\"", "name" to "\"A\"", "contentType" to "\"application/pdf\"", "byteCount" to "3", "addedAt" to "1")
        fun wineWith(attachment: String) = """{"wines":["${UUID(0, 1)}",{"id":"${UUID(0, 1)}","name":"A","grapes":[],"attachments":[$attachment]}]}"""
        fun json(map: Map<String, String>) = map.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
        assertEquals(3L, CellarJson.decode(wineWith(json(fields))).wines.values.single().attachments!!.single().byteCount)
        for (key in fields.keys) {
            assertThrows<Exception>(key) { CellarJson.decode(wineWith(json(fields - key))) }
        }
    }

    @Test
    fun theSizeLimitFitsTheRelayAndTheNetwork() {
        assertTrue(Attachment.MAX_BYTE_COUNT + 34 <= 8L * 1024 * 1024)
        assertTrue((Attachment.MAX_BYTE_COUNT + 2) / 3 * 4 + 1024 < SyncProtocol.MAX_FRAME_SIZE)
    }

    @Test
    fun cellarCommandsAttachRenameAndRemove() {
        val cellar = Cellar()
        val wine = Wine(name = "A")
        val bottle = cellar.addBottles(wine, 2)[0]
        val target = AttachmentTarget.OfWine(wine.id)
        cellar.attach(listOf(doc("a"), doc("b")), target)
        cellar.attach(listOf(doc("a", "Same.pdf"), doc("c")), target)
        assertEquals(listOf("a", "b", "c").map { PhotoId.make(it.toByteArray()) }, cellar.attachments(target).map { it.id })
        cellar.attach(listOf(doc("receipt")), AttachmentTarget.OfBottle(bottle.id))
        assertEquals(1, cellar.attachments(AttachmentTarget.OfBottle(bottle.id)).size)

        val b = PhotoId.make("b".toByteArray())
        cellar.renameAttachment(b, target, "  Fiche technique.pdf ")
        assertEquals("Fiche technique.pdf", cellar.attachments(target)[1].name)
        cellar.renameAttachment(b, target, "   ")
        assertEquals("Fiche technique.pdf", cellar.attachments(target)[1].name)

        val removed = cellar.removeAttachment(b, target)!!
        assertEquals(1, removed.second)
        assertEquals(2, cellar.attachments(target).size)
        cellar.attach(listOf(removed.first), target, removed.second)
        assertEquals(b, cellar.attachments(target)[1].id)

        val unknownBottle = assertThrows<CellarException> { cellar.attach(listOf(doc("x")), AttachmentTarget.OfBottle(UUID.randomUUID())) }
        assertEquals(CellarError.UnknownBottle, unknownBottle.error)
        val unknownWine = assertThrows<CellarException> { cellar.attach(listOf(doc("x")), AttachmentTarget.OfWine(UUID.randomUUID())) }
        assertEquals(CellarError.UnknownWine, unknownWine.error)
    }

    @Test
    fun theCellarRefersToPhotosAndDocuments() {
        val cellar = Cellar()
        val receipt = doc("receipt")
        val wine = Wine(name = "A", photoId = "label", extraPhotoIds = listOf(receipt.id), attachments = listOf(doc("sheet")))
        val bottle = cellar.addBottles(wine, 1)[0]
        cellar.attach(listOf(receipt), AttachmentTarget.OfBottle(bottle.id))
        assertEquals(setOf("label", receipt.id), cellar.photoIds)
        assertEquals(setOf(receipt.id, doc("sheet").id), cellar.attachmentIds)
        assertEquals(
            setOf(
                FileRef("label", FileKind.Photo), FileRef(receipt.id, FileKind.Photo),
                FileRef(receipt.id, FileKind.Document), FileRef(doc("sheet").id, FileKind.Document),
            ),
            cellar.fileRefs,
        )
    }

    @Test
    fun documentsTravelThroughStorageToTheirPlace() = runBlocking {
        val store = MemoryMailboxStore()
        val devices = household(2)
        grantAll(devices, store, null)
        val pdf = "%PDF-1.7 invoice".toByteArray()
        val receipt = "receipt jpeg".toByteArray()
        val sheet = Attachment(PhotoId.make(pdf), "Invoice.pdf", "application/pdf", pdf.size.toLong(), date)
        val both = PhotoId.make(receipt)
        devices[0].photos.save(sheet.id, FileKind.Document, pdf)
        devices[0].photos.save(both, FileKind.Photo, receipt)
        devices[0].photos.save(both, FileKind.Document, receipt)
        devices[0].edit { cellar ->
            val bottle = cellar.addBottles(Wine(name = "With papers", extraPhotoIds = listOf(both), attachments = listOf(sheet)), 1)[0]
            cellar.attach(
                listOf(Attachment(both, "Receipt.jpg", "image/jpeg", receipt.size.toLong(), date)),
                AttachmentTarget.OfBottle(bottle.id),
            )
        }
        devices[0].sync(store)
        devices[1].sync(store)
        assertArrayEquals(pdf, devices[1].photos.storedDocuments[sheet.id])
        assertNull(devices[1].photos.stored[sheet.id])
        assertArrayEquals(receipt, devices[1].photos.stored[both])
        assertArrayEquals(receipt, devices[1].photos.storedDocuments[both])
        // Uploaded once, though it is in two places.
        assertEquals(2, store.allFiles.values.flatMap { it.keys }.count { it.startsWith("photo/") })
    }

    @Test
    fun documentsTravelOnTheLocalNetworkToo() = runBlocking {
        val a = identity()
        val b = identity()
        var membership = Membership()
        var clock = 0L
        membership.adding(Member(a.id, "A", a.publicKey, "ios"), clock, a.id).let { (m, c) -> membership = m; clock = c }
        membership.adding(Member(b.id, "B", b.publicKey, "android"), clock, a.id).let { (m, _) -> membership = m }
        val pdf = "%PDF certificate".toByteArray()
        val id = PhotoId.make(pdf)
        val cellarA = Cellar()
        val bottle = cellarA.addBottles(Wine(name = "Certified"), 1)[0]
        cellarA.attach(listOf(Attachment(id, "Certificate.pdf", "application/pdf", pdf.size.toLong(), date)), AttachmentTarget.OfBottle(bottle.id))
        val filesA = PhotoBox(documents = mapOf(id to pdf))
        val filesB = PhotoBox()
        val snapshotA = SyncSnapshot(cellarA, SyncState.starting(cellarA, a.id), membership)
        val snapshotB = SyncSnapshot(Cellar(), SyncState(device = b.id), membership)
        val (endA, endB) = Pipe.pair()
        coroutineScope {
            val sideA = async { SyncExchange.run(SyncHandshake.initiate(endA, a, membership).first, true, snapshotA, filesA) }
            val sideB = async { SyncExchange.run(SyncHandshake.respond(endB, b, membership).first, false, snapshotB, filesB) }
            val resultA = sideA.await()
            val resultB = sideB.await()
            assertEquals(resultA.cellar, resultB.cellar)
            assertEquals("Certificate.pdf", resultB.cellar.attachments(AttachmentTarget.OfBottle(bottle.id)).single().name)
            assertArrayEquals(pdf, filesB.storedDocuments[id])
            assertTrue(filesB.stored.isEmpty())
        }
    }
}
