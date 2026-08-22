package net.brightroom.garage.server.api

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.garage.GarageException
import net.brightroom.garage.server.garage.garageBody
import net.brightroom.garage.server.garage.garageBodyWith
import net.brightroom.garage.shared.api.LayoutSummary
import net.brightroom.garage.shared.api.Overview
import net.brightroom.garage.shared.api.Section
import net.brightroom.garage.shared.api.StorageSummary
import net.brightroom.garage.shared.api.toSummary
import net.brightroom.garage.shared.model.garage.BlockError
import net.brightroom.garage.shared.model.garage.ClusterHealth
import net.brightroom.garage.shared.model.garage.ClusterLayout
import net.brightroom.garage.shared.model.garage.ClusterStatus
import net.brightroom.garage.shared.model.garage.MultiResponse

/**
 * 概況を 1 リクエストで組み立てる。
 *
 * 各セクションを並列に取得し、scope 不足（403）はそのセクションだけを
 * [Section.Denied] に落として全体は成功させる。
 */
class OverviewService(private val client: GarageAdminClient) {

    suspend fun build(token: String): Overview = coroutineScope {
        val health = async {
            section {
                client.get(token, HEALTH).garageBody<ClusterHealth>(HEALTH)
            }
        }

        val nodes = async {
            section {
                client.get(token, STATUS).garageBody<ClusterStatus>(STATUS)
                    .nodes
                    .map { it.toSummary() }
            }
        }

        val layout = async {
            section {
                val response = client.get(token, LAYOUT).garageBody<ClusterLayout>(LAYOUT)
                LayoutSummary(
                    version = response.version,
                    stagedChanges = response.stagedRoleChanges.size,
                )
            }
        }

        val storage = async {
            section {
                val buckets = client.get(token, LIST_BUCKETS).garageBody<JsonArray>(LIST_BUCKETS)
                val keys = client.get(token, LIST_KEYS).garageBody<JsonArray>(LIST_KEYS)
                StorageSummary(buckets = buckets.size, keys = keys.size)
            }
        }

        val blockErrors = async {
            section {
                // MultiResponse はジェネリック型のため serializer を明示する
                val response = client.get(token, LIST_BLOCK_ERRORS, mapOf("node" to "*"))
                    .garageBodyWith(
                        LIST_BLOCK_ERRORS,
                        MultiResponse.serializer(ListSerializer(BlockError.serializer())),
                    )
                response.success.values.sumOf { it.size }
            }
        }

        Overview(
            health = health.await(),
            nodes = nodes.await(),
            layout = layout.await(),
            storage = storage.await(),
            blockErrors = blockErrors.await(),
        )
    }

    /**
     * 403 は正常系として [Section.Denied] に、その他の失敗は [Section.Failed] に落とす。
     * 401 だけは概況全体を無意味にするため再スローする。
     */
    private suspend fun <T> section(block: suspend () -> T): Section<T> =
        try {
            Section.Loaded(block())
        } catch (e: GarageException) {
            when (e.status) {
                HttpStatusCode.Unauthorized -> throw e
                HttpStatusCode.Forbidden -> Section.Denied(e.operation)
                else -> Section.Failed(e.message)
            }
        }

    private companion object {
        const val HEALTH = "GetClusterHealth"
        const val STATUS = "GetClusterStatus"
        const val LAYOUT = "GetClusterLayout"
        const val LIST_BUCKETS = "ListBuckets"
        const val LIST_KEYS = "ListKeys"
        const val LIST_BLOCK_ERRORS = "ListBlockErrors"
    }
}
