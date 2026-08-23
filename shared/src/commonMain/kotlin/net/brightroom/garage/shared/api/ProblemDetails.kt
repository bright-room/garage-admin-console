package net.brightroom.garage.shared.api

import kotlinx.serialization.Serializable

/**
 * RFC 9457 (Problem Details for HTTP APIs) のエラー表現。
 *
 * `application/problem+json` としてレスポンスのトップレベルに置く。ラッパーは被せない。
 *
 * 分岐に使うステータスは HTTP レスポンス自体が運ぶ。この型が持つ [status] は
 * RFC 9457 が定める表現上のメンバーであり、判定には使わない。
 *
 * @param title 問題の種類を表す短い要約。`type` を省略した場合は
 *   その status の推奨理由句であるべきと RFC 9457 が定めている。
 * @param status HTTP ステータスコード。RFC 9457 は JSON number と定める。
 * @param type 問題の種類を識別する URI。省略時は `about:blank` とみなされる。
 *   S3 ブラウザの縮退 2 種（バケットがアドレス不能、使えるキーが無い）は
 *   [ProblemTypes] の URN を入れる。それ以外は常に省略する。
 * @param detail この発生に固有の説明。Garage が返した本文を入れる。
 * @param instance この発生を識別する URI。リクエストパスを入れる。
 * @param operation 拡張メンバー。原因となった Garage の operation 名。
 *   サーバー内部で起きたエラーでは null。
 */
@Serializable
data class ProblemDetails(
    val title: String,
    val status: Int,
    val detail: String? = null,
    val type: String? = null,
    val instance: String? = null,
    val operation: String? = null,
)
