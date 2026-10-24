package app.encave.core.sync

import java.net.InetAddress
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Why plain HTTP to a server name was refused. */
class LocalNetworkException(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        /** The name points to an address outside the local network (or to some outside it): use https, or a local address. */
        NotLocal,

        /** The name could not be found on this network (away from home, a typo). */
        NotFound,
    }
}

/**
 * Plain HTTP to a server on the local network reached by a name of the home network
 * ("http://nas.lan:8080", a name the router or a local DNS gives), not only by the names
 * and addresses [LocalAddress] recognizes without asking. The same rules as CaveKit's `LocalName`.
 *
 * The name is resolved before each request (the answer is kept a minute): it is accepted only
 * if every address it has is local, and the request then goes to that address, not to the
 * name, so a later DNS answer cannot send it elsewhere (DNS rebinding). HTTPS is left as it
 * is: by name, with the certificate checked.
 *
 * A server that tells sites apart by their name (virtual hosts) may answer differently by
 * address; SearXNG, Ollama and WebDAV on a NAS answer the same.
 */
object LocalName {
    /** The addresses of a name, as numeric text (IPv4 dotted, or IPv6). Blocking. */
    fun interface Resolver {
        fun addresses(host: String): List<String>
    }

    /** How long resolving a name may take. */
    const val TIMEOUT_MS = 5_000L

    /** How long an answer is kept. */
    const val CACHE_MS = 60_000L

    /**
     * True when plain HTTP to [host] may be accepted: a local name or address, or another
     * name, which [resolve] checks before each request. False for an address outside the
     * local network.
     */
    fun mayBeLocal(host: String): Boolean = LocalAddress.isLocal(host) || isName(host)

    /**
     * The URL to send [url] to: unchanged for HTTPS and for a host [LocalAddress] already knows
     * as local; for plain HTTP to another name, the same URL with the name replaced by its
     * local address. Throws [LocalNetworkException] if the name is not found or is not only
     * on the local network. Blocking: call it off the main thread.
     */
    fun resolve(url: String, resolver: Resolver? = null): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        val host = parsed.host.lowercase()
        if (parsed.scheme != "http" || host.isEmpty() || LocalAddress.isLocal(host)) return url
        if (!isName(host)) throw LocalNetworkException(LocalNetworkException.Reason.NotLocal)
        val addresses = if (resolver != null) {
            resolver.addresses(host)
        } else {
            cache[host]?.takeIf { it.second > System.nanoTime() }?.first
                ?: system(host).also { cache[host] = it to System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CACHE_MS) }
        }
        val address = choose(addresses)
        return runCatching { parsed.newBuilder().host(address).build().toString() }
            .getOrElse { throw LocalNetworkException(LocalNetworkException.Reason.NotFound) }
    }

    /** The same for a [URL]. */
    fun resolve(url: URL, resolver: Resolver? = null): URL {
        val text = url.toString()
        val resolved = resolve(text, resolver)
        return if (resolved == text) url else URL(resolved)
    }

    /** The address to use, if all of them are local: IPv4 first, then IPv6 (link-local last). */
    internal fun choose(addresses: List<String>): String {
        if (addresses.isEmpty()) throw LocalNetworkException(LocalNetworkException.Reason.NotFound)
        // A link-local IPv6 address may carry its interface ("fe80::1%wlan0"): checked without it.
        val bare = { address: String -> address.substringBefore('%') }
        if (!addresses.all { LocalAddress.isLocal(bare(it)) && !isName(bare(it)) }) {
            throw LocalNetworkException(LocalNetworkException.Reason.NotLocal)
        }
        val rank = { address: String -> if (!address.contains(':')) 0 else if (address.contains('%')) 2 else 1 }
        return addresses.minBy(rank)
    }

    /** A name rather than an address: not digits and dots only (IPv4, however written) and without ":" (IPv6). */
    internal fun isName(host: String): Boolean {
        val name = host.lowercase().removePrefix("[").removeSuffix("]")
        if (name.isEmpty() || name.contains(':') || name.contains('%')) return false
        return !name.all { it == '.' || it in '0'..'9' }
    }

    private val cache = ConcurrentHashMap<String, Pair<List<String>, Long>>()

    private val lookups = Executors.newCachedThreadPool { task -> Thread(task, "LocalName").apply { isDaemon = true } }

    /** The system resolver, at most [TIMEOUT_MS] (the lookup itself cannot be stopped, its answer is then ignored). */
    private fun system(host: String): List<String> {
        val lookup = lookups.submit(Callable { InetAddress.getAllByName(host).mapNotNull { it.hostAddress }.distinct() })
        return try {
            lookup.get(TIMEOUT_MS, TimeUnit.MILLISECONDS).ifEmpty { throw LocalNetworkException(LocalNetworkException.Reason.NotFound) }
        } catch (error: ExecutionException) {
            // UnknownHostException, or no network permission.
            throw LocalNetworkException(LocalNetworkException.Reason.NotFound)
        } catch (error: TimeoutException) {
            lookup.cancel(true)
            throw LocalNetworkException(LocalNetworkException.Reason.NotFound)
        }
    }
}
