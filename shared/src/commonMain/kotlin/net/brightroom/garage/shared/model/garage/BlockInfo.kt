package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GetBlockInfo` のレスポンス。 */
@Serializable
data class BlockInfo(val blockHash: String, val refcount: Long, val versions: List<BlockVersion> = emptyList())

@Serializable
data class BlockVersion(
    val versionId: String,
    val refDeleted: Boolean,
    val versionDeleted: Boolean,
    val garbageCollected: Boolean,
    val backlink: BlockVersionBacklink? = null,
)

/**
 * このブロックを参照しているもの。
 *
 * OpenAPI では `{"object": …}` と `{"upload": …}` の oneOf だが、両方ともオブジェクトで
 * キーが排他であるため、nullable のフィールド 2 つで表せる。`explicitNulls = false` の
 * もとでは片方だけが JSON に出る。カスタム serializer が要るのは、文字列とオブジェクトが
 * 同じ位置に来る場合だけである。
 */
@Serializable
data class BlockVersionBacklink(
    @SerialName("object")
    val storedObject: BacklinkObject? = null,
    val upload: BacklinkUpload? = null,
)

@Serializable
data class BacklinkObject(val bucketId: String, val key: String)

@Serializable
data class BacklinkUpload(
    val uploadId: String,
    val uploadDeleted: Boolean,
    val uploadGarbageCollected: Boolean,
    val bucketId: String? = null,
    val key: String? = null,
)
