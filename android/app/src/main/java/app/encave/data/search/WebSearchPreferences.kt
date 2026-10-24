package app.encave.data.search

import android.content.Context
import androidx.core.content.edit
import app.encave.core.Http
import app.encave.core.search.FoundPrice
import app.encave.core.search.PagePrices
import app.encave.core.search.WebImageDownload
import app.encave.core.search.WebImageResult
import app.encave.core.search.SameHostRedirect
import app.encave.core.search.WebSearchError
import app.encave.core.search.WebSearchRequest
import app.encave.core.search.imageProvider
import app.encave.core.search.WebSearchResult
import app.encave.core.search.WebSearchService
import app.encave.core.sync.LocalName
import app.encave.data.ai.AiPreferences
import app.encave.data.ai.SecretStore
import java.io.IOException
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Optional web search with a service the user chooses. Off by default. */
data class WebSearchSettings(
    val service: WebSearchService = WebSearchService.Off,
    /** A SearXNG server's address, such as http://nas.local:8080. */
    val serverUrl: String = "",
) {
    val isEnabled: Boolean get() = service != WebSearchService.Off
}

/**
 * Web search settings in preferences: the service, and a server address saved per service.
 * Keys are in [SecretStore] (Android Keystore), one per service.
 */
class WebSearchPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("websearch", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)
    private val state = MutableStateFlow(read())

    val settings: StateFlow<WebSearchSettings> = state.asStateFlow()

    private fun read(): WebSearchSettings {
        val service = WebSearchService.fromRaw(prefs.getString("service", "off"))
        return WebSearchSettings(service, prefs.getString("serverURL.${service.raw}", "").orEmpty())
    }

    fun select(service: WebSearchService) {
        prefs.edit { putString("service", service.raw) }
        state.value = WebSearchSettings(service, prefs.getString("serverURL.${service.raw}", "").orEmpty())
    }

    fun setServerUrl(url: String) {
        val service = state.value.service
        prefs.edit { putString("serverURL.${service.raw}", url) }
        state.value = state.value.copy(serverUrl = url)
    }

    fun apiKey(service: WebSearchService): String? = secrets.get("webSearch.apiKey.${service.raw}")?.let { AiPreferences.cleanKey(it) }

    fun setApiKey(service: WebSearchService, key: String?) =
        secrets.set("webSearch.apiKey.${service.raw}", key?.let { AiPreferences.cleanKey(it) })

    /** Saves the key in the background, in the order keys are typed. */
    fun saveApiKey(service: WebSearchService, key: String?) = SecretStore.writes.execute { runCatching { setApiKey(service, key) } }
}

/** Sends the query, and nothing else, to the service set up in Settings. */
object WebSearchClient {
    /** Pictures for the query, from the image search of the same service. */
    suspend fun searchImages(query: String, settings: WebSearchSettings, apiKey: String?, count: Int = 30): List<WebImageResult> =
        withContext(Dispatchers.IO) {
            val provider = settings.service.imageProvider(apiKey, settings.serverUrl)
            val (status, text) = send(provider.imageRequest(query, count))
            provider.imageResults(text, status)
        }

    /**
     * Downloads a picture from its host: https only, never to this device or the local network
     * (each redirect is checked too), an image type, at most [maxBytes].
     */
    suspend fun downloadImage(url: String, maxBytes: Int = WebImageDownload.MAX_BYTES): ByteArray = withContext(Dispatchers.IO) {
        var address = url
        repeat(WebImageDownload.MAX_REDIRECTS + 1) {
            if (!WebImageDownload.isAllowed(address)) throw WebSearchError.InvalidResponse
            val connection = Http.open(URL(address))
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = WebImageDownload.TIMEOUT_MS
                connection.readTimeout = WebImageDownload.TIMEOUT_MS
                connection.useCaches = false
                val status = connection.responseCode
                if (status in REDIRECTS) {
                    address = connection.getHeaderField("Location")?.let { runCatching { URL(URL(address), it).toString() }.getOrNull() }
                        ?: throw WebSearchError.InvalidResponse
                    return@repeat
                }
                if (status !in 200..299 || !WebImageDownload.isImage(connection.contentType) ||
                    !WebImageDownload.acceptsLength(connection.contentLengthLong) || connection.contentLengthLong > maxBytes
                ) {
                    throw WebSearchError.InvalidResponse
                }
                return@withContext connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        if (output.size() > maxBytes) throw WebSearchError.InvalidResponse
                    }
                    output.toByteArray()
                }
            } catch (error: IOException) {
                throw WebSearchError.Server(0)
            } finally {
                connection.disconnect()
            }
        }
        throw WebSearchError.InvalidResponse
    }

    /**
     * Sends [request]: its status and text. Redirects are followed only on the same host, over
     * HTTPS (or HTTP on the local network, see [SameHostRedirect]); any other is answered with
     * its own status, so the search terms never go elsewhere or in clear text.
     */
    private fun send(request: WebSearchRequest): Pair<Int, String> {
        var url = URL(request.url)
        var method = request.method
        var body = request.body
        repeat(SameHostRedirect.MAX + 1) {
            // A SearXNG server named on the home network gets the request at its local address.
            val connection = Http.open(LocalName.resolve(url))
            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = method
                connection.connectTimeout = 20_000
                connection.readTimeout = 30_000
                connection.useCaches = false
                request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                body?.let { text ->
                    connection.doOutput = true
                    connection.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                if (status in REDIRECTS) {
                    val next = connection.getHeaderField("Location")?.let { runCatching { URL(url, it) }.getOrNull() }
                    if (next == null || !SameHostRedirect.allows(request.url, next.toString())) return status to ""
                    // As browsers do: 303, and 301 or 302 after a POST, continue with a GET.
                    if (status == 303 || (status != 307 && status != 308 && method == "POST")) {
                        method = "GET"
                        body = null
                    }
                    url = next
                    return@repeat
                }
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                return status to stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            } catch (error: IOException) {
                throw WebSearchError.Server(0)
            } finally {
                connection.disconnect()
            }
        }
        throw WebSearchError.Server(0)
    }

    private val REDIRECTS = setOf(301, 302, 303, 307, 308)

    /**
     * Opens a result's page from this device and reads the price of a bottle on it
     * ([PagePrices]). The site sees this device's address and the page asked for, nothing
     * else: no cookies (the app sets no cookie handler), no cache, no Referer, no credentials,
     * the app's own User-Agent. https only, never a local or private address (each redirect is
     * checked too, at most [PagePrices.MAX_REDIRECTS]), HTML only, at most [PagePrices.MAX_BYTES],
     * about [PagePrices.TIMEOUT_MS] in all. Empty when the page cannot be read; neither the
     * address nor the page is logged.
     */
    suspend fun pagePrices(result: WebSearchResult, sizeML: Int, wineName: String?): List<FoundPrice> = withContext(Dispatchers.IO) {
        val deadline = System.nanoTime() + PagePrices.TIMEOUT_MS * 1_000_000L
        var address = result.url
        repeat(PagePrices.MAX_REDIRECTS + 1) {
            if (!PagePrices.resolvesPublicly(address)) return@withContext emptyList()
            val connection = runCatching { Http.open(URL(address)) }.getOrNull() ?: return@withContext emptyList()
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = PagePrices.TIMEOUT_MS
                connection.readTimeout = PagePrices.TIMEOUT_MS
                connection.useCaches = false
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
                val status = connection.responseCode
                if (status in REDIRECTS) {
                    address = connection.getHeaderField("Location")?.let { runCatching { URL(URL(address), it).toString() }.getOrNull() }
                        ?: return@withContext emptyList()
                    return@repeat
                }
                val type = connection.contentType
                if (status !in 200..299 || !PagePrices.isHtml(type)) return@withContext emptyList()
                val bytes = connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (output.size() < PagePrices.MAX_BYTES && System.nanoTime() < deadline) {
                        ensureActive()
                        val read = input.read(buffer, 0, minOf(buffer.size, PagePrices.MAX_BYTES - output.size()))
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
                return@withContext PagePrices.prices(PagePrices.text(bytes, type), result, sizeML, wineName)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // A page that cannot be read is left out, silently.
                return@withContext emptyList()
            } finally {
                connection.disconnect()
            }
        }
        emptyList()
    }

    suspend fun search(query: String, settings: WebSearchSettings, apiKey: String?, count: Int = 10): List<WebSearchResult> =
        withContext(Dispatchers.IO) {
            val provider = settings.service.provider(apiKey, settings.serverUrl)
            val (status, text) = send(provider.request(query, count))
            provider.results(text, status)
        }
}
