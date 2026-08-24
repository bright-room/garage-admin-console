package net.brightroom.garage.server.garage

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.brightroom.garage.server.api.InvalidRequestException
import net.brightroom.garage.shared.api.NodeActionOutcome
import net.brightroom.garage.shared.api.RepairRequest
import net.brightroom.garage.shared.model.garage.MultiResponse
import net.brightroom.garage.shared.model.garage.NodeInfo
import net.brightroom.garage.shared.model.garage.NodeStatistics

/**
 * ノード系 operation への型付きアクセス。
 *
 * これらは常に全ノードに問い合わせる。ノードを選ぶ UI は作らないため（P3-11）、
 * `node` クエリは [ALL_NODES] で固定する。
 */

// Garage の `node` クエリで「全ノード」を表す値。ワーカーとブロックも同じ値を使う。
internal const val ALL_NODES: String = "*"

private const val GET_NODE_INFO = "GetNodeInfo"
private const val GET_NODE_STATISTICS = "GetNodeStatistics"
private const val CREATE_METADATA_SNAPSHOT = "CreateMetadataSnapshot"
private const val LAUNCH_REPAIR_OPERATION = "LaunchRepairOperation"

/** Garage が `repairType` に受け付ける文字列。`scrub` だけは別扱いになる。 */
private val PLAIN_REPAIR_TYPES = setOf(
    "tables",
    "blocks",
    "versions",
    "multipartUploads",
    "blockRefs",
    "blockRc",
    "rebalance",
    "aliases",
    "clearResyncQueue",
)

private const val SCRUB = "scrub"

private val SCRUB_COMMANDS = setOf("start", "pause", "resume", "cancel")

/**
 * 副作用だけの operation の結果を落とし込む。
 *
 * Garage は成功したノードの値に `null` を返す。値に意味が無いので、成功した
 * ノード名の一覧と失敗の理由だけにする。ワーカーとブロックの操作も同じ形を返す。
 */
internal fun MultiResponse<JsonElement>.toOutcome(): NodeActionOutcome =
    NodeActionOutcome(ok = success.keys.sorted(), failed = error)

suspend fun GarageAdminClient.getNodeInfo(token: String): MultiResponse<NodeInfo> =
    get(token, GET_NODE_INFO, mapOf("node" to ALL_NODES))
        .garageBodyWith(GET_NODE_INFO, MultiResponse.serializer(NodeInfo.serializer()))

suspend fun GarageAdminClient.getNodeStatistics(token: String): MultiResponse<NodeStatistics> =
    get(token, GET_NODE_STATISTICS, mapOf("node" to ALL_NODES))
        .garageBodyWith(GET_NODE_STATISTICS, MultiResponse.serializer(NodeStatistics.serializer()))

suspend fun GarageAdminClient.createMetadataSnapshot(token: String): NodeActionOutcome =
    post(token, CREATE_METADATA_SNAPSHOT, params = mapOf("node" to ALL_NODES))
        .garageBodyWith(CREATE_METADATA_SNAPSHOT, MultiResponse.serializer(JsonElement.serializer()))
        .toOutcome()

/**
 * 修復を開始する。
 *
 * Garage の `repairType` は文字列 9 種と `{"scrub": …}` の oneOf である。
 * コンソールの契約は 2 つの文字列フィールドなので、その形への変換をここで閉じる。
 */
suspend fun GarageAdminClient.launchRepair(token: String, request: RepairRequest): NodeActionOutcome {
    val body = buildJsonObject { put("repairType", request.toGarageRepairType()) }

    return post(token, LAUNCH_REPAIR_OPERATION, body, mapOf("node" to ALL_NODES))
        .garageBodyWith(LAUNCH_REPAIR_OPERATION, MultiResponse.serializer(JsonElement.serializer()))
        .toOutcome()
}

private fun RepairRequest.toGarageRepairType(): JsonElement {
    if (repairType == SCRUB) {
        val command = scrubCommand
            ?: throw InvalidRequestException("scrub には scrubCommand が必要です")

        if (command !in SCRUB_COMMANDS) throw InvalidRequestException("未知の scrubCommand です: $command")

        return buildJsonObject { put(SCRUB, command) }
    }

    if (repairType !in PLAIN_REPAIR_TYPES) throw InvalidRequestException("未知の repairType です: $repairType")

    return JsonPrimitive(repairType)
}
