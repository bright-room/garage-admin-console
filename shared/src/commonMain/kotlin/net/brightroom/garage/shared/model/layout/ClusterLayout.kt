package net.brightroom.garage.shared.model.layout

import kotlinx.serialization.Serializable

@Serializable
data class ClusterLayout(
    val version: Long,
    val roles: List<LayoutNodeRole>,
    val parameters: LayoutParameters,
    val partitionSize: Long,
    val stagedRoleChanges: List<StagedRoleChange>,
    val stagedParameters: LayoutParameters? = null,
)

@Serializable
data class LayoutNodeRole(
    val id: String,
    val zone: String,
    val tags: List<String>,
    val capacity: Long? = null,
    val storedPartitions: Long? = null,
    val usableCapacity: Long? = null,
)

@Serializable
data class LayoutParameters(
    val zoneRedundancy: kotlinx.serialization.json.JsonElement,
)

@Serializable
data class StagedRoleChange(
    val id: String,
    val remove: Boolean? = null,
    val zone: String? = null,
    val tags: List<String>? = null,
    val capacity: Long? = null,
)

@Serializable
data class LayoutHistory(
    val currentVersion: Long,
    val minAck: Long,
    val versions: List<ClusterLayoutVersion>,
    val updateTrackers: Map<String, NodeUpdateTrackers>? = null,
)

@Serializable
data class ClusterLayoutVersion(
    val version: Long,
    val status: String,
    val storageNodes: Long,
    val gatewayNodes: Long,
)

@Serializable
data class NodeUpdateTrackers(
    val ack: Long,
    val sync: Long,
    val syncAck: Long,
)

@Serializable
data class ApplyLayoutRequest(
    val version: Long,
)

@Serializable
data class ApplyLayoutResponse(
    val layout: ClusterLayout,
    val message: List<String>,
)
