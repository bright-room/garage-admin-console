package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.Serializable

/** `GetClusterLayout` のレスポンス。`PreviewClusterLayoutChanges` の `newLayout` も同じ形。 */
@Serializable
data class ClusterLayout(
    val version: Long,
    val roles: List<LayoutNodeRole> = emptyList(),
    val parameters: LayoutParameters? = null,
    /** 1 パーティション（シャード）のバイト数。 */
    val partitionSize: Long = 0,
    val stagedRoleChanges: List<NodeRoleChange> = emptyList(),
    val stagedParameters: LayoutParameters? = null,
)

/**
 * レイアウト上のノードの役割。
 *
 * `GetClusterStatus` の [NodeAssignedRole] とは別の型である。あちらは `id` を持たず、
 * `storedPartitions` / `usableCapacity` も無い。使い回さないこと。
 */
@Serializable
data class LayoutNodeRole(
    val id: String,
    val zone: String,
    val tags: List<String> = emptyList(),
    /** gateway ノードでは null。 */
    val capacity: Long? = null,
    /** レイアウト計算の結果として、このノードが保持するパーティション数。 */
    val storedPartitions: Long? = null,
    val usableCapacity: Long? = null,
) {
    /** capacity を持たないノードは gateway として扱われる。 */
    val isGateway: Boolean get() = capacity == null
}

@Serializable
data class LayoutParameters(val zoneRedundancy: ZoneRedundancy)

/** データを複製する最小のゾーン数。 */
@Serializable(with = ZoneRedundancySerializer::class)
sealed interface ZoneRedundancy {

    /** 可能な限り多くのゾーンに複製する。 */
    data object Maximum : ZoneRedundancy

    data class AtLeast(val zones: Int) : ZoneRedundancy
}

/**
 * 次の版で適用されるロールの変更。
 *
 * Garage は `id` と「削除か割り当てか」を 1 つのオブジェクトに平坦化して返す。
 * 判別は `remove` キーの有無で行う。
 */
@Serializable(with = NodeRoleChangeSerializer::class)
sealed interface NodeRoleChange {

    val id: String

    data class Remove(override val id: String) : NodeRoleChange

    data class Assign(
        override val id: String,
        val zone: String,
        val tags: List<String> = emptyList(),
        /** null なら gateway として割り当てる。 */
        val capacity: Long? = null,
    ) : NodeRoleChange
}
