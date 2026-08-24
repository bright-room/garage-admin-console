package net.brightroom.garage.server.garage

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import net.brightroom.garage.shared.api.ConnectNodeResult
import net.brightroom.garage.shared.model.garage.ClusterHealth
import net.brightroom.garage.shared.model.garage.ClusterStatistics
import net.brightroom.garage.shared.model.garage.ClusterStatus

/**
 * クラスタ系 operation への型付きアクセス。
 *
 * Garage の operation 名はこのファイルの外に出さない。web が見るのは
 * リソース指向の `/api/cluster` 以下だけである（spec §7）。
 */

private const val GET_CLUSTER_STATUS = "GetClusterStatus"
private const val GET_CLUSTER_HEALTH = "GetClusterHealth"
private const val GET_CLUSTER_STATISTICS = "GetClusterStatistics"
private const val CONNECT_CLUSTER_NODES = "ConnectClusterNodes"

suspend fun GarageAdminClient.getClusterStatus(token: String): ClusterStatus =
    get(token, GET_CLUSTER_STATUS).garageBody(GET_CLUSTER_STATUS)

suspend fun GarageAdminClient.getClusterHealth(token: String): ClusterHealth =
    get(token, GET_CLUSTER_HEALTH).garageBody(GET_CLUSTER_HEALTH)

suspend fun GarageAdminClient.getClusterStatistics(token: String): ClusterStatistics =
    get(token, GET_CLUSTER_STATISTICS).garageBody(GET_CLUSTER_STATISTICS)

/**
 * ノードへの接続を試みる。
 *
 * Garage は本文のトップレベルに文字列配列を要求し、要求と同じ順で結果を返す。
 * 応答自体は接続先を含まないため、ここで要求と突き合わせて返す。
 */
suspend fun GarageAdminClient.connectNodes(token: String, nodes: List<String>): List<ConnectNodeResult> {
    val body = JsonArray(nodes.map { JsonPrimitive(it) })
    val results = post(token, CONNECT_CLUSTER_NODES, body)
        .garageBodyWith(CONNECT_CLUSTER_NODES, ListSerializer(ConnectAttempt.serializer()))

    return nodes.mapIndexed { index, node ->
        val attempt = results.getOrNull(index)

        ConnectNodeResult(
            node = node,
            success = attempt?.success == true,
            error = attempt?.error ?: "Garage が結果を返しませんでした".takeIf { attempt == null },
        )
    }
}

/** Garage の `ConnectNodeResponse`。接続先を含まないため外には出さない。 */
@Serializable
private data class ConnectAttempt(val success: Boolean, val error: String? = null)
