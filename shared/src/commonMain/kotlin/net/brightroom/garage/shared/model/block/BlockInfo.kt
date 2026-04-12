package net.brightroom.garage.shared.model.block

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class BlockError(
    val blockHash: String,
    val refcount: Long,
    val errorCount: Long,
    val lastTrySecsAgo: Long,
    val nextTryInSecs: Long,
)

@Serializable
data class BlockInfo(
    val blockHash: String,
    val refcount: Long,
    val versions: List<BlockVersion>,
)

@Serializable
data class BlockVersion(
    val versionId: String,
    val refDeleted: Boolean,
    val versionDeleted: Boolean,
    val garbageCollected: Boolean,
    val backlink: JsonElement? = null,
)

@Serializable
data class PurgeBlocksResponse(
    val blocksPurged: Long,
    val objectsDeleted: Long,
    val uploadsDeleted: Long,
    val versionsDeleted: Long,
    val blockRefsPurged: Long,
)

@Serializable
data class RetryBlockResyncResponse(
    val count: Long,
)
