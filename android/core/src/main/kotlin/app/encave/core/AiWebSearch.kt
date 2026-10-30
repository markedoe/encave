package app.encave.core

import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A page the AI cited when it completed a wine from the web: its address and title. Written
 * in "webSources" on the wine (docs/SYNC.md §1), as CaveKit's `WineWebSource`.
 */
@KeepGeneratedSerializer
@Serializable(with = WineWebSourceSerializer::class)
data class WineWebSource(
    val url: String,
    val title: String? = null,
    /** Keys written by a newer version, kept and written back unchanged (docs/SYNC.md §1). */
    @Transient val unknownKeys: JsonObject = NoUnknownKeys,
) {
    /** Only http and https addresses are kept and opened. */
    val isWebAddress: Boolean
        get() = runCatching { java.net.URI(url).scheme?.lowercase() }.getOrNull().let { it == "https" || it == "http" }
}

/** Keeps the keys of a newer version (see [KeepingUnknownKeys]); an empty title is left out, as in CaveKit. */
object WineWebSourceSerializer : KeepingUnknownKeys<WineWebSource>(WineWebSource.generatedSerializer()) {
    override fun unknownKeys(value: WineWebSource) = value.unknownKeys
    override fun withUnknownKeys(value: WineWebSource, keys: JsonObject) = value.copy(unknownKeys = keys)
    override fun deserialize(decoder: Decoder): WineWebSource = super.deserialize(decoder).let { it.copy(title = it.title?.ifEmpty { null }) }
}

/** Reading the pages an AI cited, and asking for JSON when it searches. As CaveKit's `AIWebSearch`. */
object AiWebSearch {
    /** How a provider's API searches the web itself. */
    enum class Kind { AnthropicTool, OpenRouterPlugin, ResponsesTool }

    /** The prompt for a model that searches: the JSON object is asked in words. */
    fun prompt(prompt: String, schema: JsonObject): String =
        "$prompt\nAnswer with only a JSON object, without Markdown, following this JSON schema: $schema"

    private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement?.objects(): List<JsonObject> = (this as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    /** Each address once, http or https only, at most [limit]. */
    fun unique(sources: List<WineWebSource>, limit: Int = 8): List<WineWebSource> =
        sources.filter { it.isWebAddress }.distinctBy { it.url }.take(limit)

    private fun source(url: String?, title: String?) = url?.let { WineWebSource(it, title?.ifEmpty { null }) }

    /** Claude: the citations of its text, else the results of its searches. */
    fun claude(blocks: JsonArray): List<WineWebSource> {
        val all = blocks.objects()
        val cited = all.flatMap { it["citations"].objects() }.mapNotNull { source(it["url"].text(), it["title"].text()) }
        if (cited.isNotEmpty()) return unique(cited)
        return unique(
            all.filter { it["type"].text() == "web_search_tool_result" }.flatMap { it["content"].objects() }
                .mapNotNull { source(it["url"].text(), it["title"].text()) },
        )
    }

    /** Chat Completions (OpenRouter): the message's url_citation annotations. */
    fun chatCompletions(message: JsonObject?): List<WineWebSource> = unique(
        message?.get("annotations").objects().mapNotNull { annotation ->
            val citation = annotation["url_citation"] as? JsonObject ?: return@mapNotNull null
            source(citation["url"].text(), citation["title"].text())
        },
    )

    /** Responses (OpenAI, xAI): the answer's text and its url_citation annotations. */
    fun responses(json: JsonObject): Pair<String?, List<WineWebSource>> {
        val parts = json["output"].objects().filter { it["type"].text() == "message" }.flatMap { it["content"].objects() }
            .filter { it["type"].text() == "output_text" }
        val text = parts.mapNotNull { it["text"].text() }.lastOrNull { it.contains('{') }
        val sources = parts.flatMap { it["annotations"].objects() }.filter { it["type"].text() == "url_citation" }
            .mapNotNull { source(it["url"].text(), it["title"].text()) }
        return text to unique(sources)
    }
}
