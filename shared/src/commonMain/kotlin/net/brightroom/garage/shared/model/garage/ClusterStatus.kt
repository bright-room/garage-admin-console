package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.Serializable

/** `GetClusterStatus` のレスポンス。 */
@Serializable
data class ClusterStatus(val layoutVersion: Long, val nodes: List<NodeResp>)

@Serializable
data class NodeResp(
    val id: String,
    val isUp: Boolean,
    /** 旧レイアウトに属し、データを退避中であることを示す。 */
    val draining: Boolean,
    val hostname: String? = null,
    val addr: String? = null,
    val garageVersion: String? = null,
    val lastSeenSecsAgo: Long? = null,
    val role: NodeAssignedRole? = null,
    val dataPartition: FreeSpace? = null,
    val metadataPartition: FreeSpace? = null,
)

@Serializable
data class NodeAssignedRole(
    val zone: String,
    val tags: List<String>,
    /** gateway ノードでは null。 */
    val capacity: Long? = null,
)

@Serializable
data class FreeSpace(val available: Long, val total: Long)
