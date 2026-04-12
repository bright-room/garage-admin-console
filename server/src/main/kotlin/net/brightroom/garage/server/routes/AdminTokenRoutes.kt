package net.brightroom.garage.server.routes

import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.brightroom.garage.server.service.GarageAdminClient

fun Route.adminTokenRoutes(client: GarageAdminClient) {
    route("/admin-tokens") {
        get {
            val response = client.get("/v2/ListAdminTokens")
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        post {
            val body = call.receiveText()
            val response = client.post("/v2/CreateAdminToken", body)
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        get("/current") {
            val response = client.get("/v2/GetCurrentAdminTokenInfo")
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        get("/{id}") {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val response = client.get("/v2/GetAdminTokenInfo?id=$id")
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        post("/{id}") {
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val body = call.receiveText()
            val response = client.post("/v2/UpdateAdminToken?id=$id", body)
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        delete("/{id}") {
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val response = client.post("/v2/DeleteAdminToken", """{"id":"$id"}""")
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
    }
}
