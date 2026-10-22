package app.encave.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject

/** No unknown keys: the value of `unknownKeys` for records made on this device. */
val NoUnknownKeys = JsonObject(emptyMap())

/**
 * These keys, or those of [stored] when there are none: an edit made from a form, which
 * builds the record again from the fields it shows, keeps the keys of the stored record.
 */
internal fun JsonObject.orKept(stored: JsonObject?): JsonObject = if (isEmpty() && stored != null) stored else this

/**
 * The serializer of a synced record that keeps the keys this version of Encave does not
 * know, so that writing the record back (after an edit, in a sync message, a file or a
 * backup) does not erase fields added by a newer version (docs/SYNC.md §1). They are kept
 * as raw JSON and written back unchanged; a key the record knows is never kept, so known
 * keys always win. Records without unknown keys are written exactly as before. Mirrors
 * CaveKit's `UnknownFields`.
 */
abstract class KeepingUnknownKeys<T : Any>(
    private val generated: KSerializer<T>,
    /** Keys no longer written but still read (or ignored on purpose), never kept. */
    legacyKeys: Set<String> = emptySet(),
) : KSerializer<T> {
    override val descriptor: SerialDescriptor get() = generated.descriptor

    private val known: Set<String> by lazy {
        (0 until generated.descriptor.elementsCount).map { generated.descriptor.getElementName(it) }.toSet() + legacyKeys
    }

    protected abstract fun unknownKeys(value: T): JsonObject

    protected abstract fun withUnknownKeys(value: T, keys: JsonObject): T

    override fun serialize(encoder: Encoder, value: T) {
        val keys = unknownKeys(value)
        if (keys.isEmpty() || encoder !is JsonEncoder) return generated.serialize(encoder, value)
        val element = encoder.json.encodeToJsonElement(generated, value) as JsonObject
        encoder.encodeJsonElement(JsonObject(element + keys.filterKeys { it !in known && it !in element }))
    }

    override fun deserialize(decoder: Decoder): T {
        if (decoder !is JsonDecoder) return generated.deserialize(decoder)
        val element = decoder.decodeJsonElement()
        val value = decoder.json.decodeFromJsonElement(generated, element)
        val keys = (element as? JsonObject)?.filterKeys { it !in known }.orEmpty()
        return if (keys.isEmpty()) value else withUnknownKeys(value, JsonObject(keys))
    }
}
