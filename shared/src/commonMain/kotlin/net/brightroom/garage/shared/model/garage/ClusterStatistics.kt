package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.Serializable

/** `GetClusterStatistics` のレスポンス。 */
@Serializable
data class ClusterStatistics(
    /** Garage が組み立てた人間向けのテキスト。整形せずそのまま出す。 */
    val freeform: String = "",
    val dataAvail: Long? = null,
    val metadataAvail: Long? = null,
    /** 一部のノードから空き容量を取れなかった。数値を過信しないための印。 */
    val incompleteAvailInfo: Boolean = false,
    val bucketCount: Long = 0,
    val totalObjectCount: Long = 0,
    val totalObjectBytes: Long = 0,
)
