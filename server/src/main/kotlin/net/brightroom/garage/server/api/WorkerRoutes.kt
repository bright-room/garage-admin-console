package net.brightroom.garage.server.api

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.garage.getWorkerInfo
import net.brightroom.garage.server.garage.getWorkerVariables
import net.brightroom.garage.server.garage.listWorkers
import net.brightroom.garage.server.garage.setWorkerVariable
import net.brightroom.garage.shared.api.SetWorkerVariableRequest

/**
 * ワーカーのルート。
 *
 * `/variables` は `/{id}` より先に書く。Ktor は定数セグメントを優先するので
 * 順序を変えても解決されるが、読む側が取り違えないようにこの順にする。
 */
fun Route.workerRoutes(client: GarageAdminClient) {
    route("/workers") {
        get {
            call.respond(client.listWorkers(call.adminToken()))
        }

        get("/variables") {
            call.respond(client.getWorkerVariables(call.adminToken()))
        }

        put("/variables") {
            val token = call.adminToken()
            val request = call.receive<SetWorkerVariableRequest>()

            if (request.variable.isBlank()) throw InvalidRequestException("変数名が空です")

            call.respond(client.setWorkerVariable(token, request))
        }

        get("/{id}") {
            val id = call.pathParam("id").toLongOrNull()
                ?: throw InvalidRequestException("ワーカー ID は整数です")

            call.respond(client.getWorkerInfo(call.adminToken(), id))
        }
    }
}
