package app.encave.core.sync

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Why plain HTTP to a server name was refused. */
class LocalNetworkException(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        /** The name has no address on the local network: use https, or the server's local address. */
        NotLocal,

        /** The name could not be found on this network (away from home, a typo). */
        NotFound,
    }
}

/**
 * Plain HTTP to a server on the local network reached by a name of the home network
 * ("http://nas.lan:8080", "http://nas.local", a name the router or a local DNS gives).
 * The same rules as CaveKit's `LocalName`.
 *
 * The name is resolved before each request (the answer is kept a minute): it is accepted only
 * if it has a local address, and the request then goes only to such an address, so neither a
 * public address of the same name nor a later DNS answer can receive it (DNS rebinding).
 * Only addresses and "localhost" are used as they are. HTTPS is left as it is: by name, with
 * the certificate checked.
 *
 * OkHttp (WebDAV) keeps the name in the URL, so the server sees it (virtual hosts, Nextcloud's
 * trusted_domains), and connects through [systemDns], which gives only the checked local addresses.
 * HttpURLConnection callers (relay, AI, web search) get the URL with the address instead.
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

    /** True when [url] is plain HTTP to a name that [resolve] looks up (not an address, not localhost). */
    fun needsLookup(url: HttpUrl): Boolean = url.scheme == "http" && isName(url.host) && !url.host.equals("localhost", ignoreCase = true)

    /**
     * The URL to send [url] to: unchanged for HTTPS, a local address and "localhost"; for
     * plain HTTP to a name, the same URL with the name replaced by its best local address.
     * Throws [LocalNetworkException] if the name is not found or has no local address.
     * Blocking: call it off the main thread.
     */
    fun resolve(url: String, resolver: Resolver? = null): String {
        val parsed = url.toHttpUrlOrNull()
            // Plain HTTP that OkHttp cannot read ("http://nas..lan/") is refused, never sent as it is.
            ?: if (url.trim().startsWith("http:", ignoreCase = true)) throw LocalNetworkException(LocalNetworkException.Reason.NotLocal) else return url
        val host = parsed.host.lowercase()
        if (parsed.scheme != "http" || host.isEmpty()) return url
        if (!isName(host)) {
            if (LocalAddress.isLocal(host)) return url
            throw LocalNetworkException(LocalNetworkException.Reason.NotLocal)
        }
        if (host == "localhost") return url
        // "nas..lan" or ".lan" is no name of the network.
        if (host.startsWith(".") || host.contains("..")) throw LocalNetworkException(LocalNetworkException.Reason.NotLocal)
        val address = choose(addresses(host, resolver))
        return runCatching { parsed.newBuilder().host(address).build().toString() }
            .getOrElse { throw LocalNetworkException(LocalNetworkException.Reason.NotFound) }
    }

    /** The same for a [URL]. */
    fun resolve(url: URL, resolver: Resolver? = null): URL {
        val text = url.toString()
        val resolved = resolve(text, resolver)
        return if (resolved == text) url else URL(resolved)
    }

    /**
     * For OkHttp, on requests that [needsLookup]: the local addresses of a name, best first;
     * [UnknownHostException] if it has none. Use it only for plain HTTP to such names.
     */
    fun dns(resolver: Resolver? = null): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = try {
            localAddresses(addresses(hostname.lowercase(), resolver)).map { InetAddress.getByName(it) }
        } catch (error: LocalNetworkException) {
            throw UnknownHostException("$hostname: ${error.reason}")
        }
    }

    /** [dns] with the system resolver: one instance, so that OkHttp reuses its connections. */
    val systemDns: Dns = dns(null)

    /**
     * The best local address: private or loopback IPv4 first, then unique local or loopback
     * IPv6, then link-local IPv4, then link-local IPv6 without an interface. Addresses outside
     * the local network (a public IPv6 next to a local IPv4, CGNAT) are left out.
     * NotLocal if none is local, NotFound if there is none, or only link-local ones with an
     * interface ("fe80::1%wlan0", which a URL cannot hold).
     */
    internal fun choose(addresses: List<String>): String = localAddresses(addresses).first()

    /** Every usable local address, best first, as [choose] ranks them. */
    private fun localAddresses(addresses: List<String>): List<String> {
        val ranked = addresses.mapNotNull { address -> rank(address)?.takeIf { it >= 0 }?.let { address to it } }
        if (ranked.isNotEmpty()) return ranked.sortedBy { it.second }.map { it.first }
        val scoped = addresses.any { rank(it) == SCOPED }
        throw LocalNetworkException(if (addresses.isEmpty() || scoped) LocalNetworkException.Reason.NotFound else LocalNetworkException.Reason.NotLocal)
    }

    private const val SCOPED = -1

    /**
     * How an address may be used, by its bytes (IPv6 however written, "0:0:0:0:0:0:0:1" as
     * Inet6Address gives it): a rank (lower is better), [SCOPED], or null outside the local network.
     */
    internal fun rank(address: String): Int? {
        val bare = address.substringBefore('%')
        val scoped = address.contains('%')
        if (!bare.contains(':')) {
            if (scoped || isName(bare) || !LocalAddress.isLocal(bare)) return null
            return if (bare.startsWith("169.254.")) 2 else 0
        }
        if (bare.isEmpty() || !bare.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }) return null
        // A literal with ":" is parsed, never looked up; an IPv4-mapped one is left out, as in Swift.
        val bytes = (runCatching { InetAddress.getByName(bare) }.getOrNull() as? Inet6Address)?.address ?: return null
        val linkLocal = bytes[0] == 0xFE.toByte() && (bytes[1].toInt() and 0xC0) == 0x80
        if (scoped) return if (linkLocal) SCOPED else null
        val loopback = (0 until 15).all { bytes[it] == 0.toByte() } && bytes[15] == 1.toByte()
        if ((bytes[0].toInt() and 0xFE) == 0xFC || loopback) return 1
        return if (linkLocal) 3 else null
    }

    private fun addresses(host: String, resolver: Resolver?): List<String> =
        if (resolver != null) {
            resolver.addresses(host)
        } else {
            cache[host]?.takeIf { it.second > System.nanoTime() }?.first
                ?: system(host).also { cache[host] = it to System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CACHE_MS) }
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
