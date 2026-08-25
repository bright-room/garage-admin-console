package net.brightroom.garage.shared.api

import kotlinx.serialization.Serializable
import net.brightroom.garage.shared.model.garage.ClusterHealth
import net.brightroom.garage.shared.model.garage.ClusterStatus
import net.brightroom.garage.shared.model.garage.LayoutParameters
import net.brightroom.garage.shared.model.garage.NodeRoleChange

/**
 * `GET /api/cluster` の応答。
 *
 * `GetClusterStatus` と `GetClusterHealth` を 1 リクエストにまとめる（spec §7）。
 * `/api/overview` とは別物である。あちらは scope 縮退のためにセクション単位の
 * 成否を持つ契約であり、こちらは両方そろって初めて意味を持つ（P3-15）。
 */
@Serializable
data class ClusterView(val status: ClusterStatus, val health: ClusterHealth)

/** `POST /api/cluster/connect`。要素は `<nodeId>@<host>:<port>` の形。 */
@Serializable
data class ConnectNodesRequest(val nodes: List<String>)

/** 接続の試行結果。要求した [node] と対応する。 */
@Serializable
data class ConnectNodeResult(val node: String, val success: Boolean, val error: String? = null)

/**
 * `POST /api/layout/roles`。stage するだけで適用はしない。
 *
 * [parameters] を省略すると `zoneRedundancy` は変更されない。
 */
@Serializable
data class StageRolesRequest(val roles: List<NodeRoleChange> = emptyList(), val parameters: LayoutParameters? = null)

/** `POST /api/layout/apply`。[version] は適用後の版番号（現在の版 + 1）。 */
@Serializable
data class ApplyLayoutRequest(val version: Long)

/**
 * `POST /api/layout/skip-dead-nodes`。
 *
 * @param allowMissingData 残ったノードでデータの quorum が得られなくても続行する。
 */
@Serializable
data class SkipDeadNodesRequest(val version: Long, val allowMissingData: Boolean = false)

/**
 * `POST /api/nodes/repair`。
 *
 * Garage の `RepairType` は文字列 9 種と `{"scrub": …}` の oneOf だが、その形の
 * 組み立ては `:server` の `garage/` に閉じる。コンソールの契約では
 * [repairType] が `"scrub"` のときだけ [scrubCommand] を伴う。
 */
@Serializable
data class RepairRequest(val repairType: String, val scrubCommand: String? = null)
