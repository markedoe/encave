package app.encave.core.sync

import app.encave.core.Cellar
import app.encave.core.CellarJson
import app.encave.core.CellarSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JSON of the sync protocol (docs/SYNC.md §4 and §7): keys sorted, binary values in
 * standard Base64, UUIDs in uppercase (either case is read), dictionaries keyed by
 * UUID as arrays alternating key and value, and dates as numbers of seconds since
 * 2001-01-01, as CaveKit's `JSONEncoder.sync` writes them. Readers ignore key order
 * and unknown keys.
 */
object SyncJson {
    /** Strict on enum values, like Swift's decoder: an unknown bottle status fails the read instead of becoming another one. */
    val json = kotlinx.serialization.json.Json(CellarJson.referenceJson) { coerceInputValues = false }

    fun <T> encode(serializer: KSerializer<T>, value: T): ByteArray =
        write(json.encodeToJsonElement(serializer, value)).toByteArray(Charsets.UTF_8)

    fun <T> decode(serializer: KSerializer<T>, bytes: ByteArray): T = json.decodeFromString(serializer, bytes.toString(Charsets.UTF_8))

    fun encodeCellar(cellar: Cellar): ByteArray = encode(CellarSerializer, cellar)
    fun decodeCellar(bytes: ByteArray): Cellar = decode(CellarSerializer, bytes)

    /** Compact JSON with sorted keys. */
    fun write(element: JsonElement): String = StringBuilder().also { write(element, it, swiftEscapes = false) }.toString()

    internal fun write(element: JsonElement, out: StringBuilder, swiftEscapes: Boolean) {
        when (element) {
            is JsonObject -> {
                out.append('{')
                element.keys.sorted().forEachIndexed { index, key ->
                    if (index > 0) out.append(',')
                    string(key, out, swiftEscapes)
                    out.append(':')
                    write(element.getValue(key), out, swiftEscapes)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                element.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    write(item, out, swiftEscapes)
                }
                out.append(']')
            }
            JsonNull -> out.append("null")
            is JsonPrimitive -> if (element.isString) string(element.content, out, swiftEscapes) else out.append(element.content)
        }
    }

    private fun string(text: String, out: StringBuilder, swiftEscapes: Boolean) {
        out.append('"')
        for (char in text) {
            when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                // Foundation's JSONEncoder escapes slashes unless told not to.
                '/' -> if (swiftEscapes) out.append("\\/") else out.append('/')
                else -> if (char < ' ') out.append("\\u%04x".format(char.code)) else out.append(char)
            }
        }
        out.append('"')
    }
}
