package net.brightroom.garage.server.routes

import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.brightroom.garage.server.service.GarageAdminClient

fun Route.clusterRoutes(client: GarageAdminClient) {
    route("/cluster") {
        get("/status") {
            val response = client.get("/v2/GetClusterStatus")
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        get("/health") {
            val response = client.get("/v2/GetClusterHealth")
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        get("/statistics") {
            val response = client.get("/v2/GetClusterStatistics")
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        post("/connect") {
            val body = call.receiveText()
            val response = client.post("/v2/ConnectClusterNodes", body)
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
    }
}
