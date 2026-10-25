package app.encave.core.search

import app.encave.core.Http
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Opens result pages from this device for one price search and reads the price of a bottle
 * on them ([PagePrices]). One reader per search, closed after it: its connections and TLS
 * sessions are not reused by a later search.
 *
 * The site sees this device's address, the page asked for and the app's User-Agent, nothing
 * else: no cookies, no cache, no Referer, no credentials, no language. https on port 443 to a
 * public name only; the name is resolved here and the connection goes only to the addresses
 * checked ([PublicHost.isPublic]), so a name cannot lead to the local network. At most
 * [PagePrices.MAX_REDIRECTS] redirects, each checked the same way; [PagePrices.TIMEOUT_MS]
 * for the whole page, name lookups included; HTML only, at most [PagePrices.MAX_BYTES].
 * Pages are read without the device's proxy, so the name checked is the one connected to.
 * Cancelling the coroutine stops the connection. Neither the address nor the page is logged.
 */
class PageReader(
    private val timeoutMs: Long = PagePrices.TIMEOUT_MS.toLong(),
    private val lookUp: (String, Long) -> List<InetAddress> = PublicHost::lookUp,
) {
    /** TLS of its own: a later search does not resume this one's sessions. */
    private val trustManager: X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers.filterIsInstance<X509TrustManager>().first()
    private val tls: SSLContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }

    private val client = OkHttpClient.Builder()
        .sslSocketFactory(tls.socketFactory, trustManager)
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .retryOnConnectionFailure(false)
        // Straight to the site: a proxy set on the device would resolve the name itself, past the check.
        .proxy(Proxy.NO_PROXY)
        .connectionPool(ConnectionPool(PagePrices.PARALLEL_PAGES, 30, TimeUnit.SECONDS))
        .addInterceptor(Http.userAgentInterceptor)
        .build()

    /** The addresses of [host] if every one is public; otherwise the lookup fails. */
    internal fun vettedAddresses(host: String, deadline: Long): List<InetAddress> {
        val remaining = (deadline - System.nanoTime()) / 1_000_000
        val all = lookUp(host, remaining)
        if (all.isEmpty() || !all.all { PublicHost.isPublic(it.address) }) throw UnknownHostException("Not a public name")
        return all
    }

    /** The prices of one bottle on [result]'s page; empty when it cannot be read. */
    suspend fun prices(
        result: WebSearchResult,
        sizeML: Int,
        wineName: String?,
        vintage: Int? = null,
        producer: String? = null,
    ): List<FoundPrice> =
        withContext(Dispatchers.IO) {
            val job = coroutineContext.job
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            val html = runCatching { read(result.url, deadline) }.getOrElse { error ->
                if (error is CancellationException) throw error
                null
            } ?: return@withContext emptyList()
            ensureActive()
            // Reading the page takes a moment more, never long (PagePrices reads in one pass). A page
            // that could not be read, for whatever reason, only gives no price.
            val stop = deadline + 2_000_000_000L
            runCatching { PagePrices.prices(html, result, sizeML, wineName, vintage, producer) { job.isCancelled || System.nanoTime() > stop } }
                .getOrElse { error -> if (error is CancellationException) throw error else emptyList() }
        }

    /** For tests: replaces the network for one address (the time left in ms) with an answer. */
    internal var fetchForTests: (suspend (String, Long) -> Outcome)? = null

    /** The page's text after the redirects, each address checked; null when it cannot be read in time. */
    internal suspend fun read(url: String, deadline: Long): String? {
        var address = url
        repeat(PagePrices.MAX_REDIRECTS + 1) {
            if (!PagePrices.isAllowed(address)) return null
            val remaining = (deadline - System.nanoTime()) / 1_000_000
            if (remaining <= 0) return null
            when (val outcome = (fetchForTests ?: ::fetch)(address, remaining)) {
                is Redirect -> address = outcome.next ?: return null
                is Body -> return outcome.html
            }
        }
        return null
    }

    private suspend fun fetch(address: String, remaining: Long): Outcome {
        val deadline = System.nanoTime() + remaining * 1_000_000
        val call = client.newBuilder()
            .callTimeout(remaining, TimeUnit.MILLISECONDS)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = vettedAddresses(hostname, deadline)
            })
            .build()
            .newCall(Request.Builder().url(address).header("Accept", "text/html,application/xhtml+xml").build())
        return coroutineScope {
            val scope = coroutineContext
            // Cancelling the search cancels the call, which stops a blocked connection or read.
            val watchdog = launch {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                call.execute().use { response -> outcome(response) { scope.ensureActive() } }
            } catch (error: IOException) {
                Body(null)
            } finally {
                watchdog.cancel()
            }
        }
    }

    /**
     * What an answer gives: the next address of a redirect (resolved against the page's), or
     * the page's text when it is a success, HTML and not announced longer than
     * [PagePrices.MAX_BYTES] (read up to that many bytes); [checkActive] runs between reads.
     */
    internal fun outcome(response: Response, checkActive: () -> Unit = {}): Outcome {
        if (response.isRedirect) {
            val next = response.header("Location")?.let { response.request.url.resolve(it) }?.toString()
            return Redirect(next)
        }
        val body = response.body
        val type = response.header("Content-Type")
        if (!response.isSuccessful || !PagePrices.isHtml(type) || !PagePrices.acceptsLength(body.contentLength())) {
            return Body(null)
        }
        val source = body.source()
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (bytes.size() < PagePrices.MAX_BYTES) {
            checkActive()
            val read = source.read(buffer, 0, minOf(buffer.size, PagePrices.MAX_BYTES - bytes.size()))
            if (read < 0) break
            bytes.write(buffer, 0, read)
        }
        return Body(PagePrices.text(bytes.toByteArray(), type))
    }

    /** Lets the connections go: call once the search is done or stopped. */
    fun close() {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    internal sealed interface Outcome
    internal class Redirect(val next: String?) : Outcome
    internal class Body(val html: String?) : Outcome
}
