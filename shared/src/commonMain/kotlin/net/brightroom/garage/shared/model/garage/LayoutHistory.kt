package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GetClusterLayoutHistory` のレスポンス。
 *
 * `updateTrackers` は運用の判断に使わないため取り込まない（spec D6）。
 */
@Serializable
data class LayoutHistory(
    val currentVersion: Long,
    /** クラスタの全ノードがこの版までを認識している。 */
    val minAck: Long,
    val versions: List<LayoutVersion> = emptyList(),
)

@Serializable
data class LayoutVersion(
    val version: Long,
    val status: LayoutVersionStatus,
    val storageNodes: Long,
    val gatewayNodes: Long,
)

/** Garage はこの enum だけ先頭大文字で返す。他の enum と揃っていない。 */
@Serializable
enum class LayoutVersionStatus {
    @SerialName("Current")
    CURRENT,

    @SerialName("Draining")
    DRAINING,

    @SerialName("Historical")
    HISTORICAL,
}
