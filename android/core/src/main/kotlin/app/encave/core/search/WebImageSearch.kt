package app.encave.core.search

import app.encave.core.Wine
import app.encave.core.sync.LocalAddress
import java.net.URI
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/*
 * Pictures of a bottle from the internet, the Kotlin port of CaveKit's WebImageSearch.swift.
 * Only the search terms go to the search service; the thumbnails and the picture the user
 * chooses are then downloaded from the sites that host them. The picture becomes the wine's
 * cover (coverPhotoId); the label photo is never replaced.
 */

/** A picture found: a thumbnail for the grid, the full picture, and the page it is on. */
data class WebImageResult(val title: String, val thumbnailUrl: String, val imageUrl: String, val pageUrl: String?) {
    val id: String get() = imageUrl

    /** The site of the page, or of the picture. */
    val domain: String get() = WebSearchResult.host(pageUrl ?: imageUrl).removePrefix("www.")

    companion object {
        /** Null without an https picture. A missing or plain-http thumbnail falls back to the picture. */
        fun of(title: String?, thumbnail: String?, image: String?, page: String?): WebImageResult? {
            val imageUrl = WebImageDownload.httpsUrl(image) ?: return null
            return WebImageResult(
                WebSearchText.plain(title.orEmpty()), WebImageDownload.httpsUrl(thumbnail) ?: imageUrl, imageUrl,
                page?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    }
}

/** A service that can also search pictures. */
interface WebImageSearchProvider {
    fun imageRequest(query: String, count: Int): WebSearchRequest
    fun imageResults(body: String, status: Int): List<WebImageResult>
}

fun WebSearchService.imageProvider(apiKey: String?, serverUrl: String): WebImageSearchProvider =
    provider(apiKey, serverUrl) as? WebImageSearchProvider ?: throw WebSearchError.InvalidResponse

/** "Domaine X Chablis 1er Cru 2019 bottle": the wine's terms and "bottle" in the user's language. */
fun WebSearchQuery.pictureTerms(wine: Wine, bottleWord: String = "bottle"): String = terms(wine, bottleWord)

internal object ImageAnswers {
    @Serializable data class Source(val src: String? = null, val url: String? = null)
    @Serializable data class BraveItem(val title: String? = null, val url: String? = null, val thumbnail: Source? = null, val properties: Source? = null)
    @Serializable data class Brave(val results: List<BraveItem>? = null)

    @Suppress("PropertyName")
    @Serializable data class SearxItem(val title: String? = null, val url: String? = null, val img_src: String? = null, val thumbnail_src: String? = null)
    @Serializable data class Searx(val results: List<SearxItem>? = null)

    fun brave(body: String): List<WebImageResult> {
        val answer = runCatching { searchJson.decodeFromString(Brave.serializer(), body) }.getOrElse { throw WebSearchError.InvalidResponse }
        return WebImageDownload.unique(answer.results.orEmpty().mapNotNull { WebImageResult.of(it.title, it.thumbnail?.src, it.properties?.url, it.url) })
    }

    fun searx(body: String): List<WebImageResult> {
        val answer = runCatching { searchJson.decodeFromString(Searx.serializer(), body) }.getOrElse { throw WebSearchError.InvalidResponse }
        return WebImageDownload.unique(answer.results.orEmpty().mapNotNull { WebImageResult.of(it.title, it.thumbnail_src, it.img_src, it.url) })
    }

    /** {"images": ["https://…"]} or [{"url", "description"}]. */
    fun tavily(body: String): List<WebImageResult> {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrElse { throw WebSearchError.InvalidResponse }
        val images = root["images"] as? JsonArray ?: return emptyList()
        return WebImageDownload.unique(
            images.mapNotNull { element ->
                when (element) {
                    is JsonPrimitive -> WebImageResult.of(null, null, element.contentOrNull, null)
                    is JsonObject -> WebImageResult.of(
                        (element["description"] as? JsonPrimitive)?.contentOrNull, null,
                        (element["url"] as? JsonPrimitive)?.contentOrNull, null,
                    )
                    else -> null
                }
            },
        )
    }
}

/** Checks for downloading a chosen picture: https from a public host only, an image type, at most 8 MB. */
object WebImageDownload {
    const val MAX_BYTES = 8 * 1024 * 1024
    const val TIMEOUT_MS = 20_000
    private val types = setOf(
        "image/jpeg", "image/jpg", "image/pjpeg", "image/png", "image/webp", "image/heic", "image/heif", "image/gif", "image/avif",
    )

    internal fun httpsUrl(text: String?): String? {
        val address = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val uri = runCatching { URI(address) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) != "https" || uri.host.isNullOrEmpty()) return null
        return address
    }

    /** Redirects followed for one picture, each checked with [isAllowed]. */
    const val MAX_REDIRECTS = 5

    /**
     * https, and a public host: search results are not trusted, so a picture never comes from
     * this device or the local network (a router, a NAS), by name or by address.
     */
    fun isAllowed(url: String): Boolean {
        val address = httpsUrl(url) ?: return false
        val host = runCatching { URI(address).host }.getOrNull() ?: return false
        return !isPrivateHost(host)
    }

    /** localhost, *.local, private, loopback and link-local addresses, and IP addresses written in other forms. */
    internal fun isPrivateHost(host: String): Boolean {
        val name = host.lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]").trimEnd('.')
        if (name.isEmpty() || LocalAddress.isLocal(name) || name.endsWith(".localhost")) return true
        if (name.contains(':')) {
            // IPv6: the unspecified address, and IPv4 addresses mapped into it.
            if (name == "::" || name.startsWith("::ffff:") || name.startsWith("0:0:0:0:0:ffff:")) return true
            return false
        }
        // A name whose last label is a number is an IP address ("2130706433", "0x7f.1", "127.1"):
        // only the usual four decimal numbers, of a public address, are accepted.
        if (name.substringAfterLast('.').let { it.isNotEmpty() && (it.all(Char::isDigit) || it.startsWith("0x")) }) {
            val octets = name.split('.').map { part -> part.takeIf { it.length in 1..3 && it.all(Char::isDigit) && (it == "0" || it[0] != '0') }?.toInt() }
            if (octets.size != 4 || octets.any { it == null || it > 255 }) return true
            val (a, b) = octets[0]!! to octets[1]!!
            // This network, shared address space (carrier-grade NAT), multicast and reserved.
            return a == 0 || (a == 100 && b in 64..127) || a >= 224
        }
        return false
    }

    /** "image/jpeg; charset=…" is an image; text, HTML, SVG and unknown types are not. */
    fun isImage(contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return false
        return type in types
    }

    /** False when the server announces more than the cap (-1 or null: unknown, checked while reading). */
    fun acceptsLength(length: Long?): Boolean = length == null || length < 0 || length <= MAX_BYTES

    internal fun unique(results: List<WebImageResult>): List<WebImageResult> = results.distinctBy { it.id }
}
