package app.encave.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import app.encave.core.sync.SyncProtocol
import java.io.Closeable
import java.net.InetSocketAddress

/**
 * Finds other Encave devices on the local network with DNS-SD (Bonjour), through
 * Android's Network Service Discovery: service type `_encave._tcp`, TXT records as
 * in docs/SYNC.md §4. Only random device IDs, cellar tags and pairing rendezvous
 * values are advertised, never names.
 */
class LocalNetwork(context: Context) {
    companion object {
        /** Android 17 asks the user before an app talks to the local network. */
        const val PERMISSION_SDK = 37

        /** Whether this app may use the local network (always before Android 17). */
        fun isAllowed(context: Context): Boolean =
            Build.VERSION.SDK_INT < PERMISSION_SDK ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
    }

    private val nsd: NsdManager? = context.getSystemService(NsdManager::class.java)

    /** A service another device advertises, resolved to an address. */
    data class Found(val name: String, val attributes: Map<String, String>, val address: InetSocketAddress)

    /** Advertises [name] on [port] with TXT [attributes] until closed. Null (and [onFailure]) if NSD is not available. */
    fun advertise(name: String, port: Int, attributes: Map<String, String>, onFailure: () -> Unit = {}): Closeable? {
        val manager = nsd ?: run {
            onFailure()
            return null
        }
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SyncProtocol.SERVICE_TYPE
            this.port = port
            attributes.forEach { (key, value) -> setAttribute(key, value) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) = onFailure()
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        return runCatching {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            Closeable { runCatching { manager.unregisterService(listener) } }
        }.getOrElse {
            onFailure()
            null
        }
    }

    /**
     * Looks for Encave services until closed. [onFound] gets each one once resolved;
     * [onLost] gets the name of one that went away. [onFailure] is told when looking cannot
     * start (local network access refused, or no network service), [onStarted] when it does.
     */
    fun browse(
        onFound: (Found) -> Unit,
        onLost: (String) -> Unit,
        onFailure: () -> Unit = {},
        onStarted: () -> Unit = {},
    ): Closeable? {
        val manager = nsd ?: run {
            onFailure()
            return null
        }
        val resolver = Resolver(manager, onFound)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = onStarted()
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = onFailure()
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) = resolver.enqueue(info)
            override fun onServiceLost(info: NsdServiceInfo) = onLost(info.serviceName)
        }
        return runCatching {
            manager.discoverServices(SyncProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            Closeable {
                resolver.stop()
                runCatching { manager.stopServiceDiscovery(listener) }
            }
        }.getOrElse {
            onFailure()
            null
        }
    }

    /** Resolves one service at a time: older Android versions refuse concurrent resolutions. */
    private class Resolver(private val manager: NsdManager, private val onFound: (Found) -> Unit) {
        private val queue = ArrayDeque<NsdServiceInfo>()
        private var busy = false
        private var stopped = false

        fun enqueue(info: NsdServiceInfo) = synchronized(this) {
            if (stopped || queue.any { it.serviceName == info.serviceName }) return
            queue.addLast(info)
            next()
        }

        fun stop() = synchronized(this) {
            stopped = true
            queue.clear()
        }

        private fun next() {
            if (busy || stopped) return
            val info = queue.removeFirstOrNull() ?: return
            busy = true
            @Suppress("DEPRECATION")
            manager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = done()

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.hostAddresses.firstOrNull()
                    if (host != null && info.port > 0) {
                        val attributes = info.attributes.mapValues { (_, value) -> value?.toString(Charsets.UTF_8).orEmpty() }
                        runCatching { onFound(Found(info.serviceName, attributes, InetSocketAddress(host, info.port))) }
                    }
                    done()
                }
            })
        }

        private fun done() = synchronized(this) {
            busy = false
            next()
        }
    }
}
