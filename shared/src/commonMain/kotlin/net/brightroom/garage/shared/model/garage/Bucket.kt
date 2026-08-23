package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** `ListBuckets` の要素。 */
@Serializable
data class BucketSummary(
    val id: String,
    val globalAliases: List<String> = emptyList(),
    val localAliases: List<BucketLocalAlias> = emptyList(),
    val created: Instant? = null,
) {
    /** 画面に出す名前。alias が無いバケットは ID の先頭で代用する。 */
    val displayName: String
        get() = globalAliases.firstOrNull()
            ?: localAliases.firstOrNull()?.alias
            ?: id.take(12)
}

@Serializable
data class BucketLocalAlias(val accessKeyId: String, val alias: String)

/** `GetBucketInfo` のレスポンス。`CreateBucket` と `UpdateBucket` も同じ形を返す。 */
@Serializable
data class BucketInfo(
    val id: String,
    val globalAliases: List<String> = emptyList(),
    val websiteAccess: Boolean = false,
    val keys: List<BucketKey> = emptyList(),
    val objects: Long = 0,
    val bytes: Long = 0,
    val unfinishedUploads: Long = 0,
    val unfinishedMultipartUploads: Long = 0,
    val unfinishedMultipartUploadParts: Long = 0,
    val unfinishedMultipartUploadBytes: Long = 0,
    val quotas: BucketQuotas = BucketQuotas(),
    val created: Instant? = null,
    val websiteConfig: BucketWebsiteConfig? = null,
    /** 未設定なら null。空配列を送ると削除される（実機で確認済み）。 */
    val corsRules: List<CorsRule>? = null,
    val lifecycleRules: List<LifecycleRule>? = null,
) {
    /** 画面に出す名前。alias が無いバケットは ID の先頭で代用する。 */
    val displayName: String
        get() = globalAliases.firstOrNull()
            ?: keys.firstNotNullOfOrNull { it.bucketLocalAliases.firstOrNull() }
            ?: id.take(12)
}

/** そのバケットに権限を持つアクセスキー。 */
@Serializable
data class BucketKey(
    val accessKeyId: String,
    val name: String,
    val permissions: BucketKeyPermissions,
    val bucketLocalAliases: List<String> = emptyList(),
)

@Serializable
data class BucketKeyPermissions(val owner: Boolean = false, val read: Boolean = false, val write: Boolean = false) {
    /**
     * S3 資格情報を導出するときの優先度（spec §6.4 の owner > read+write > read）。
     *
     * 0 はオブジェクト操作に使えないことを表す。read が無いと一覧すらできないため、
     * write だけのキーも 0 とする。
     */
    val rank: Int
        get() = when {
            owner -> 3
            read && write -> 2
            read -> 1
            else -> 0
        }
}

@Serializable
data class BucketQuotas(val maxObjects: Long? = null, val maxSize: Long? = null)

/**
 * website 公開の設定。
 *
 * `routingRules` はコンソールの編集対象外のため持たない（P2-4）。
 * `UpdateBucket` に `routingRules` を含めなくても Garage 側は保持する
 * （実機で確認済み）ので、持たないことで既存設定を壊すことはない。
 */
@Serializable
data class BucketWebsiteConfig(val indexDocument: String, val errorDocument: String? = null)

/**
 * CORS ルール。
 *
 * フィールド名は S3 の XML 由来で PascalCase である。Garage は camelCase を
 * 受け付けないため `@SerialName` を外してはならない。
 */
@Serializable
data class CorsRule(
    @SerialName("AllowedOrigin") val allowedOrigins: List<String> = emptyList(),
    @SerialName("AllowedMethod") val allowedMethods: List<String> = emptyList(),
    @SerialName("AllowedHeader") val allowedHeaders: List<String> = emptyList(),
    @SerialName("ExposeHeader") val exposeHeaders: List<String> = emptyList(),
    @SerialName("MaxAgeSeconds") val maxAgeSeconds: Long? = null,
    @SerialName("ID") val id: String? = null,
)

@Serializable
enum class LifecycleStatus {
    @SerialName("Enabled")
    ENABLED,

    @SerialName("Disabled")
    DISABLED,
}

/** オブジェクトのライフサイクルルール。CORS と同じく PascalCase。 */
@Serializable
data class LifecycleRule(
    @SerialName("Status") val status: LifecycleStatus,
    @SerialName("ID") val id: String? = null,
    @SerialName("Filter") val filter: LifecycleFilter? = null,
    @SerialName("Expiration") val expiration: LifecycleExpiration? = null,
    @SerialName("AbortIncompleteMultipartUpload")
    val abortIncompleteMultipartUpload: AbortIncompleteMultipartUpload? = null,
)

/**
 * ルールの適用対象。
 *
 * [and] は S3 では再帰的だが、コンソールが編集するのは 1 段までとする（P2-5）。
 * 読み取りでは落とさずそのまま保持し、書き戻しでも壊さない。
 */
@Serializable
data class LifecycleFilter(
    @SerialName("Prefix") val prefix: String? = null,
    @SerialName("ObjectSizeGreaterThan") val objectSizeGreaterThan: Long? = null,
    @SerialName("ObjectSizeLessThan") val objectSizeLessThan: Long? = null,
    @SerialName("And") val and: LifecycleFilter? = null,
)

/** [days] と [date] は排他。Garage はどちらの形も返す（実機で確認済み）。 */
@Serializable
data class LifecycleExpiration(
    @SerialName("Days") val days: Long? = null,
    @SerialName("Date") val date: String? = null,
)

@Serializable
data class AbortIncompleteMultipartUpload(@SerialName("DaysAfterInitiation") val daysAfterInitiation: Long)
