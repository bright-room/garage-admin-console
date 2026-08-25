package net.brightroom.garage.server.garage

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.brightroom.garage.shared.api.NodeActionOutcome
import net.brightroom.garage.shared.api.RetryResyncRequest
import net.brightroom.garage.shared.model.garage.BlockError
import net.brightroom.garage.shared.model.garage.BlockInfo
import net.brightroom.garage.shared.model.garage.MultiResponse

/**
 * ブロック系 operation への型付きアクセス。
 *
 * `PurgeBlocks` だけは本文のトップレベルが JSON 配列である。
 */

private const val LIST_BLOCK_ERRORS = "ListBlockErrors"
private const val GET_BLOCK_INFO = "GetBlockInfo"
private const val RETRY_BLOCK_RESYNC = "RetryBlockResync"
private const val PURGE_BLOCKS = "PurgeBlocks"

private val allNodes = mapOf("node" to ALL_NODES)

suspend fun GarageAdminClient.listBlockErrors(token: String): MultiResponse<List<BlockError>> =
    get(token, LIST_BLOCK_ERRORS, allNodes)
        .garageBodyWith(LIST_BLOCK_ERRORS, MultiResponse.serializer(ListSerializer(BlockError.serializer())))

suspend fun GarageAdminClient.getBlockInfo(token: String, hash: String): MultiResponse<BlockInfo> =
    post(token, GET_BLOCK_INFO, buildJsonObject { put("blockHash", hash) }, allNodes)
        .garageBodyWith(GET_BLOCK_INFO, MultiResponse.serializer(BlockInfo.serializer()))

/**
 * 再同期を試み直す。
 *
 * Garage は `{"all": true}` と `{"blockHashes": [...]}` の 2 形を受け付ける。
 * 両方を送ることはできないため、[RetryResyncRequest.all] を優先する。
 */
suspend fun GarageAdminClient.retryBlockResync(token: String, request: RetryResyncRequest): NodeActionOutcome {
    val body = if (request.all) {
        buildJsonObject { put("all", true) }
    } else {
        buildJsonObject { putJsonArray("blockHashes") { request.blockHashes.forEach { add(it) } } }
    }

    return post(token, RETRY_BLOCK_RESYNC, body, allNodes)
        .garageBodyWith(RETRY_BLOCK_RESYNC, MultiResponse.serializer(JsonElement.serializer()))
        .toOutcome()
}

/** 本文のトップレベルが配列である点が他の operation と異なる。 */
suspend fun GarageAdminClient.purgeBlocks(token: String, hashes: List<String>): NodeActionOutcome =
    post(token, PURGE_BLOCKS, JsonArray(hashes.map { JsonPrimitive(it) }), allNodes)
        .garageBodyWith(PURGE_BLOCKS, MultiResponse.serializer(JsonElement.serializer()))
        .toOutcome()
