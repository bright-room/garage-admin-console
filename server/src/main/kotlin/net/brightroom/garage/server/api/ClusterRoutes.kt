package net.brightroom.garage.server.api

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.garage.connectNodes
import net.brightroom.garage.server.garage.getClusterHealth
import net.brightroom.garage.server.garage.getClusterStatistics
import net.brightroom.garage.server.garage.getClusterStatus
import net.brightroom.garage.shared.api.ClusterView
import net.brightroom.garage.shared.api.ConnectNodesRequest

/**
 * クラスタのルート。
 *
 * `/api/cluster` は状態と健全性をまとめて返す。どちらか一方が 403 なら全体を
 * 403 にする。`/api/overview` のようなセクション単位の縮退はここでは行わない
 * （P3-15）。片方だけの画面は意味を持たないためである。
 */
fun Route.clusterRoutes(client: GarageAdminClient) {
    route("/cluster") {
        get {
            val token = call.adminToken()

            // 2 つの operation は独立している。順に待つ理由が無い
            val view = coroutineScope {
                val status = async { client.getClusterStatus(token) }
                val health = async { client.getClusterHealth(token) }

                ClusterView(status = status.await(), health = health.await())
            }

            call.respond(view)
        }

        get("/statistics") {
            call.respond(client.getClusterStatistics(call.adminToken()))
        }

        post("/connect") {
            val token = call.adminToken()
            val request = call.receive<ConnectNodesRequest>()

            if (request.nodes.isEmpty()) throw InvalidRequestException("接続先のノードを 1 つ以上指定してください")

            call.respond(client.connectNodes(token, request.nodes))
        }
    }
}
