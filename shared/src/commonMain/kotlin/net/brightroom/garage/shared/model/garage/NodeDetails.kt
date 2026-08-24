package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.Serializable

/** `GetNodeInfo` の 1 ノード分。 */
@Serializable
data class NodeInfo(
    val nodeId: String,
    val hostname: String? = null,
    val garageVersion: String? = null,
    val garageFeatures: List<String> = emptyList(),
    val rustVersion: String? = null,
    val dbEngine: String? = null,
)

/** `GetNodeStatistics` の 1 ノード分。 */
@Serializable
data class NodeStatistics(
    /** Garage が組み立てた人間向けのテキスト。 */
    val freeform: String = "",
    val tableStats: List<TableStat> = emptyList(),
    val blockManagerStats: BlockManagerStats? = null,
)

@Serializable
data class TableStat(
    val tableName: String,
    val items: Long,
    val merkleItems: Long,
    val merkleQueueLen: Long,
    val insertQueueLen: Long,
    val gcQueueLen: Long,
)

@Serializable
data class BlockManagerStats(
    /** 参照カウントの件数。おおよそブロック数にあたる。 */
    val rcEntries: Long,
    val resyncQueueLen: Long,
    val resyncErrors: Long,
)
