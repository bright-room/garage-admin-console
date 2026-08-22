package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * クラスタ全体の健全性。Garage Admin API v2 が定める 3 値。
 *
 * 未知の値が来た場合はデシリアライズに失敗し、概況の該当セクションだけが
 * `Section.Failed` に落ちる。他のセクションの描画は妨げない。
 */
@Serializable
enum class ClusterHealthStatus {
    /** すべてのストレージノードに接続できている。 */
    @SerialName("healthy")
    HEALTHY,

    /** 一部のストレージノードに接続できていないが、全パーティションで書き込みquorumがある。 */
    @SerialName("degraded")
    DEGRADED,

    /** 一部のパーティションで書き込みquorumが得られていない。 */
    @SerialName("unavailable")
    UNAVAILABLE,
}

/** `GetClusterHealth` のレスポンス。 */
@Serializable
data class ClusterHealth(
    val status: ClusterHealthStatus,
    val knownNodes: Int,
    val connectedNodes: Int,
    val storageNodes: Int,
    val storageNodesUp: Int,
    val partitions: Int,
    val partitionsQuorum: Int,
    val partitionsAllOk: Int,
) {
    val isHealthy: Boolean get() = status == ClusterHealthStatus.HEALTHY
}
