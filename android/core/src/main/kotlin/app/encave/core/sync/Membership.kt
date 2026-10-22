package app.encave.core.sync

import app.encave.core.InstantSerializer
import app.encave.core.KeepingUnknownKeys
import app.encave.core.NoUnknownKeys
import app.encave.core.ReferenceDates
import app.encave.core.UuidSerializer
import app.encave.core.parseUuid
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A device of the cellar. */
@KeepGeneratedSerializer
@Serializable(with = MemberSerializer::class)
data class Member(
    @Serializable(with = UuidSerializer::class) val id: UUID,
    val name: String,
    /** X25519 public key, 32 bytes. */
    @Serializable(with = Base64Serializer::class) val publicKey: ByteArray,
    /** "ios", "ipados", "macos" or "android". */
    val platform: String,
    @Required @Serializable(with = InstantSerializer::class) val addedAt: Instant = ReferenceDates.now(),
    /**
     * The device's inbox on a relay, where others leave it new cellar keys
     * ([DeviceIdentity.inboxId]). Set by the device itself; null for records from before
     * remote sync, which then get keys only on the local network.
     */
    val inbox: String? = null,
    /** Keys written by a newer version, kept and written back unchanged (docs/SYNC.md §1). Part of equality, so a merge that only brings them in is still saved. */
    @Transient val unknownKeys: JsonObject = NoUnknownKeys,
) {
    override fun equals(other: Any?): Boolean = other is Member && id == other.id && name == other.name &&
        publicKey.contentEquals(other.publicKey) && platform == other.platform && addedAt == other.addedAt && inbox == other.inbox &&
        unknownKeys == other.unknownKeys

    override fun hashCode(): Int = listOf(id, name, publicKey.contentHashCode(), platform, addedAt, inbox, unknownKeys).hashCode()
}

/** Keeps the keys of a newer version (see [KeepingUnknownKeys]). */
object MemberSerializer : KeepingUnknownKeys<Member>(Member.generatedSerializer()) {
    override fun unknownKeys(value: Member) = value.unknownKeys
    override fun withUnknownKeys(value: Member, keys: JsonObject) = value.copy(unknownKeys = keys)
}

/**
 * The devices of a cellar, synced like the cellar itself: each change is stamped,
 * and removing a device leaves a stamp so it is not added back by an older copy.
 */
@Serializable(with = MembershipSerializer::class)
data class Membership(
    /** Shared by all devices of the cellar; set by the first device. */
    val cellarId: UUID = UUID.randomUUID(),
    val members: Map<UUID, Member> = emptyMap(),
    val stamps: Map<UUID, Stamp> = emptyMap(),
) {
    /** Advertised on the local network so devices of the same cellar find each other without showing the cellar ID. */
    val networkTag: String get() = KeySchedule.hash("encave-cellar-tag-v1", listOf(cellarId.lower.toByteArray())).copyOf(6).hex()

    /** Devices that may sync, oldest first. */
    val active: List<Member> get() = members.values.sortedBy { it.addedAt }

    /** Adds (or updates) a member. Returns the new list and the advanced clock. */
    fun adding(member: Member, clock: Long, by: UUID): Pair<Membership, Long> {
        val next = clock + 1
        return copy(members = members + (member.id to member), stamps = stamps + (member.id to Stamp(next, by))) to next
    }

    /**
     * Makes sure this device's own record names its inbox. Returns the new list and clock,
     * or null if nothing changed (the change is stamped, so it reaches the other devices).
     * A removed device stays removed.
     */
    fun publishingInbox(identity: DeviceIdentity, clock: Long): Pair<Membership, Long>? {
        val me = members[identity.id] ?: return null
        if (me.inbox == identity.inboxId) return null
        return adding(me.copy(inbox = identity.inboxId), clock, identity.id)
    }

    /** Removes a member, leaving a deletion stamp. Returns the new list and the advanced clock. */
    fun removing(id: UUID, clock: Long, by: UUID): Pair<Membership, Long> {
        val next = clock + 1
        return copy(members = members - id, stamps = stamps + (id to Stamp(next, by, deleted = true))) to next
    }

    /**
     * Combines two lists of the same cellar; each device as of its latest change. A removal
     * is final: it wins over any later stamp, so a removed device cannot add itself back by
     * sending a newer record. Pairing again gives a new device ID.
     */
    fun merged(other: Membership): Membership {
        val resultMembers = LinkedHashMap<UUID, Member>()
        val resultStamps = LinkedHashMap<UUID, Stamp>()
        for (id in stamps.keys + other.stamps.keys + members.keys + other.members.keys) {
            val a = this.stamps[id] ?: if (this.members[id] != null) Stamp.ZERO else null
            val b = other.stamps[id] ?: if (other.members[id] != null) Stamp.ZERO else null
            val useOther = when {
                a == null -> true
                b == null -> false
                a.deleted != b.deleted -> b.deleted
                else -> b > a
            }
            val stamp = if (useOther) b else a
            if (stamp != null) resultStamps[id] = stamp
            if (stamp?.deleted == true) continue
            val member = if (useOther) other.members[id] ?: members[id] else members[id] ?: other.members[id]
            if (member != null) resultMembers[id] = member
        }
        return Membership(cellarId, resultMembers, resultStamps)
    }
}

/** `{cellarID, members: [id, member, …], stamps: [id, stamp, …]}`, as Swift encodes `[UUID: …]`. */
object MembershipSerializer : KSerializer<Membership> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Membership) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("Membership is JSON only")
        val json = output.json
        output.encodeJsonElement(
            buildJsonObject {
                put("cellarID", JsonPrimitive(value.cellarId.upper))
                put("members", buildJsonArray {
                    value.members.forEach { (id, member) ->
                        add(JsonPrimitive(id.upper))
                        add(json.encodeToJsonElement(Member.serializer(), member))
                    }
                })
                put("stamps", buildJsonArray {
                    value.stamps.forEach { (id, stamp) ->
                        add(JsonPrimitive(id.upper))
                        add(json.encodeToJsonElement(Stamp.serializer(), stamp))
                    }
                })
            },
        )
    }

    override fun deserialize(decoder: Decoder): Membership {
        val input = decoder as? JsonDecoder ?: throw SerializationException("Membership is JSON only")
        val json = input.json
        val root = input.decodeJsonElement() as? JsonObject ?: throw SerializationException("Membership must be an object")
        val cellar = root["cellarID"]?.jsonPrimitive?.content ?: throw SerializationException("Missing cellarID")
        return Membership(
            cellarId = uuid(cellar),
            members = pairs(root["members"]) { json.decodeFromJsonElement(Member.serializer(), it) },
            stamps = pairs(root["stamps"]) { json.decodeFromJsonElement(Stamp.serializer(), it) },
        )
    }

    private fun <T> pairs(element: kotlinx.serialization.json.JsonElement?, decode: (kotlinx.serialization.json.JsonElement) -> T): Map<UUID, T> {
        // Required, and in Swift's encoding of a dictionary keyed by UUID only, like CaveKit's decoder.
        val array = element as? JsonArray ?: throw SerializationException("Expected a dictionary array")
        if (array.size % 2 != 0) throw SerializationException("Odd dictionary array")
        val result = LinkedHashMap<UUID, T>()
        for (index in array.indices step 2) {
            result[uuid(array[index].jsonPrimitive.content)] = decode(array[index + 1])
        }
        return result
    }

    private fun uuid(text: String): UUID = parseUuid(text) ?: throw SerializationException("Invalid UUID: $text")
}
