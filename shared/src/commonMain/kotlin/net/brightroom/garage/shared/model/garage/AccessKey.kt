package net.brightroom.garage.shared.model.garage

import kotlin.time.Instant
import kotlinx.serialization.Serializable

/** `ListKeys` の要素。 */
@Serializable
data class KeySummary(
    val id: String,
    val name: String,
    val expired: Boolean,
    val created: Instant? = null,
    val expiration: Instant? = null,
)

/**
 * `GetKeyInfo` のレスポンス。`CreateKey` / `ImportKey` / `UpdateKey` も同じ形を返す。
 *
 * [secretAccessKey] は `showSecretKey=true` を付けたとき、および作成直後の
 * レスポンスにだけ入る。サーバーはこの値をキャッシュにもログにも残さない。
 */
@Serializable
data class KeyInfo(
    val accessKeyId: String,
    val name: String,
    val expired: Boolean,
    val permissions: KeyPermissions = KeyPermissions(),
    val buckets: List<KeyBucket> = emptyList(),
    val created: Instant? = null,
    val expiration: Instant? = null,
    val secretAccessKey: String? = null,
) {
    /** secret をログに出さない。`equals` / `hashCode` は既定のままでよい。 */
    override fun toString(): String =
        "KeyInfo(accessKeyId=$accessKeyId, name=$name, expired=$expired, " +
            "secretAccessKey=${if (secretAccessKey == null) "null" else "<redacted>"})"
}

@Serializable
data class KeyPermissions(
    val createBucket: Boolean = false,
)

/** そのキーが権限を持つバケット。 */
@Serializable
data class KeyBucket(
    val id: String,
    val globalAliases: List<String> = emptyList(),
    val localAliases: List<String> = emptyList(),
    val permissions: BucketKeyPermissions,
) {
    val displayName: String
        get() = globalAliases.firstOrNull() ?: localAliases.firstOrNull() ?: id.take(12)
}
