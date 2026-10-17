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

    override fun serialize(encoder: Encoder, value: Instant) {
        // The sync payload and the on-device file write numbers, like Swift's `.deferredToDate`.
        if (encoder is JsonEncoder && encoder.serializersModule.getContextual(ReferenceDates::class) != null) {
            encoder.encodeJsonElement(JsonUnquotedLiteral(referenceSeconds(value)))
        } else {
            encoder.encodeString(format(value))
        }
    }

    /** Seconds since 2001-01-01 as a plain decimal number ("782003123.5"), the exact value Swift stores. */
    fun referenceSeconds(value: Instant): String =
        BigDecimal(referenceDouble(value).toString()).stripTrailingZeros().toPlainString()

    fun referenceDouble(value: Instant): Double = (value.epochSecond - APPLE_REFERENCE_EPOCH).toDouble() + value.nano / 1e9

    /** The instant of a Swift `Date` (a Double of seconds since 2001-01-01). */
    fun fromReferenceSeconds(seconds: Double): Instant {
        val whole = Math.floor(seconds)
        val nanos = Math.round((seconds - whole) * 1e9)
        return Instant.ofEpochSecond(APPLE_REFERENCE_EPOCH + whole.toLong(), nanos)
    }

    override fun deserialize(decoder: Decoder): Instant {
        if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement().jsonPrimitive
            if (!element.isString) {
                val seconds = element.doubleOrNull ?: throw SerializationException("Invalid date: $element")
                return fromReferenceSeconds(seconds)
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

/**
 * Marker: a [Json] whose serializers module has a contextual serializer for this class
 * writes dates as numbers of seconds since 2001-01-01 instead of ISO-8601 strings.
 */
object ReferenceDates {
    internal object Marker : KSerializer<ReferenceDates> {
        override val descriptor = PrimitiveSerialDescriptor("app.encave.ReferenceDates", PrimitiveKind.STRING)
        override fun serialize(encoder: Encoder, value: ReferenceDates) = encoder.encodeString("")
        override fun deserialize(decoder: Decoder): ReferenceDates = ReferenceDates
    }

    val module = kotlinx.serialization.modules.SerializersModule { contextual(ReferenceDates::class, Marker) }

    /**
     * Now, as an instant that a Swift `Date` (a Double of seconds) holds exactly, so it
     * reads back the same after a sync. Use it for every date the app records.
     */
    fun now(): Instant = canonical(Instant.now())

    fun canonical(value: Instant): Instant = InstantSerializer.fromReferenceSeconds(InstantSerializer.referenceDouble(value))
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
                put("discoveries", buildJsonArray {
                    value.discoveries.forEach { (id, discovery) ->
                        add(JsonPrimitive(id.toString().uppercase(Locale.ROOT)))
                        add(json.encodeToJsonElement(Discovery.serializer(), discovery))
                    }
                })
                put("racks", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(Rack.serializer()), value.racks))
                put("schemaVersion", JsonPrimitive(value.schemaVersion))
                put("settings", json.encodeToJsonElement(CellarSettings.serializer(), value.settings))
                put("wines", buildJsonArray {
                    value.wines.forEach { (id, wine) ->
                        add(JsonPrimitive(id.toString().uppercase(Locale.ROOT)))
                        add(encodeWine(json, wine))
                    }
                })
            },
        )
    }

    override fun deserialize(decoder: Decoder): Cellar {
        val input = decoder as? JsonDecoder ?: throw SerializationException("Cellar can only be decoded from JSON")
        val json = input.json
        val root = input.decodeJsonElement() as? JsonObject ?: throw SerializationException("Cellar must be an object")
        // Keyed by each object's own ID, whatever the file says: for an ID given twice, the
        // entry with the smallest key wins (as CaveKit re-keys), and racks keep the first.
        val wines = entries(root["wines"]).map { json.decodeFromJsonElement(Wine.serializer(), it) }
        val bottles = entries(root["bottles"]).map { json.decodeFromJsonElement(Bottle.serializer(), it) }
        val racks = (root["racks"] as? JsonArray).orEmpty().map { json.decodeFromJsonElement(Rack.serializer(), it) }
        val settings = root["settings"]?.let { json.decodeFromJsonElement(CellarSettings.serializer(), it) } ?: CellarSettings()
        val discoveries = entries(root["discoveries"]).map { json.decodeFromJsonElement(Discovery.serializer(), it) }
        // Older files are upgraded on load, like the Swift decoder does.
        return Cellar(wines, bottles, racks, settings, discoveries)
    }

    /** A wine as Swift writes it: optional lists ("barcodes", "tags") are left out when empty. */
    internal fun encodeWine(json: Json, wine: Wine): JsonElement {
        val element = json.encodeToJsonElement(Wine.serializer(), wine) as JsonObject
        val empty = listOf("barcodes", "tags").filter { (element[it] as? JsonArray)?.isEmpty() == true }
        return if (empty.isEmpty()) element else JsonObject(element - empty.toSet())
    }

    /**
     * Values of a Swift dictionary (`[key, value, key, value]`, or a keyed object), in file
     * order. Objects are keyed by their own "id" later; for an ID given twice, the entry
     * with the smallest key (as an uppercase string) comes first, so it is the one kept.
     */
    private fun entries(element: JsonElement?): List<JsonElement> {
        val pairs = when (element) {
            null -> emptyList()
            is JsonArray -> element.chunked(2).filter { it.size == 2 }.map { (key, value) -> key.jsonPrimitive.content to value }
            is JsonObject -> element.entries.map { it.key to it.value }
            else -> throw SerializationException("Unexpected dictionary encoding")
        }
        val ownId = { value: JsonElement -> ((value as? JsonObject)?.get("id") as? JsonPrimitive)?.content?.uppercase(Locale.ROOT) }
        val winners = pairs.groupBy { ownId(it.second) }.mapValues { (_, same) -> same.minBy { it.first.uppercase(Locale.ROOT) } }
        return pairs.filter { pair -> ownId(pair.second).let { it == null || winners[it] === pair } }.map { it.second }
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

    /** Like [json], with dates as Swift writes them in its cellar file: exact numbers, so they round-trip. */
    val referenceJson = Json(json) { serializersModule = ReferenceDates.module }

    /** The on-device file and the sync payload: dates as numbers (backups keep ISO-8601). */
    fun encode(cellar: Cellar): String = referenceJson.encodeToString(CellarSerializer, cellar)

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
