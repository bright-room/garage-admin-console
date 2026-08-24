package net.brightroom.garage.server.garage

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.brightroom.garage.server.plugins.GarageJson
import net.brightroom.garage.shared.api.SkipDeadNodesRequest
import net.brightroom.garage.shared.api.StageRolesRequest
import net.brightroom.garage.shared.model.garage.ClusterLayout
import net.brightroom.garage.shared.model.garage.LayoutHistory
import net.brightroom.garage.shared.model.garage.LayoutPreview

/**
 * レイアウト系 operation への型付きアクセス。
 *
 * 更新系（`UpdateClusterLayout` / `ApplyClusterLayout` / `RevertClusterLayout`）は
 * いずれも更新後のレイアウトを返す。`ClusterLayoutSkipDeadNodes` だけは履歴を返す。
 */

private const val GET_CLUSTER_LAYOUT = "GetClusterLayout"
private const val UPDATE_CLUSTER_LAYOUT = "UpdateClusterLayout"
private const val PREVIEW_CLUSTER_LAYOUT_CHANGES = "PreviewClusterLayoutChanges"
private const val APPLY_CLUSTER_LAYOUT = "ApplyClusterLayout"
private const val REVERT_CLUSTER_LAYOUT = "RevertClusterLayout"
private const val GET_CLUSTER_LAYOUT_HISTORY = "GetClusterLayoutHistory"
private const val CLUSTER_LAYOUT_SKIP_DEAD_NODES = "ClusterLayoutSkipDeadNodes"

suspend fun GarageAdminClient.getLayout(token: String): ClusterLayout =
    get(token, GET_CLUSTER_LAYOUT).garageBody(GET_CLUSTER_LAYOUT)

/**
 * ロールの変更を stage する。適用はしない。
 *
 * [StageRolesRequest] は Garage の `UpdateClusterLayoutRequest` と同じ形なので
 * そのまま送る。`parameters` を省略すると `zoneRedundancy` は変更されない
 * （`GarageJson` の `explicitNulls = false` がこの意味論を成立させている）。
 */
suspend fun GarageAdminClient.stageRoles(token: String, request: StageRolesRequest): ClusterLayout = post(
    token,
    UPDATE_CLUSTER_LAYOUT,
    GarageJson.encodeToJsonElement(StageRolesRequest.serializer(), request),
).garageBody(UPDATE_CLUSTER_LAYOUT)

/**
 * staged 変更を適用した場合のレイアウトを計算する。
 *
 * クラスタの状態は変わらない。計算できなかった場合も HTTP は 200 であり、
 * 本文が [LayoutPreview.Failed] の形になる。
 */
suspend fun GarageAdminClient.previewLayout(token: String): LayoutPreview =
    post(token, PREVIEW_CLUSTER_LAYOUT_CHANGES).garageBody(PREVIEW_CLUSTER_LAYOUT_CHANGES)

/** @param version 適用後の版番号。Garage は安全策としてこれを要求する。 */
suspend fun GarageAdminClient.applyLayout(token: String, version: Long): ClusterLayout = post(
    token,
    APPLY_CLUSTER_LAYOUT,
    buildJsonObject { put("version", version) },
).garageBody(APPLY_CLUSTER_LAYOUT)

suspend fun GarageAdminClient.revertLayout(token: String): ClusterLayout =
    post(token, REVERT_CLUSTER_LAYOUT).garageBody(REVERT_CLUSTER_LAYOUT)

suspend fun GarageAdminClient.getLayoutHistory(token: String): LayoutHistory =
    get(token, GET_CLUSTER_LAYOUT_HISTORY).garageBody(GET_CLUSTER_LAYOUT_HISTORY)

suspend fun GarageAdminClient.skipDeadNodes(token: String, request: SkipDeadNodesRequest): LayoutHistory = post(
    token,
    CLUSTER_LAYOUT_SKIP_DEAD_NODES,
    GarageJson.encodeToJsonElement(SkipDeadNodesRequest.serializer(), request),
).garageBody(CLUSTER_LAYOUT_SKIP_DEAD_NODES)
