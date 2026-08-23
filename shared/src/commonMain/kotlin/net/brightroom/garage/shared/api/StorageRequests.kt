package net.brightroom.garage.shared.api

import kotlinx.serialization.Serializable
import net.brightroom.garage.shared.model.garage.BucketKeyPermissions
import net.brightroom.garage.shared.model.garage.BucketQuotas
import net.brightroom.garage.shared.model.garage.CorsRule
import net.brightroom.garage.shared.model.garage.LifecycleRule
import kotlin.time.Instant

/**
 * ブラウザ → サーバーのリクエスト。
 *
 * サーバーはこれを型で受け、Garage の operation ごとの形に写して転送する。
 * 生の JSON を素通しさせないための層である（spec D5）。
 */
@Serializable
data class CreateBucketRequest(
    /** null なら alias 無しで作る。 */
    val globalAlias: String? = null,
)

/**
 * バケットの設定変更。
 *
 * **null と空リストの違いに意味がある**（実機で確認済み）。
 * - null（＝ JSON に出ない）: そのフィールドは変更しない
 * - `emptyList()`（＝ `[]`）: そのルールを削除する
 * - `BucketQuotas(null, null)`: quota を解除する
 *
 * サーバーとブラウザの双方で `Json { explicitNulls = false }` を使うことが前提。
 */
@Serializable
data class UpdateBucketRequest(
    val quotas: BucketQuotas? = null,
    val websiteAccess: WebsiteAccessRequest? = null,
    val corsRules: List<CorsRule>? = null,
    val lifecycleRules: List<LifecycleRule>? = null,
)

/**
 * website 公開の設定。
 *
 * Garage は `enabled = true` のとき [indexDocument] を必須とし、
 * `enabled = false` のときはどちらのドキュメントも指定してはならない。
 */
@Serializable
data class WebsiteAccessRequest(
    val enabled: Boolean,
    val indexDocument: String? = null,
    val errorDocument: String? = null,
)

@Serializable
data class BucketAliasRequest(val alias: String)

@Serializable
data class BucketKeyPermissionRequest(val permissions: BucketKeyPermissions)

@Serializable
data class CleanupUploadsRequest(val olderThanSecs: Long = DEFAULT_CLEANUP_AGE_SECS)

/** 24 時間。進行中のアップロードを巻き込まないための既定値（P2-9）。 */
const val DEFAULT_CLEANUP_AGE_SECS: Long = 86_400

/** 後始末の結果。画面に「N 件を削除しました」と出すためだけに持つ。 */
@Serializable
data class CleanupUploadsResult(val uploadsDeleted: Long)

@Serializable
data class CreateKeyRequest(val name: String, val allowCreateBucket: Boolean = false, val expiration: Instant? = null)

@Serializable
data class ImportKeyRequest(val name: String, val accessKeyId: String, val secretAccessKey: String) {
    /** secret をログに出さない。`equals` / `hashCode` は既定のままでよい。 */
    override fun toString(): String =
        "ImportKeyRequest(name=$name, accessKeyId=$accessKeyId, secretAccessKey=<redacted>)"
}

/**
 * キーの更新。
 *
 * [allowCreateBucket] が null なら権限は変更しない。
 * [neverExpires] が true なら期限を解除し、[expiration] は無視される。
 */
@Serializable
data class UpdateKeyRequest(
    val name: String? = null,
    val allowCreateBucket: Boolean? = null,
    val expiration: Instant? = null,
    val neverExpires: Boolean = false,
)
