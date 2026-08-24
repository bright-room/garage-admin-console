package net.brightroom.garage.server.api

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.garage.createMetadataSnapshot
import net.brightroom.garage.server.garage.getNodeInfo
import net.brightroom.garage.server.garage.getNodeStatistics
import net.brightroom.garage.server.garage.launchRepair
import net.brightroom.garage.shared.api.RepairRequest

/**
 * ノードのルート。
 *
 * いずれも全ノードに問い合わせ、ノード別の成否を潰さずに返す（spec §7.3）。
 */
fun Route.nodeRoutes(client: GarageAdminClient) {
    route("/nodes") {
        get("/info") {
            call.respond(client.getNodeInfo(call.adminToken()))
        }

        get("/statistics") {
            call.respond(client.getNodeStatistics(call.adminToken()))
        }

        post("/snapshot") {
            call.respond(client.createMetadataSnapshot(call.adminToken()))
        }

        post("/repair") {
            val token = call.adminToken()
            val request = call.receive<RepairRequest>()

            call.respond(client.launchRepair(token, request))
        }
    }
}
