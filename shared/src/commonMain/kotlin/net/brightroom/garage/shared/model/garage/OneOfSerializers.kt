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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
