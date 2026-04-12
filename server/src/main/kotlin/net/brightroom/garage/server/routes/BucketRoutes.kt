package net.brightroom.garage.server.routes

import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.brightroom.garage.server.service.GarageAdminClient

fun Route.bucketRoutes(client: GarageAdminClient) {
    route("/buckets") {
        get {
            val response = client.get("/v2/ListBuckets")
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        post {
            val body = call.receiveText()
            val response = client.post("/v2/CreateBucket", body)
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        get("/{id}") {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val response = client.get("/v2/GetBucketInfo?id=$id")
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        post("/{id}") {
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val body = call.receiveText()
            val response = client.post("/v2/UpdateBucket?id=$id", body)
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        delete("/{id}") {
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val response = client.post("/v2/DeleteBucket", """{"id":"$id"}""")
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        post("/{id}/alias") {
            val body = call.receiveText()
            val response = client.post("/v2/AddBucketAlias", body)
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        delete("/{id}/alias") {
            val body = call.receiveText()
            val response = client.post("/v2/RemoveBucketAlias", body)
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        post("/{id}/allow-key") {
            val body = call.receiveText()
            val response = client.post("/v2/AllowBucketKey", body)
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        post("/{id}/deny-key") {
            val body = call.receiveText()
            val response = client.post("/v2/DenyBucketKey", body)
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        post("/{id}/cleanup") {
            val body = call.receiveText()
            val response = client.post("/v2/CleanupIncompleteUploads", body)
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
    }
}
