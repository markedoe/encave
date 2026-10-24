package app.encave.core.search

import app.encave.core.Http
import java.io.IOException
import java.net.InetAddress
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
    suspend fun prices(result: WebSearchResult, sizeML: Int, wineName: String?): List<FoundPrice> = withContext(Dispatchers.IO) {
        val job = coroutineContext.job
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        val html = runCatching { read(result.url, deadline) }.getOrElse { error ->
            if (error is CancellationException) throw error
            null
        } ?: return@withContext emptyList()
        ensureActive()
        // Reading the page takes a moment more, never long (PagePrices reads in one pass).
        val stop = deadline + 2_000_000_000L
        PagePrices.prices(html, result, sizeML, wineName) { job.isCancelled || System.nanoTime() > stop }
    }

    private suspend fun read(url: String, deadline: Long): String? {
        var address = url
        repeat(PagePrices.MAX_REDIRECTS + 1) {
            if (!PagePrices.isAllowed(address)) return null
            val remaining = (deadline - System.nanoTime()) / 1_000_000
            if (remaining <= 0) return null
            val call = client.newBuilder()
                .callTimeout(remaining, TimeUnit.MILLISECONDS)
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> = vettedAddresses(hostname, deadline)
                })
                .build()
                .newCall(Request.Builder().url(address).header("Accept", "text/html,application/xhtml+xml").build())
            val outcome = coroutineScope {
                // Cancelling the search cancels the call, which stops a blocked connection or read.
                val watchdog = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
                try {
                    call.execute().use<Response, Outcome> { response ->
                        if (response.isRedirect) {
                            val next = response.header("Location")?.let { response.request.url.resolve(it) }?.toString()
                            return@use Redirect(next)
                        }
                        val body = response.body
                        val type = response.header("Content-Type")
                        if (!response.isSuccessful || !PagePrices.isHtml(type) || !PagePrices.acceptsLength(body.contentLength())) {
                            return@use Body(null)
                        }
                        val source = body.source()
                        val bytes = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        while (bytes.size() < PagePrices.MAX_BYTES) {
                            coroutineContext.ensureActive()
                            val read = source.read(buffer, 0, minOf(buffer.size, PagePrices.MAX_BYTES - bytes.size()))
                            if (read < 0) break
                            bytes.write(buffer, 0, read)
                        }
                        Body(PagePrices.text(bytes.toByteArray(), type))
                    }
                } catch (error: IOException) {
                    Body(null)
                } finally {
                    watchdog.cancel()
                }
            }
            when (outcome) {
                is Redirect -> address = outcome.next ?: return null
                is Body -> return outcome.html
            }
        }
        return null
    }

    /** Lets the connections go: call once the search is done or stopped. */
    fun close() {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private sealed interface Outcome
    private class Redirect(val next: String?) : Outcome
    private class Body(val html: String?) : Outcome
}
