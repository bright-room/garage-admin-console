package net.brightroom.garage.shared.model.cluster

import kotlinx.serialization.Serializable

@Serializable
data class ClusterStatus(
    val layoutVersion: Long,
    val nodes: List<NodeResp>,
)

@Serializable
data class NodeResp(
    val id: String,
    val isUp: Boolean,
    val draining: Boolean,
    val addr: String? = null,
    val hostname: String? = null,
    val garageVersion: String? = null,
    val lastSeenSecsAgo: Long? = null,
    val role: NodeAssignedRole? = null,
    val dataPartition: FreeSpaceResp? = null,
    val metadataPartition: FreeSpaceResp? = null,
)

@Serializable
data class NodeAssignedRole(
    val zone: String,
    val tags: List<String>,
    val capacity: Long? = null,
)

@Serializable
data class FreeSpaceResp(
    val available: Long,
    val total: Long,
)
