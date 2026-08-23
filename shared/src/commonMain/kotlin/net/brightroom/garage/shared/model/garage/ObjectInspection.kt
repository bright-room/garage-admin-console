package net.brightroom.garage.shared.model.garage

import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * `InspectObject` のレスポンス。
 *
 * S3 では見えない内部表現（バージョン、ブロック、インライン格納か）を出す。
 * オブジェクトブラウザの「詳細」から開く。
 */
@Serializable
data class ObjectInspection(
    val bucketId: String,
    val key: String,
    val versions: List<ObjectVersion> = emptyList(),
)

@Serializable
data class ObjectVersion(
    val uuid: String,
    val timestamp: Instant,
    val encrypted: Boolean,
    val uploading: Boolean,
    val aborted: Boolean,
    val deleteMarker: Boolean,
    val inline: Boolean,
    val size: Long? = null,
    val etag: String? = null,
    /** HTTP ヘッダの組。Garage は `[["content-type","text/plain"], ...]` の形で返す。 */
    val headers: List<List<String>> = emptyList(),
    val blocks: List<ObjectBlock> = emptyList(),
)

@Serializable
data class ObjectBlock(
    val partNumber: Long,
    val offset: Long,
    val hash: String,
    val size: Long,
)
