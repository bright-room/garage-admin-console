@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * JSON の形が実行時に変わる型（OpenAPI の oneOf）を扱うための土台。
 *
 * 直列化を `value::class` からの serializer 探索に委ねない。探索はリフレクションに
 * 依存し、wasmJs で同じように動く保証が無いため。代わりに周囲の [Json] インスタンス
 * （[JsonDecoder.json] / [JsonEncoder.json]）だけを使って明示的に組み立てる。
 */
abstract class JsonShapeSerializer<T>(private val name: String) : KSerializer<T> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(name)

    protected abstract fun fromJson(json: Json, element: JsonElement): T

    protected abstract fun toJson(json: Json, value: T): JsonElement

    final override fun deserialize(decoder: Decoder): T {
        val jsonDecoder = decoder as? JsonDecoder ?: throw SerializationException("$name は JSON でのみ扱える")

        return fromJson(jsonDecoder.json, jsonDecoder.decodeJsonElement())
    }

    final override fun serialize(encoder: Encoder, value: T) {
        val jsonEncoder = encoder as? JsonEncoder ?: throw SerializationException("$name は JSON でのみ扱える")

        jsonEncoder.encodeJsonElement(toJson(jsonEncoder.json, value))
    }
}

/** 文字列そのものを取り出す。文字列でなければ null。 */
internal fun JsonElement.asStringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

object WorkerStateSerializer : JsonShapeSerializer<WorkerState>("WorkerState") {
    private const val THROTTLED = "throttled"
    private const val DURATION_SECS = "durationSecs"

    override fun fromJson(json: Json, element: JsonElement): WorkerState {
        element.asStringOrNull()?.let { name ->
            return when (name) {
                "idle" -> WorkerState.Idle
                "busy" -> WorkerState.Busy
                "done" -> WorkerState.Done
                else -> throw SerializationException("未知のワーカー状態: $name")
            }
        }

        val throttled = (element as? JsonObject)?.get(THROTTLED)
            ?: throw SerializationException("ワーカー状態として解釈できません")

        return WorkerState.Throttled(throttled.jsonObject.getValue(DURATION_SECS).jsonPrimitive.double)
    }

    override fun toJson(json: Json, value: WorkerState): JsonElement = when (value) {
        WorkerState.Idle, WorkerState.Busy, WorkerState.Done -> JsonPrimitive(value.label)

        is WorkerState.Throttled -> buildJsonObject {
            putJsonObject(THROTTLED) { put(DURATION_SECS, value.durationSecs) }
        }
    }
}

object ZoneRedundancySerializer : JsonShapeSerializer<ZoneRedundancy>("ZoneRedundancy") {
    private const val MAXIMUM = "maximum"
    private const val AT_LEAST = "atLeast"

    override fun fromJson(json: Json, element: JsonElement): ZoneRedundancy {
        element.asStringOrNull()?.let { name ->
            if (name == MAXIMUM) return ZoneRedundancy.Maximum

            throw SerializationException("未知のゾーン冗長度: $name")
        }

        val atLeast = (element as? JsonObject)?.get(AT_LEAST)
            ?: throw SerializationException("ゾーン冗長度として解釈できません")

        return ZoneRedundancy.AtLeast(atLeast.jsonPrimitive.int)
    }

    override fun toJson(json: Json, value: ZoneRedundancy): JsonElement = when (value) {
        ZoneRedundancy.Maximum -> JsonPrimitive(MAXIMUM)
        is ZoneRedundancy.AtLeast -> buildJsonObject { put(AT_LEAST, value.zones) }
    }
}

object NodeRoleChangeSerializer : JsonShapeSerializer<NodeRoleChange>("NodeRoleChange") {
    private const val ID = "id"
    private const val REMOVE = "remove"
    private const val ZONE = "zone"
    private const val TAGS = "tags"
    private const val CAPACITY = "capacity"

    override fun fromJson(json: Json, element: JsonElement): NodeRoleChange {
        val obj = element as? JsonObject ?: throw SerializationException("ロールの変更はオブジェクトでなければならない")
        val id = obj.getValue(ID).jsonPrimitive.content

        if (REMOVE in obj) return NodeRoleChange.Remove(id)

        return NodeRoleChange.Assign(
            id = id,
            zone = obj.getValue(ZONE).jsonPrimitive.content,
            tags = obj[TAGS]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            capacity = obj[CAPACITY]?.jsonPrimitive?.longOrNull,
        )
    }

    override fun toJson(json: Json, value: NodeRoleChange): JsonElement = buildJsonObject {
        put(ID, value.id)

        when (value) {
            is NodeRoleChange.Remove -> put(REMOVE, true)

            is NodeRoleChange.Assign -> {
                put(ZONE, value.zone)
                putJsonArray(TAGS) { value.tags.forEach { add(it) } }
                // gateway ノードは capacity を持たない。null を送ると容量 0 の
                // ストレージノードとして解釈されうるため、キーごと落とす
                value.capacity?.let { put(CAPACITY, it) }
            }
        }
    }
}

object LayoutPreviewSerializer : JsonShapeSerializer<LayoutPreview>("LayoutPreview") {
    private const val ERROR = "error"

    override fun fromJson(json: Json, element: JsonElement): LayoutPreview {
        val obj = element as? JsonObject ?: throw SerializationException("preview はオブジェクトでなければならない")

        val serializer = if (ERROR in obj) {
            LayoutPreview.Failed.serializer()
        } else {
            LayoutPreview.Computed.serializer()
        }

        return json.decodeFromJsonElement(serializer, obj)
    }

    override fun toJson(json: Json, value: LayoutPreview): JsonElement = when (value) {
        is LayoutPreview.Failed -> json.encodeToJsonElement(LayoutPreview.Failed.serializer(), value)
        is LayoutPreview.Computed -> json.encodeToJsonElement(LayoutPreview.Computed.serializer(), value)
    }
}
