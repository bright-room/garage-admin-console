package net.brightroom.garage.server.routes

import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.brightroom.garage.server.service.GarageAdminClient

fun Route.keyRoutes(client: GarageAdminClient) {
    route("/keys") {
        get {
            val response = client.get("/v2/ListKeys")
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        post {
            val body = call.receiveText()
            val response = client.post("/v2/CreateKey", body)
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        post("/import") {
            val body = call.receiveText()
            val response = client.post("/v2/ImportKey", body)
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        get("/{id}") {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val showSecret = call.request.queryParameters["showSecretKey"] ?: "false"
            val response = client.get("/v2/GetKeyInfo?id=$id&showSecretKey=$showSecret")
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        post("/{id}") {
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val body = call.receiveText()
            val response = client.post("/v2/UpdateKey?id=$id", body)
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        delete("/{id}") {
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val response = client.post("/v2/DeleteKey", """{"accessKeyId":"$id"}""")
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
    }
}
