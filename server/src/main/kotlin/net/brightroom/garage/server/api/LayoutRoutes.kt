package net.brightroom.garage.server.api

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.garage.applyLayout
import net.brightroom.garage.server.garage.getLayout
import net.brightroom.garage.server.garage.getLayoutHistory
import net.brightroom.garage.server.garage.previewLayout
import net.brightroom.garage.server.garage.revertLayout
import net.brightroom.garage.server.garage.skipDeadNodes
import net.brightroom.garage.server.garage.stageRoles
import net.brightroom.garage.shared.api.ApplyLayoutRequest
import net.brightroom.garage.shared.api.SkipDeadNodesRequest
import net.brightroom.garage.shared.api.StageRolesRequest

/**
 * レイアウトのルート。
 *
 * `POST /layout/roles` は stage するだけで適用しない。適用は `/layout/apply` で
 * 明示的に行い、web はその前に必ず `/layout/preview` を挟む（spec §8.6）。
 * **preview を強制する仕組みはサーバーには置かない。** Garage の operation を
 * 1:1 で写すのがこの層の役割であり、順序の担保は UI 側の責務である。
 */
fun Route.layoutRoutes(client: GarageAdminClient) {
    route("/layout") {
        get {
            call.respond(client.getLayout(call.adminToken()))
        }

        post("/roles") {
            val token = call.adminToken()
            val request = call.receive<StageRolesRequest>()

            if (request.roles.isEmpty() && request.parameters == null) {
                throw InvalidRequestException("stage する変更がありません")
            }

            call.respond(client.stageRoles(token, request))
        }

        // 計算できなかった場合も 200 を返す。失敗は本文の形で表される
        post("/preview") {
            call.respond(client.previewLayout(call.adminToken()))
        }

        post("/apply") {
            val token = call.adminToken()
            val request = call.receive<ApplyLayoutRequest>()

            call.respond(client.applyLayout(token, request.version))
        }

        post("/revert") {
            call.respond(client.revertLayout(call.adminToken()))
        }

        get("/history") {
            call.respond(client.getLayoutHistory(call.adminToken()))
        }

        post("/skip-dead-nodes") {
            val token = call.adminToken()
            val request = call.receive<SkipDeadNodesRequest>()

            call.respond(client.skipDeadNodes(token, request))
        }
    }
}
