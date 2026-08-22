package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.Serializable

/** `GetClusterHealth` のレスポンス。 */
@Serializable
data class ClusterHealth(
    /** `healthy` / `degraded` / `unavailable` のいずれか。 */
    val status: String,
    val knownNodes: Int,
    val connectedNodes: Int,
    val storageNodes: Int,
    val storageNodesUp: Int,
    val partitions: Int,
    val partitionsQuorum: Int,
    val partitionsAllOk: Int,
) {
    val isHealthy: Boolean get() = status == "healthy"
}
