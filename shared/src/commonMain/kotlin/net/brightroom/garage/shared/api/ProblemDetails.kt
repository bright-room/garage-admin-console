package net.brightroom.garage.shared.api

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** RFC 9457 の `status` は JSON number である。Kotlin 側では型のある [HttpStatusCode] を使う。 */
object HttpStatusCodeSerializer : KSerializer<HttpStatusCode> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.ktor.http.HttpStatusCode", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: HttpStatusCode) {
        encoder.encodeInt(value.value)
    }

    override fun deserialize(decoder: Decoder): HttpStatusCode =
        HttpStatusCode.fromValue(decoder.decodeInt())
}

/**
 * RFC 9457 (Problem Details for HTTP APIs) のエラー表現。
 *
 * `application/problem+json` としてレスポンスのトップレベルに置く。ラッパーは被せない。
 *
 * @param title 問題の種類を表す短い要約。`type` を省略した場合は
 *   その status の推奨理由句であるべきと RFC 9457 が定めている。
 * @param type 問題の種類を識別する URI。省略時は `about:blank` とみなされる。
 *   コンソール固有の問題型を定義するまでは常に省略する。
 * @param detail この発生に固有の説明。Garage が返した本文を入れる。
 * @param instance この発生を識別する URI。リクエストパスを入れる。
 * @param operation 拡張メンバー。原因となった Garage の operation 名。
 *   サーバー内部で起きたエラーでは null。
 */
@Serializable
data class ProblemDetails(
    val title: String,
    @Serializable(with = HttpStatusCodeSerializer::class)
    val status: HttpStatusCode,
    val detail: String? = null,
    val type: String? = null,
    val instance: String? = null,
    val operation: String? = null,
) {
    companion object
}

/** `type` を省略し、`title` に status の推奨理由句を用いる標準的な組み立て方。 */
fun ProblemDetails.Companion.of(
    status: HttpStatusCode,
    detail: String? = null,
    instance: String? = null,
    operation: String? = null,
): ProblemDetails = ProblemDetails(
    title = status.description,
    status = status,
    detail = detail,
    instance = instance,
    operation = operation,
)
