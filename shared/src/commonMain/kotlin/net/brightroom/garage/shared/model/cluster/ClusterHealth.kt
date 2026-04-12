package net.brightroom.garage.shared.model.cluster

import kotlinx.serialization.Serializable

@Serializable
data class ClusterHealth(
    val status: String,
    val knownNodes: Int,
    val connectedNodes: Int,
    val storageNodes: Int,
    val storageNodesUp: Int,
    val partitions: Int,
    val partitionsQuorum: Int,
    val partitionsAllOk: Int,
)
