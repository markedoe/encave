package app.encave.sync

import app.encave.core.Cellar
import app.encave.core.Wine
import app.encave.core.sync.DeviceIdentity
import app.encave.core.sync.Member
import app.encave.core.sync.Membership
import app.encave.core.sync.PhotoAccess
import app.encave.core.sync.SyncExchange
import app.encave.core.sync.SyncHandshake
import app.encave.core.sync.SyncSnapshot
import app.encave.core.sync.SyncState
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Two devices sync over a real TCP connection on this machine, with length-prefixed frames. */
class SocketTransportTest {
    @Test
    fun twoDevicesSyncOverTcp() = runBlocking {
        val (a, _) = DeviceIdentity.generate()
        val (b, _) = DeviceIdentity.generate()
        val (one, clock) = Membership().adding(Member(a.id, "A", a.publicKey, "android"), 0, a.id)
        val (membership, _) = one.adding(Member(b.id, "B", b.publicKey, "ios"), clock, a.id)
        val cellarA = Cellar().apply { addBottles(Wine(name = "From A"), 2) }
        val cellarB = Cellar().apply { addBottles(Wine(name = "From B"), 1) }

        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val responder = async(Dispatchers.IO) {
                val transport = SocketTransport(server.accept())
                val (channel, _) = SyncHandshake.respond(transport, b, membership)
                SyncExchange.run(channel, false, SyncSnapshot(cellarB, SyncState.starting(cellarB, b.id), membership), PhotoAccess.NONE)
                    .also { transport.close() }
            }
            val transport = SocketTransport.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort))
            val (channel, peer) = SyncHandshake.initiate(transport, a, membership)
            val resultA = SyncExchange.run(channel, true, SyncSnapshot(cellarA, SyncState.starting(cellarA, a.id), membership), PhotoAccess.NONE)
            transport.close()
            val resultB = responder.await()
            assertEquals(b.id, peer.id)
            assertEquals(resultA.cellar, resultB.cellar)
            assertEquals(3, resultA.cellar.bottles.size)
        }
    }

    @Test
    fun framesKeepTheirBoundaries() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val received = async(Dispatchers.IO) {
                val transport = SocketTransport(server.accept())
                transport.allowLargeFrames()
                List(3) { transport.receive() }.also { transport.close() }
            }
            val transport = SocketTransport.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort))
            val frames = listOf(ByteArray(0), "hello".toByteArray(), ByteArray(70_000) { it.toByte() })
            withContext(Dispatchers.IO) { frames.forEach { transport.send(it) } }
            val got = received.await()
            transport.close()
            frames.zip(got).forEach { (sent, read) -> assertArrayEquals(sent, read) }
        }
    }

    @Test
    fun aLargeFrameBeforeAuthenticationIsRefused() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val received = async(Dispatchers.IO) {
                val transport = SocketTransport(server.accept())
                runCatching { transport.receive() }.also { transport.close() }
            }
            val transport = SocketTransport.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort))
            withContext(Dispatchers.IO) { runCatching { transport.send(ByteArray(SocketTransport.UNAUTHENTICATED_LIMIT + 1)) } }
            val result = received.await()
            transport.close()
            assertEquals(app.encave.core.sync.SyncError.FrameTooLarge, (result.exceptionOrNull() as? app.encave.core.sync.SyncException)?.error)
        }
    }
}
