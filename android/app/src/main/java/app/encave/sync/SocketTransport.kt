package app.encave.sync

import app.encave.core.sync.FrameTransport
import app.encave.core.sync.SyncError
import app.encave.core.sync.SyncException
import app.encave.core.sync.SyncProtocol
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A TCP connection carrying frames (docs/SYNC.md §4): a 4-byte big-endian length,
 * then the bytes. Frames over 16 MiB are refused.
 */
class SocketTransport(private val socket: Socket, timeoutMs: Int = READ_TIMEOUT_MS) : FrameTransport {
    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output = DataOutputStream(socket.getOutputStream().buffered())
    private val sending = Mutex()

    init {
        // A session that stalls (the other device went to sleep) ends instead of hanging.
        socket.soTimeout = timeoutMs
        socket.tcpNoDelay = true
    }

    override suspend fun send(frame: ByteArray) {
        if (frame.size > SyncProtocol.MAX_FRAME_SIZE) throw SyncException(SyncError.FrameTooLarge)
        sending.withLock {
            withContext(Dispatchers.IO) {
                try {
                    output.writeInt(frame.size)
                    output.write(frame)
                    output.flush()
                } catch (_: IOException) {
                    throw SyncException(SyncError.Closed)
                }
            }
        }
    }

    override suspend fun receive(): ByteArray = withContext(Dispatchers.IO) {
        try {
            val length = input.readInt()
            if (length < 0 || length > SyncProtocol.MAX_FRAME_SIZE) throw SyncException(SyncError.FrameTooLarge)
            ByteArray(length).also { input.readFully(it) }
        } catch (_: IOException) {
            throw SyncException(SyncError.Closed)
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        const val READ_TIMEOUT_MS = 60_000

        /** Pairing waits for people to compare codes. */
        const val PAIRING_TIMEOUT_MS = 300_000
        private const val CONNECT_TIMEOUT_MS = 15_000

        /** Connects to another device; fails within 15 seconds. */
        suspend fun connect(address: InetSocketAddress, timeoutMs: Int = READ_TIMEOUT_MS): SocketTransport = withContext(Dispatchers.IO) {
            val socket = Socket()
            try {
                socket.connect(address, CONNECT_TIMEOUT_MS)
                SocketTransport(socket, timeoutMs)
            } catch (_: IOException) {
                runCatching { socket.close() }
                throw SyncException(SyncError.Closed)
            }
        }
    }
}
