package app.encave.core.search

import app.encave.core.Wine
import app.encave.core.sync.LocalName
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/*
 * Optional web search to complete a wine's details and find its current price, the Kotlin
 * port of CaveKit's WebSearch.swift. Off by default. Only the search terms built from the
 * wine (producer, name, appellation, vintage, and the bottle size for a price) are sent,
 * to the provider the user sets up; never the cellar, notes, prices or places.
 * The app sends the request; this file builds it and reads the answer.
 */

/** One web page found. */
data class WebSearchResult(val title: String, val url: String, val snippet: String) {
    /** "example.com" for "https://www.example.com/page". */
    val domain: String get() = host(url).removePrefix("www.")

    companion object {
        /** Null for links that would not open a web page (no http or https address). */
        fun of(title: String?, url: String?, snippet: String?): WebSearchResult? {
            val address = url?.trim() ?: return null
            val uri = runCatching { URI(address) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            if (scheme != "https" && scheme != "http") return null
            val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
            val plainTitle = WebSearchText.plain(title.orEmpty())
            return WebSearchResult(plainTitle.ifEmpty { host }, address, WebSearchText.plain(snippet.orEmpty()))
        }

        internal fun host(url: String): String = runCatching { URI(url).host }.getOrNull().orEmpty()
    }
}

/** An HTTP request described without a client, so it can be tested anywhere. */
data class WebSearchRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: String? = null,
)

sealed class WebSearchError : Exception() {
    data object MissingKey : WebSearchError()
    /** The server address is not a full http:// or https:// address. */
    data object InvalidServerUrl : WebSearchError()
    /** The key was refused. */
    data object Unauthorized : WebSearchError()
    /** A SearXNG instance without the JSON format enabled answers 403. */
    data object JsonFormatDisabled : WebSearchError()
    data object RateLimited : WebSearchError()
    data class Server(val status: Int) : WebSearchError()
    data object InvalidResponse : WebSearchError()
}

/** A search service. More can be added by implementing these two steps. */
interface WebSearchProvider {
    fun request(query: String, count: Int): WebSearchRequest

    /** Reads the answer; throws [WebSearchError] for an error status. */
    fun results(body: String, status: Int): List<WebSearchResult>
}

/** The services offered in Settings. */
enum class WebSearchService(val raw: String, val brand: String?, val setupPage: String?) {
    Off("off", null, null),
    Brave("brave", "Brave Search", "https://api-dashboard.search.brave.com/app/keys"),
    Tavily("tavily", "Tavily", "https://app.tavily.com/home"),
    SearXNG("searxng", "SearXNG", "https://docs.searxng.org/admin/installation.html");

    val requiresKey: Boolean get() = this == Brave || this == Tavily

    /** SearXNG runs on the user's own server (a NAS, a computer) or an instance they trust. */
    val requiresServerUrl: Boolean get() = this == SearXNG

    fun provider(apiKey: String?, serverUrl: String): WebSearchProvider {
        val key = apiKey.orEmpty().trim()
        return when (this) {
            Off -> throw WebSearchError.InvalidServerUrl
            Brave -> if (key.isEmpty()) throw WebSearchError.MissingKey else BraveSearch(key)
            Tavily -> if (key.isEmpty()) throw WebSearchError.MissingKey else TavilySearch(key)
            SearXNG -> SearXNGSearch(serverUrl)
        }
    }

    companion object {
        fun fromRaw(raw: String?): WebSearchService = entries.firstOrNull { it.raw == raw } ?: Off
    }
}

object WebSearchQuery {
    /**
     * "Domaine X Chablis 1er Cru 2019 wine": producer, name, appellation and vintage, without
     * repeating a part already in another. Nothing else about the wine or the cellar.
     */
    fun terms(producer: String?, name: String, appellation: String?, vintage: Int?, wineWord: String = "wine"): String {
        fun clean(text: String?) = text.orEmpty().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        val cleanName = clean(name)
        val cleanProducer = clean(producer)
        val cleanAppellation = clean(appellation)
        val parts = mutableListOf<String>()
        // "Château Margaux" by "Château Margaux": once. A name within the producer's: the producer.
        val addsProducer = cleanProducer.isNotEmpty() && !contains(cleanName, cleanProducer)
        if (addsProducer) parts += cleanProducer
        if (cleanName.isNotEmpty() && !(addsProducer && contains(cleanProducer, cleanName))) parts += cleanName
        if (cleanAppellation.isNotEmpty() && !contains(parts.joinToString(" "), cleanAppellation)) parts += cleanAppellation
        if (vintage != null && vintage in 1000..9999) parts += vintage.toString()
        val word = clean(wineWord)
        if (word.isNotEmpty() && parts.isNotEmpty()) parts += word
        return parts.joinToString(" ")
    }

    fun terms(wine: Wine, wineWord: String = "wine"): String =
        terms(wine.producer, wine.name, wine.appellation, wine.vintage, wineWord)

    /** The wine's terms, the bottle size when it is not 75 cl, and "price" in the user's language. */
    fun priceTerms(
        producer: String?, name: String, appellation: String?, vintage: Int?,
        sizeML: Int = 750, priceWord: String = "price",
    ): String {
        val parts = mutableListOf(terms(producer, name, appellation, vintage, wineWord = ""))
        sizeWords(sizeML)?.let { parts += it }
        priceWord.trim().takeIf { it.isNotEmpty() }?.let { parts += it }
        return parts.filter { it.isNotEmpty() }.joinToString(" ")
    }

    fun priceTerms(wine: Wine, sizeML: Int = 750, priceWord: String = "price"): String =
        priceTerms(wine.producer, wine.name, wine.appellation, wine.vintage, sizeML, priceWord)

    /** Null for a 75 cl bottle. */
    internal fun sizeWords(sizeML: Int): String? = when (sizeML) {
        750 -> null
        375 -> "37.5 cl"
        620 -> "clavelin"
        1000 -> "1 l"
        1500 -> "magnum"
        3000 -> "double magnum"
        else -> when {
            sizeML > 3000 && sizeML % 500 == 0 -> if (sizeML % 1000 == 0) "${sizeML / 1000} l" else "${sizeML / 1000}.5 l"
            sizeML % 10 == 0 -> "${sizeML / 10} cl"
            else -> "$sizeML ml"
        }
    }

    /** Percent-encodes a query parameter value (spaces as %20, and "+", "&", "=" too). */
    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
        .replace("+", "%20").replace("*", "%2A").replace("%7E", "~")

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

    private fun contains(text: String, part: String) = fold(text).contains(fold(part))
}

internal val searchJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun checkStatus(status: Int) {
    when (status) {
        in 200..299 -> Unit
        401, 403 -> throw WebSearchError.Unauthorized
        429, 432, 433 -> throw WebSearchError.RateLimited
        else -> throw WebSearchError.Server(status)
    }
}

/**
 * Brave Search API: GET /res/v1/web/search, the key in X-Subscription-Token.
 * Answer: {"web": {"results": [{"title", "url", "description"}]}}.
 */
class BraveSearch(private val apiKey: String) : WebSearchProvider, WebImageSearchProvider {
    @Serializable private data class Item(val title: String? = null, val url: String? = null, val description: String? = null)
    @Serializable private data class Web(val results: List<Item>? = null)
    @Serializable private data class Answer(val web: Web? = null)

    override fun request(query: String, count: Int): WebSearchRequest = WebSearchRequest(
        url = "https://api.search.brave.com/res/v1/web/search?q=${WebSearchQuery.encode(query)}&count=${count.coerceIn(1, 20)}",
        method = "GET",
        headers = mapOf("Accept" to "application/json", "X-Subscription-Token" to apiKey),
    )

    override fun results(body: String, status: Int): List<WebSearchResult> {
        checkStatus(status)
        val answer = runCatching { searchJson.decodeFromString(Answer.serializer(), body) }
            .getOrElse { throw WebSearchError.InvalidResponse }
        return answer.web?.results.orEmpty().mapNotNull { WebSearchResult.of(it.title, it.url, it.description) }
    }

    /** Brave Image Search: GET /res/v1/images/search, the same key. */
    override fun imageRequest(query: String, count: Int): WebSearchRequest = WebSearchRequest(
        url = "https://api.search.brave.com/res/v1/images/search?q=${WebSearchQuery.encode(query)}&count=${count.coerceIn(1, 100)}&safesearch=strict",
        method = "GET",
        headers = mapOf("Accept" to "application/json", "X-Subscription-Token" to apiKey),
    )

    override fun imageResults(body: String, status: Int): List<WebImageResult> {
        checkStatus(status)
        return ImageAnswers.brave(body)
    }
}

/**
 * SearXNG: GET <server>/search?q=…&format=json. JSON must be enabled on the instance
 * (search: formats: [html, json] in settings.yml), or it answers 403.
 * Answer: {"results": [{"title", "url", "content"}]}.
 */
class SearXNGSearch(serverUrl: String) : WebSearchProvider, WebImageSearchProvider {
    @Serializable private data class Item(val title: String? = null, val url: String? = null, val content: String? = null)
    @Serializable private data class Answer(val results: List<Item>? = null)

    /** "http://nas.local:8080", without "/search" or a trailing slash. */
    val base: String = run {
        var base = serverUrl.trim()
        base.indexOfFirst { it == '?' || it == '#' }.takeIf { it >= 0 }?.let { base = base.substring(0, it) }
        base = base.trimEnd('/')
        if (base.lowercase(Locale.ROOT).endsWith("/search")) base = base.dropLast("/search".length)
        base = base.trimEnd('/')
        val url = base.toHttpUrlOrNull()
        // Plain HTTP only on the local network: elsewhere anyone on the way would read the search terms.
        // A name is checked when sending (LocalName.resolve).
        if (url == null || url.host.isEmpty() || (!url.isHttps && !LocalName.mayBeLocal(url.host))) throw WebSearchError.InvalidServerUrl
        base
    }

    override fun request(query: String, count: Int): WebSearchRequest = WebSearchRequest(
        url = "$base/search?q=${WebSearchQuery.encode(query)}&format=json",
        method = "GET",
        headers = mapOf("Accept" to "application/json"),
    )

    override fun results(body: String, status: Int): List<WebSearchResult> {
        if (status == 403) throw WebSearchError.JsonFormatDisabled
        checkStatus(status)
        val answer = runCatching { searchJson.decodeFromString(Answer.serializer(), body) }
            .getOrElse { throw WebSearchError.InvalidResponse }
        return answer.results.orEmpty().mapNotNull { WebSearchResult.of(it.title, it.url, it.content) }
    }

    override fun imageRequest(query: String, count: Int): WebSearchRequest = WebSearchRequest(
        url = "$base/search?q=${WebSearchQuery.encode(query)}&format=json&categories=images&safesearch=1",
        method = "GET",
        headers = mapOf("Accept" to "application/json"),
    )

    override fun imageResults(body: String, status: Int): List<WebImageResult> {
        if (status == 403) throw WebSearchError.JsonFormatDisabled
        checkStatus(status)
        return ImageAnswers.searx(body)
    }
}

/**
 * Tavily: POST /search with a JSON body, the key as a Bearer token.
 * Answer: {"results": [{"title", "url", "content"}]}.
 */
class TavilySearch(private val apiKey: String) : WebSearchProvider, WebImageSearchProvider {
    @Serializable private data class Item(val title: String? = null, val url: String? = null, val content: String? = null)
    @Serializable private data class Answer(val results: List<Item>? = null)
    @Serializable private data class Body(val query: String, val max_results: Int, val search_depth: String = "basic")
    @Serializable private data class ImageBody(
        val query: String,
        val max_results: Int,
        val include_images: Boolean = true,
        val include_image_descriptions: Boolean = true,
    )

    override fun request(query: String, count: Int): WebSearchRequest = WebSearchRequest(
        url = "https://api.tavily.com/search",
        method = "POST",
        headers = mapOf("Content-Type" to "application/json", "Authorization" to "Bearer $apiKey"),
        body = Json { encodeDefaults = true }.encodeToString(Body.serializer(), Body(query, count.coerceIn(1, 20))),
    )

    override fun results(body: String, status: Int): List<WebSearchResult> {
        checkStatus(status)
        val answer = runCatching { searchJson.decodeFromString(Answer.serializer(), body) }
            .getOrElse { throw WebSearchError.InvalidResponse }
        return answer.results.orEmpty().mapNotNull { WebSearchResult.of(it.title, it.url, it.content) }
    }

    override fun imageRequest(query: String, count: Int): WebSearchRequest = WebSearchRequest(
        url = "https://api.tavily.com/search",
        method = "POST",
        headers = mapOf("Content-Type" to "application/json", "Authorization" to "Bearer $apiKey"),
        body = Json { encodeDefaults = true }.encodeToString(ImageBody.serializer(), ImageBody(query, count.coerceIn(1, 20))),
    )

    override fun imageResults(body: String, status: Int): List<WebImageResult> {
        checkStatus(status)
        return ImageAnswers.tavily(body)
    }
}

internal object WebSearchText {
    /** Snippets carry HTML ("<strong>Chablis</strong> &amp; …"): plain text, on one line. */
    fun plain(html: String): String {
        val text = StringBuilder()
        var inTag = false
        for (character in html) {
            when {
                character == '<' -> inTag = true
                character == '>' && inTag -> inTag = false
                !inTag -> text.append(character)
            }
        }
        return decodeEntities(text.toString()).split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "rsquo" to "’", "lsquo" to "‘", "ldquo" to "“", "rdquo" to "”",
        "euro" to "€", "pound" to "£", "laquo" to "«", "raquo" to "»", "middot" to "·", "thinsp" to " ",
    )

    fun decodeEntities(text: String): String {
        if (!text.contains('&')) return text
        val result = StringBuilder()
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character == '&') {
                val end = text.indexOf(';', index).takeIf { it in (index + 1)..(index + 11) }
                if (end != null) {
                    val entity = text.substring(index + 1, end)
                    val value = named[entity.lowercase(Locale.ROOT)] ?: numeric(entity)
                    if (value != null) {
                        result.append(value)
                        index = end + 1
                        continue
                    }
                }
            }
            result.append(character)
            index += 1
        }
        return result.toString()
    }

    private fun numeric(entity: String): String? {
        if (!entity.startsWith("#")) return null
        val digits = entity.drop(1)
        val code = if (digits.startsWith("x") || digits.startsWith("X")) digits.drop(1).toIntOrNull(16) else digits.toIntOrNull()
        return code?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) }
    }
}

/**
 * Which redirects a request to a search service (or an AI provider) may follow. The search
 * terms (or the key and the photo) go only where the user pointed: the same host, over HTTPS,
 * or over plain HTTP only from an HTTP server on the local network to the same host (a name is
 * resolved again for each hop, [LocalName]). Another host, or HTTPS to HTTP, is refused.
 */
object SameHostRedirect {
    /** At most this many redirects for one request. */
    const val MAX = 5

    fun allows(from: String, to: String): Boolean {
        val origin = from.toHttpUrlOrNull() ?: return false
        val target = to.toHttpUrlOrNull() ?: return false
        if (origin.host.isEmpty() || origin.host != target.host) return false
        // Over http, a local address, or a name that LocalName checks again before the next hop.
        return target.isHttps || (!origin.isHttps && LocalName.mayBeLocal(target.host))
    }
}
