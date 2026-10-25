package app.encave.data.search

import android.content.Context
import androidx.core.content.edit
import app.encave.core.CappedRead
import app.encave.core.Http
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
import kotlinx.coroutines.Dispatchers
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
            // Each address, redirects included, must lead to a public host, its name resolved and checked.
            if (!WebImageDownload.resolvesPublicly(address)) throw WebSearchError.InvalidResponse
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
                return status to stream?.use { CappedRead.bytes(it, connection.contentLengthLong, CappedRead.JSON_LIMIT).toString(Charsets.UTF_8) }.orEmpty()
            } catch (error: IOException) {
                throw WebSearchError.Server(0)
            } finally {
                connection.disconnect()
            }
        }
        throw WebSearchError.Server(0)
    }

    private val REDIRECTS = setOf(301, 302, 303, 307, 308)

    suspend fun search(query: String, settings: WebSearchSettings, apiKey: String?, count: Int = 10): List<WebSearchResult> =
        withContext(Dispatchers.IO) {
            val provider = settings.service.provider(apiKey, settings.serverUrl)
            val (status, text) = send(provider.request(query, count))
            provider.results(text, status)
        }
}
