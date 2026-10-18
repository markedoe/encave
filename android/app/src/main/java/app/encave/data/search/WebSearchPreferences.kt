package app.encave.data.search

import android.content.Context
import androidx.core.content.edit
import app.encave.core.search.WebImageDownload
import app.encave.core.search.WebImageResult
import app.encave.core.search.WebSearchError
import app.encave.core.search.WebSearchRequest
import app.encave.core.search.imageProvider
import app.encave.core.search.WebSearchResult
import app.encave.core.search.WebSearchService
import app.encave.data.ai.AiPreferences
import app.encave.data.ai.SecretStore
import java.io.IOException
import java.net.HttpURLConnection
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

    /** Downloads a picture from its host: https only, an image type, at most [maxBytes]. */
    suspend fun downloadImage(url: String, maxBytes: Int = WebImageDownload.MAX_BYTES): ByteArray = withContext(Dispatchers.IO) {
        if (!WebImageDownload.isAllowed(url)) throw WebSearchError.InvalidResponse
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = WebImageDownload.TIMEOUT_MS
            connection.readTimeout = WebImageDownload.TIMEOUT_MS
            connection.useCaches = false
            val status = connection.responseCode
            if (status !in 200..299 || !WebImageDownload.isAllowed(connection.url.toString()) ||
                !WebImageDownload.isImage(connection.contentType) ||
                !WebImageDownload.acceptsLength(connection.contentLengthLong) || connection.contentLengthLong > maxBytes
            ) {
                throw WebSearchError.InvalidResponse
            }
            connection.inputStream.use { input ->
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

    private fun send(request: WebSearchRequest): Pair<Int, String> {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.useCaches = false
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            request.body?.let { body ->
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            return status to stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        } catch (error: IOException) {
            throw WebSearchError.Server(0)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun search(query: String, settings: WebSearchSettings, apiKey: String?, count: Int = 10): List<WebSearchResult> =
        withContext(Dispatchers.IO) {
            val provider = settings.service.provider(apiKey, settings.serverUrl)
            val request = provider.request(query, count)
            val connection = URL(request.url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = request.method
                connection.connectTimeout = 20_000
                connection.readTimeout = 30_000
                connection.useCaches = false
                request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                request.body?.let { body ->
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                provider.results(text, status)
            } catch (error: IOException) {
                throw WebSearchError.Server(0)
            } finally {
                connection.disconnect()
            }
        }
}

