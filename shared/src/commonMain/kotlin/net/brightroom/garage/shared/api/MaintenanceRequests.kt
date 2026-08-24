package net.brightroom.garage.shared.api

import kotlinx.serialization.Serializable
import net.brightroom.garage.shared.model.garage.AdminToken
import kotlin.time.Instant

/**
 * 副作用だけの operation の結果。
 *
 * Garage は `MultiResponse` の `success` にノードごとの `null` を返す。値に意味が
 * 無いため、成功したノードの一覧と失敗の理由だけに落として web へ渡す。
 */
@Serializable
data class NodeActionOutcome(val ok: List<String> = emptyList(), val failed: Map<String, String> = emptyMap())

/** `PUT /api/workers/variables`。 */
@Serializable
data class SetWorkerVariableRequest(val variable: String, val value: String)

/**
 * `POST /api/blocks/{hash}/retry-resync` と全件再試行。
 *
 * [all] が true なら [blockHashes] は無視される。両方が空なら 400 になる。
 */
@Serializable
data class RetryResyncRequest(val all: Boolean = false, val blockHashes: List<String> = emptyList())

/** `POST /api/blocks/purge`。全件を対象にする指定は用意しない（P3-14）。 */
@Serializable
data class PurgeBlocksRequest(val blockHashes: List<String>)

/**
 * `POST /api/admin-tokens`。
 *
 * [expiration] が null なら無期限のトークンを作る。
 */
@Serializable
data class CreateAdminTokenRequest(val name: String, val scope: List<String>, val expiration: Instant? = null)

/**
 * `PATCH /api/admin-tokens/{id}`。省略したフィールドは変更されない。
 *
 * @param neverExpires true にすると [expiration] を無視して無期限にする。
 */
@Serializable
data class UpdateAdminTokenRequest(
    val name: String? = null,
    val scope: List<String>? = null,
    val expiration: Instant? = null,
    val neverExpires: Boolean = false,
)

/**
 * `POST /api/admin-tokens` の応答。
 *
 * [secretToken] は Garage が一度しか返さない。サーバーはこれを保持せず、
 * ログにも出さず、この応答としてのみ返す。
 */
@Serializable
data class CreatedAdminToken(val token: AdminToken, val secretToken: String)
