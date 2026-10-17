package app.encave.core

import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/*
 * JSON encoding compatible with the Swift `Codable` output of CaveKit:
 * - UUIDs as uppercase strings;
 * - dates as ISO-8601 without fractional seconds (`.iso8601` in Swift rejects them);
 *   numbers are read as seconds since 2001-01-01 (Swift `.deferredToDate`);
 * - `Decimal` prices as bare JSON numbers;
 * - `[UUID: Wine]` dictionaries as flat arrays alternating key and value.
 */

/** UUID as an uppercase string, as Foundation writes it. */
object UuidSerializer : KSerializer<UUID> {
    override val descriptor = PrimitiveSerialDescriptor("app.encave.UUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString().uppercase(Locale.ROOT))
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}

/** ISO-8601 instant, whole seconds. Also reads Apple reference-date numbers. */
object InstantSerializer : KSerializer<Instant> {
    /** 2001-01-01T00:00:00Z, the reference date of Foundation's `Date`. */
    private const val APPLE_REFERENCE_EPOCH = 978_307_200L

    override val descriptor = PrimitiveSerialDescriptor("app.encave.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(format(value))

    override fun deserialize(decoder: Decoder): Instant {
        if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement().jsonPrimitive
            if (!element.isString) {
                val seconds = element.doubleOrNull ?: throw SerializationException("Invalid date: $element")
                return Instant.ofEpochSecond(APPLE_REFERENCE_EPOCH).plusNanos((seconds * 1e9).toLong())
            }
            return parse(element.content)
        }
        return parse(decoder.decodeString())
    }

    fun format(value: Instant): String = DateTimeFormatter.ISO_INSTANT.format(value.truncatedTo(ChronoUnit.SECONDS))

    fun parse(text: String): Instant = try {
        Instant.parse(text)
    } catch (_: Exception) {
        OffsetDateTime.parse(text).toInstant()
    }
}

/** `BigDecimal` as a bare JSON number ("12.5", "24"), like Swift's `Decimal`. */
object DecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor = PrimitiveSerialDescriptor("app.encave.Decimal", PrimitiveKind.DOUBLE)

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        val text = plain(value)
        if (encoder is JsonEncoder) encoder.encodeJsonElement(JsonUnquotedLiteral(text)) else encoder.encodeString(text)
    }

    override fun deserialize(decoder: Decoder): BigDecimal {
        val text = if (decoder is JsonDecoder) decoder.decodeJsonElement().jsonPrimitive.content else decoder.decodeString()
        return text.toBigDecimalOrNull() ?: throw SerializationException("Invalid decimal: $text")
    }

    /** "24" rather than "24.00" or "2.4E+1", like Swift's `"\(Decimal)"`. */
    fun plain(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()
}

/** The cellar, with Swift's dictionary encoding (alternating key/value arrays). */
object CellarSerializer : KSerializer<Cellar> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Cellar) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("Cellar can only be encoded as JSON")
        val json = output.json
        output.encodeJsonElement(
            buildJsonObject {
                put("bottles", buildJsonArray {
                    value.bottles.forEach { (id, bottle) ->
                        add(JsonPrimitive(id.toString().uppercase(Locale.ROOT)))
                        add(json.encodeToJsonElement(Bottle.serializer(), bottle))
                    }
                })
                put("racks", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(Rack.serializer()), value.racks))
                put("schemaVersion", JsonPrimitive(value.schemaVersion))
                put("settings", json.encodeToJsonElement(CellarSettings.serializer(), value.settings))
                put("wines", buildJsonArray {
                    value.wines.forEach { (id, wine) ->
                        add(JsonPrimitive(id.toString().uppercase(Locale.ROOT)))
                        add(json.encodeToJsonElement(Wine.serializer(), wine))
                    }
                })
            },
        )
    }

    override fun deserialize(decoder: Decoder): Cellar {
        val input = decoder as? JsonDecoder ?: throw SerializationException("Cellar can only be decoded from JSON")
        val json = input.json
        val root = input.decodeJsonElement() as? JsonObject ?: throw SerializationException("Cellar must be an object")
        val wines = entries(root["wines"]).map { json.decodeFromJsonElement(Wine.serializer(), it) }
        val bottles = entries(root["bottles"]).map { json.decodeFromJsonElement(Bottle.serializer(), it) }
        val racks = (root["racks"] as? JsonArray).orEmpty().map { json.decodeFromJsonElement(Rack.serializer(), it) }
        val settings = root["settings"]?.let { json.decodeFromJsonElement(CellarSettings.serializer(), it) } ?: CellarSettings()
        // Older files are upgraded on load, like the Swift decoder does.
        return Cellar(wines, bottles, racks, settings)
    }

    /** Values of a Swift dictionary: `[key, value, key, value]`, or a keyed object. */
    private fun entries(element: JsonElement?): List<JsonElement> = when (element) {
        null -> emptyList()
        is JsonArray -> element.filterIndexed { index, _ -> index % 2 == 1 }
        is JsonObject -> element.values.toList()
        else -> throw SerializationException("Unexpected dictionary encoding")
    }
}

/** Full backup format: the whole cellar in a small envelope. */
@kotlinx.serialization.Serializable
data class CellarBackup(
    val format: String,
    val version: Int,
    @kotlinx.serialization.Serializable(with = InstantSerializer::class) val exportedAt: Instant,
    @kotlinx.serialization.Serializable(with = CellarSerializer::class) val cellar: Cellar,
) {
    companion object {
        const val FORMAT = "encave"
        fun of(cellar: Cellar, exportedAt: Instant = Instant.now()) =
            CellarBackup(FORMAT, Cellar.CURRENT_SCHEMA_VERSION, exportedAt, cellar)
    }
}

/** JSON configuration shared by the on-device store and backups. */
object CellarJson {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }

    private val pretty = Json(json) {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun encode(cellar: Cellar): String = json.encodeToString(CellarSerializer, cellar)

    fun decode(text: String): Cellar = json.decodeFromString(CellarSerializer, text)

    /** Pretty-printed backup with sorted keys, like the Apple export. */
    fun backup(cellar: Cellar, exportedAt: Instant = Instant.now()): String {
        val element = json.encodeToJsonElement(CellarBackup.serializer(), CellarBackup.of(cellar, exportedAt))
        return pretty.encodeToString(JsonElement.serializer(), sortKeys(element))
    }

    fun backupFrom(text: String): CellarBackup {
        val backup = try {
            json.decodeFromString(CellarBackup.serializer(), text)
        } catch (_: Exception) {
            throw ImportException(ImportError.NotAnEncaveBackup)
        }
        if (backup.format != CellarBackup.FORMAT) throw ImportException(ImportError.NotAnEncaveBackup)
        return backup
    }

    internal fun sortKeys(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.toSortedMap().mapValues { sortKeys(it.value) })
        is JsonArray -> JsonArray(element.map { sortKeys(it) })
        else -> element
    }
}
