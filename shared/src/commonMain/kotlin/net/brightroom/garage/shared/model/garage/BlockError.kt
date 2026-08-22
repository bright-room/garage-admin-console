package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.Serializable

/** `ListBlockErrors` の要素。再同期に失敗しているブロックを表す。 */
@Serializable
data class BlockError(
    val blockHash: String,
    val refcount: Long,
    val errorCount: Long,
    val lastTrySecsAgo: Long,
    val nextTryInSecs: Long,
)
