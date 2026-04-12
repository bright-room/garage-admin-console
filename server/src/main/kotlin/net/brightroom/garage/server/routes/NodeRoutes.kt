package net.brightroom.garage.server.routes

import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.brightroom.garage.server.service.GarageAdminClient

fun Route.nodeRoutes(client: GarageAdminClient) {
    route("/nodes") {
        get("/info") {
            val node = call.request.queryParameters["node"]
            val path = if (node != null) "/v2/GetNodeInfo?node=$node" else "/v2/GetNodeInfo"
            val response = client.get(path)
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        get("/statistics") {
            val node = call.request.queryParameters["node"]
            val path = if (node != null) "/v2/GetNodeStatistics?node=$node" else "/v2/GetNodeStatistics"
            val response = client.get(path)
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        post("/snapshot") {
            val body = call.receiveText()
            val response = client.post("/v2/CreateMetadataSnapshot", body)
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        post("/repair") {
            val body = call.receiveText()
            val response = client.post("/v2/LaunchRepairOperation", body)
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
    }
}
