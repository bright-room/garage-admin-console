package net.brightroom.garage.server.routes

import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.brightroom.garage.server.service.GarageAdminClient

fun Route.blockRoutes(client: GarageAdminClient) {
    route("/blocks") {
        get("/errors") {
            val node = call.request.queryParameters["node"]
            val path = if (node != null) "/v2/ListBlockErrors?node=$node" else "/v2/ListBlockErrors"
            val response = client.get(path)
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        post("/info") {
            val body = call.receiveText()
            val response = client.post("/v2/GetBlockInfo", body)
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        post("/retry-resync") {
            val body = call.receiveText()
            val response = client.post("/v2/RetryBlockResync", body)
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        post("/purge") {
            val body = call.receiveText()
            val response = client.post("/v2/PurgeBlocks", body)
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
    }
}
