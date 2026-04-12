package net.brightroom.garage.server.routes

import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.brightroom.garage.server.service.GarageAdminClient

fun Route.layoutRoutes(client: GarageAdminClient) {
    route("/layout") {
        get {
            val response = client.get("/v2/GetClusterLayout")
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        post("/update") {
            val body = call.receiveText()
            val response = client.post("/v2/UpdateClusterLayout", body)
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        post("/apply") {
            val body = call.receiveText()
            val response = client.post("/v2/ApplyClusterLayout", body)
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        post("/revert") {
            val response = client.post("/v2/RevertClusterLayout")
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        post("/preview") {
            val response = client.post("/v2/PreviewClusterLayoutChanges")
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        get("/history") {
            val response = client.get("/v2/GetClusterLayoutHistory")
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        post("/skip-dead-nodes") {
            val body = call.receiveText()
            val response = client.post("/v2/ClusterLayoutSkipDeadNodes", body)
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
    }
}
