package net.brightroom.garage.server.garage

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.brightroom.garage.shared.api.NodeActionOutcome
import net.brightroom.garage.shared.api.SetWorkerVariableRequest
import net.brightroom.garage.shared.model.garage.MultiResponse
import net.brightroom.garage.shared.model.garage.WorkerInfo

/**
 * ワーカー系 operation への型付きアクセス。
 *
 * `ListWorkers` と `GetWorkerVariable` は読み取りだが Garage 側は POST である。
 * コンソールの `/api/workers` は GET なので、その差はここで吸収する。
 */

private const val LIST_WORKERS = "ListWorkers"
private const val GET_WORKER_INFO = "GetWorkerInfo"
private const val GET_WORKER_VARIABLE = "GetWorkerVariable"
private const val SET_WORKER_VARIABLE = "SetWorkerVariable"

private val allNodes = mapOf("node" to ALL_NODES)

/** 絞り込みは行わない。画面側で状態別に並べ替える。 */
suspend fun GarageAdminClient.listWorkers(token: String): MultiResponse<List<WorkerInfo>> =
    post(token, LIST_WORKERS, JsonObject(emptyMap()), allNodes)
        .garageBodyWith(LIST_WORKERS, MultiResponse.serializer(ListSerializer(WorkerInfo.serializer())))

suspend fun GarageAdminClient.getWorkerInfo(token: String, id: Long): MultiResponse<WorkerInfo> =
    post(token, GET_WORKER_INFO, buildJsonObject { put("id", id) }, allNodes)
        .garageBodyWith(GET_WORKER_INFO, MultiResponse.serializer(WorkerInfo.serializer()))

/** 変数名を指定せずに呼ぶと、そのノードが持つ変数がすべて返る。 */
suspend fun GarageAdminClient.getWorkerVariables(token: String): MultiResponse<Map<String, String>> =
    post(token, GET_WORKER_VARIABLE, JsonObject(emptyMap()), allNodes)
        .garageBodyWith(
            GET_WORKER_VARIABLE,
            MultiResponse.serializer(MapSerializer(String.serializer(), String.serializer())),
        )

suspend fun GarageAdminClient.setWorkerVariable(token: String, request: SetWorkerVariableRequest): NodeActionOutcome =
    post(
        token,
        SET_WORKER_VARIABLE,
        buildJsonObject {
            put("variable", request.variable)
            put("value", request.value)
        },
        allNodes,
    ).garageBodyWith(SET_WORKER_VARIABLE, MultiResponse.serializer(JsonElement.serializer())).toOutcome()
