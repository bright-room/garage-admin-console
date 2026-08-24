package net.brightroom.garage.server.api

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.garage.getBlockInfo
import net.brightroom.garage.server.garage.listBlockErrors
import net.brightroom.garage.server.garage.purgeBlocks
import net.brightroom.garage.server.garage.retryBlockResync
import net.brightroom.garage.shared.api.PurgeBlocksRequest
import net.brightroom.garage.shared.api.RetryResyncRequest

/**
 * ブロックのルート。
 *
 * `POST /blocks/retry-resync` は spec §7 の一覧に無いが、`RetryBlockResync` の
 * 「全件」に到達する経路が他に無いため足している（P3-14）。
 */
fun Route.blockRoutes(client: GarageAdminClient) {
    route("/blocks") {
        get("/errors") {
            call.respond(client.listBlockErrors(call.adminToken()))
        }

        post("/purge") {
            val token = call.adminToken()
            val request = call.receive<PurgeBlocksRequest>()

            if (request.blockHashes.isEmpty()) throw InvalidRequestException("対象のブロックがありません")

            call.respond(client.purgeBlocks(token, request.blockHashes))
        }

        post("/retry-resync") {
            val token = call.adminToken()
            val request = call.receive<RetryResyncRequest>()

            if (!request.all && request.blockHashes.isEmpty()) {
                throw InvalidRequestException("再同期の対象がありません")
            }

            call.respond(client.retryBlockResync(token, request))
        }

        get("/{hash}") {
            call.respond(client.getBlockInfo(call.adminToken(), call.pathParam("hash")))
        }

        post("/{hash}/retry-resync") {
            val hash = call.pathParam("hash")

            call.respond(client.retryBlockResync(call.adminToken(), RetryResyncRequest(blockHashes = listOf(hash))))
        }
    }
}
